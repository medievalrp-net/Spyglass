# Omniscience Dissection — 05: Commands and Display Handlers

Files covered:

- Core: `command/OmniCommands.java`, `command/AiHandler.java`, `command/PageStore.java`,
  `command/async/AsyncCallback.java`, `command/async/SearchCallback.java`,
  `command/result/CommandResult.java`, `command/result/UseResult.java`,
  `command/util/Async.java`, `command/util/SearchParameterHelper.java`.
- API: `display/DisplayHandler.java`, `display/SimpleDisplayHandler.java`,
  `display/MessageDisplayHandler.java`, `display/ItemDisplayHandler.java`,
  `display/DamageDisplayHandler.java`, `display/TeleportDisplayHandler.java`.

This is the user-facing layer: how a player goes from typing `/omni s p:Steve b:stone` to
seeing a clickable, hoverable list of things Steve did to stone blocks. It's also the
layer most tangled up in legacy Spigot-era chat APIs — every rendered line goes through
`net.md_5.bungee.api.chat.*`, not Paper's native Adventure `Component` tree.

## Responsibility

Three jobs wired through this layer:

1. **Translate chat input to query sessions.** Parse the raw `search-parameters` greedy
   string, hand it to `QuerySession.newQueryFromArguments`, coordinate the async
   round-trip to Mongo, push results back out to the player.
2. **Render results to chat.** Turn `DataEntry` instances into `BaseComponent[]` arrays
   with colour codes, hover tooltips, and click-to-run actions. Page them. Re-query when
   the player clicks a detail link.
3. **Side-channel commands.** Tool toggle, events list, rollback/restore/undo
   execution, teleport links, and the AI help assistant.

The whole surface was migrated from a bespoke `OmniSubCommand`/`SimpleCommand` hierarchy
to Incendo Cloud 2.x in this session. The migration was mostly mechanical — the command
handlers moved into `OmniCommands.register` but the rendering logic (`SearchCallback`,
`PageStore`, the display handlers) was left untouched, so the old bungee-chat API is
still everywhere below the command-routing boundary.

## Command surface

All subcommands registered in `OmniCommands.register` (`OmniCommands.java:76-137`).
Top-level command is registered with three aliases at `OmniCommands.java:83`:
`/omniscience`, `/o`, `/omni`. Every subcommand below is reachable through all three
root aliases.

| Subcommand | Aliases | Permission | Arg | Handler |
|---|---|---|---|---|
| *(root)* | — | *(none)* | — | `sendHelp` |
| `help` | `h`, `?` | *(none)* | — | `sendHelp` |
| `search` | `s`, `sc`, `lookup`, `l` | `omniscience.commands.search` | greedy string | `runSearch` |
| `page` | `p`, `pg` | `omniscience.commands.page` | int ≥1 | `PageStore.showPage` |
| `rollback` | `rb`, `roll` | `omniscience.commands.rollback` | greedy string | `runApplier(NEWEST_FIRST)` |
| `restore` | `rs`, `rst` | `omniscience.commands.rollback` | greedy string | `runApplier(OLDEST_FIRST)` |
| `undo` | `u` | `omniscience.commands.undo` | — | `runUndo` |
| `tool` | `t`, `inspect` | `omniscience.commands.tool` | — | `runTool` |
| `events` | `e` | `omniscience.commands.events` | — | `runEvents` |
| `ai` | `help-ai`, `ask` | `omniscience.commands.ai` | greedy string | `aiHandler.execute` |

Plus a second top-level command `/omnitele` (`OmniCommands.java:127-134`). Signature is
`/omnitele <worldUuid> <x> <y> <z>`. Permission is `omniscience.mayuse`. It is *not*
something users are meant to type — it's the click target for the teleport link inside
search result hover tooltips, emitted by
`DataHelper.buildLocation(location, clickable=true)` at
`DataHelper.java:165`:

```
/omnitele <worldUuid> <blockX> <blockY> <blockZ>
```

There's no token, no nonce, no expiration. Anyone with `omniscience.mayuse` can teleport
themselves to an arbitrary `(world, x, y, z)` by typing the command literally. See the
pain-points section.

### Registration shape

All aliases of a given subcommand are registered via the tiny helper at
`OmniCommands.java:139-148`:

```java
private static void registerWithAliases(
    PaperCommandManager<CommandSourceStack> mgr,
    Command.Builder<CommandSourceStack> base,
    List<String> names,
    UnaryOperator<Command.Builder<CommandSourceStack>> configure
) {
    for (String name : names) {
        mgr.command(configure.apply(base.literal(name)));
    }
}
```

For each alias name in the list, it adds a `literal(name)` node to the base command
builder, runs the configurer (which attaches the argument, permission, and handler), and
registers the resulting command with Cloud. That's a fan-out — five aliases for
`search` means five distinct registrations of what is logically one command. There is no
`alias(...)` on `Command.Builder` for multiple literals; this is how the migration
handled it.

The help text at `OmniCommands.java:56-65` is a `List<HelpEntry>` that only lists the
canonical names (no aliases displayed). Aliases are invisible to users who don't already
know them.

## Cloud integration

Setup at `OmniCommands.java:76-80`:

```java
PaperCommandManager<CommandSourceStack> mgr = PaperCommandManager.builder()
    .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
    .buildOnEnable(plugin);
```

`buildOnEnable(plugin)` hooks into the Paper plugin lifecycle so Cloud registers its
Brigadier nodes during plugin enable. The execution coordinator is `simpleCoordinator`
— synchronous, no threading policy (handlers run on the main thread when Cloud decides
to; any async work is the handler's problem, not Cloud's).

`CommandSourceStack` is Paper's Brigadier source type. In older Paper versions, `/foo`
command senders were `CommandSender` directly; with Brigadier integration, sender is
now wrapped so `getExecutor()` can be an `Entity`/`null`, and `getSender()` is the
direct invoker (console or player). `OmniCommands.sender(...)` at
`OmniCommands.java:150-157` unwraps this:

```java
CommandSourceStack stack = ctx.sender();
Entity executor = stack.getExecutor();
if (executor instanceof CommandSender cs) return cs;
return stack.getSender();
```

Prefers `executor` (the entity who is *effectively* running the command, e.g. when
`/execute as` proxies it) but falls back to the direct sender. Every handler's first
line calls `sender(ctx)` to get the same old `CommandSender` the rest of the plugin
knows how to deal with.

### Tab completion

Cloud's Brigadier-native suggestions flow. The `search-parameters` / `rollback` /
`restore` arguments use a shared provider, defined at
`OmniCommands.java:67-74`:

```java
private static final SuggestionProvider<CommandSourceStack> SEARCH_SUGGESTIONS =
    SuggestionProvider.blocking((ctx, input) -> {
        String remaining = input.remainingInput();
        String lastToken = lastToken(remaining);
        return SearchParameterHelper.suggestParameterCompletion(lastToken).stream()
            .map(Suggestion::suggestion)
            .collect(Collectors.toList());
    });
```

`input.remainingInput()` returns the untyped portion of the greedy string. `lastToken`
grabs everything after the last space (`OmniCommands.java:159-163`). That single token
is fed into `SearchParameterHelper.suggestParameterCompletion`, which branches
(`SearchParameterHelper.java:13-69`):

- `partial` starts with `-` and contains `=`: user has typed a flag and is filling in
  its argument. Dispatch to `flagHandler.suggestCompletionOptions(partialArg)` and
  re-prefix results.
- `partial` starts with `-` only: user is typing a flag name. Return every registered
  `FlagHandler` alias with the `-` prefix (plus `=` if the flag requires an argument).
- `partial` contains `:`: user has typed a parameter and is filling in its argument.
  Dispatch to `parameterHandler.suggestTabCompletion(partialArg)` and re-prefix.
- Everything else: return the union of every flag alias and every parameter alias.

So Cloud asks for suggestions on the greedy string, the helper re-parses the tail,
dispatches into the right handler, and returns strings that Cloud wraps back into
`Suggestion` instances. Tab completion for parameters works per-handler because each
`ParameterHandler.suggestTabCompletion(...)` is free to return whatever it wants (player
names, block types, event names, …). See doc 04 for that side.

### What the migration changed

The old command code (pre-this-session) was a tree of `OmniSubCommand` abstract
classes extending `SimpleCommand`, registered via Bukkit's `PluginCommand` with a
manual argument dispatcher and manual tab-completion plumbing. The migration:

- Collapsed all subcommands into literal nodes on one Cloud command builder.
- Delegated tab completion to Cloud via `SuggestionProvider.blocking`.
- Dropped the hand-rolled permission checks inside each handler — Cloud's `.permission`
  handles them before the handler is invoked.
- Moved the per-sender search result state out of command instances and into the
  dedicated `PageStore` static singleton.

Most of the rendering layer below `runSearch` was not touched. `SearchCallback`,
`PageStore`, and the display handlers are the same code they were before the migration.

## The main flow: `/omni search …`

```
/omni search p:Steve b:stone
    │
    ▼
Cloud dispatch → runSearch(sender, "p:Steve b:stone")        OmniCommands.java:177
    │
    ▼
new QuerySession(sender)
    │
    ▼
session.newQueryFromArguments(["p:Steve","b:stone"])  → CompletableFuture<Void>
    │   (parameter handlers add conditions to the query
    │    asynchronously; see doc 04)
    ▼
Async.lookup(session, new SearchCallback(session))           Async.java:16
    │
    ├─ query.setSearchLimit(OmniConfig.lookupSizeLimit)
    ├─ Bukkit.getScheduler().runTaskAsynchronously(...)
    │      └─ Omniscience.getStorageHandler().records().query(session)
    │             → CompletableFuture<List<DataEntry>>
    │
    ▼
SearchCallback.success(results)                              SearchCallback.java:32
    │
    ├─ results.stream().map(this::buildComponent).toList()
    │      └─ buildComponent(entry) → BaseComponent[]        SearchCallback.java:50
    │
    ▼
PageStore.setSearchResults(sender, components)               PageStore.java:20
    │
    ▼
PageStore.showPage(sender, 0)                                PageStore.java:31
```

So there are three async boundaries: the parameter parsing future, the Bukkit async
task wrapping, and the storage driver's own future. The sender's main thread does not
block on any of them; `Async.lookup` fires the query on a Bukkit async thread, and
`SearchCallback.success` dispatches back from whatever thread `records().query`
completes on. Messages to the player eventually land via
`Player.spigot().sendMessage(BaseComponent[])` inside `PageStore.showPage` —
thread-safe from Paper's side.

### `runSearch` exception handling

`OmniCommands.java:181-190`:

```java
try {
    CompletableFuture<Void> future = session.newQueryFromArguments(args);
    future.thenAccept(ignored -> Async.lookup(session, new SearchCallback(session)));
} catch (ParameterException e) {
    sender.sendMessage(Formatter.error(e.getMessage()));
} catch (Exception ex) {
    String message = ex.getMessage() == null ? "An unknown error occurred while running this command. Please check console." : ex.getMessage();
    ex.printStackTrace();
    sender.sendMessage(Formatter.error(message));
}
```

Caveat: `newQueryFromArguments` returns a future *and* can throw synchronously
(`ParameterException`). But any error *inside* the future chain (e.g. a parameter
handler failing async) will be swallowed by the `thenAccept` without a matching
`exceptionally`. The catch block here only catches sync exceptions. Async parsing
failures don't reach the player.

### `Async.lookup` lifecycle

```java
public static void lookup(final QuerySession session, AsyncCallback callback) {
    session.getQuery().setSearchLimit(OmniConfig.INSTANCE.getLookupSizeLimit());
    Bukkit.getScheduler().runTaskAsynchronously(Omniscience.getProvidingPlugin(Omniscience.class), () -> {
        try {
            CompletableFuture<List<DataEntry>> future = Omniscience.getStorageHandler().records().query(session);
            future.thenAccept(results -> {
                try {
                    if (results.isEmpty()) callback.empty();
                    else callback.success(results);
                } catch (Exception e) {
                    session.getSender().sendMessage(Formatter.error(e.getMessage()));
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            callback.error(e);
            e.printStackTrace();
        }
    });
}
```

The Bukkit async task runs on Bukkit's scheduler thread, calls
`records().query(session)` (which internally does its own async work — see doc 02).
The outer task doesn't actually do any I/O itself; it just hands off to the driver
future. The Bukkit scheduler wrap is redundant, practically — we could call
`records().query()` directly from the command thread. It exists because earlier
implementations of the storage driver were blocking, and removing the wrap would
mean auditing every backend.

`setSearchLimit` is applied per-call here using `limits.lookup.size`. The same
pattern applies in `runApplier` at `OmniCommands.java:206` with
`limits.actionables`.

## `SearchCallback.buildComponent` — the big renderer

The 120-line method at `SearchCallback.java:50-166` is where a `DataEntry` becomes a
`BaseComponent[]`. It is straight-line code, not broken up into helpers, and the
control flow depends on two boolean branches (`displayHandler present?` and `entry
instanceof DataEntryComplete?`). Both sides compose three `StringBuilder`s into a
final `ComponentBuilder`.

### Data sources

Before building, it looks up a display handler:

```java
Optional<String> oDHandler = entry.data.getString(DataKeys.DISPLAY_METHOD);
if (oDHandler.isPresent()) {
    displayHandler = Omniscience.getDisplayHandler(oDHandler.get());
    ...
}
```

`DISPLAY_METHOD` is a string key in the entry's `DataWrapper`, set at write time by the
listener that created the event (e.g. a message event sets it to `"message"`, an item
event sets it to `"item"`). Most entries don't set it — those fall back to the default
rendering.

### Line layout

```
= [WE] Steve broke 3 stone 2m ago
  \_\_/ \___/ \___/  \/ \___/ \____/
   │    │     │      │  │     │
   │    │     │      │  │     endOfMessage (time)
   │    │     │      │  target (primary colour, clickable)
   │    │     │      quantity
   │    │     verb past tense
   │    source name (player or cause)
   origin tag (added this session)
```

The prefix literal `= ` is inserted at `SearchCallback.java:117/147`; no part of the
rendering makes it configurable. The origin tag is only present if the entry has a
non-empty `ORIGIN` data key (set by `WorldEditLogger` / FAWE hook):

```java
private String formatOriginTag(String origin) {
    if (origin == null || origin.isEmpty()) return "";
    return ChatColor.AQUA + "[" + switch (origin.toLowerCase()) {
        case "worldedit" -> "WE";
        case "fastasyncworldedit", "fawe" -> "FAWE";
        default -> origin;
    } + "]" + ChatColor.RESET;
}
```

(`SearchCallback.java:168-175`.) This method was added this session alongside the
chat event recipients hover. Any unknown origin value is passed through literally.

### Hover data

Built in parallel to the visible line. Keys that always appear:

- `Source: <entry.getSourceName()>`
- `Event: <entry.getEventName()>`

Keys that conditionally appear:

- `Origin: <origin>` if `DataKeys.ORIGIN` is set
- `Quantity: <qty>` if `DataKeys.QUANTITY` is set
- `Target: <target>` if target is non-empty (colour-stripped for the hover)
- `Count: <count>` when the entry is a `DataAggregateEntry` (groups of repeated events)
- Additional lines from `displayHandler.buildAdditionalHoverData(...)` appended last
- `Time: <formatted time>` only when the entry is a `DataEntryComplete`
- `Location: (x y z world)` only when the location resolves and the entry is complete

The location line is unusual: it's appended as a nested `ComponentBuilder` because
`DataHelper.buildLocation` returns `BaseComponent[]` (it has its own click event). See
`SearchCallback.java:125-131`. That block is also where the `-e` (`EXTENDED`) flag
adds a second, visible "- (x y z world)" row below the main line.

### Click event (detail drill-down)

Only attached for `DataAggregateEntry` / incomplete entries (i.e. grouped search
results). The click re-runs a constructed `/omniscience search` with narrowed
parameters so the player can see the individual events in the group:

```java
private String buildDetailCommand(DataEntry entry) {
    String action = "a:" + entry.getEventName();
    final String source = entry.data.get(DataKeys.PLAYER_ID).isPresent()
        ? "p:" + entry.getSourceName()
        : "c:" + entry.getSourceName();
    String target = "trg:" + entry.getTargetName().replaceAll(" ", ",");
    StringBuilder command = new StringBuilder("/omniscience search ");
    command.append(action).append(" ").append(source).append(" ").append(target).append(" ");
    Optional<ParameterHandler> radius = Omniscience.getParameterHandler("r");
    if (radius.isPresent() && session.isIgnoredDefault(radius.get())) {
        command.append("-g").append(" ");
    } else {
        command.append("r:").append(session.getRadius()).append(" ");
    }
    command.append("-ng");
    return command.toString();
}
```

(`SearchCallback.java:177-203`.) Rebuilds a search by event name, source, target,
radius, and appends `-ng` to ungroup so the player actually sees individual records.
Hard-codes the prefix `/omniscience` — that is, the click won't land if only `/omni` or
`/o` is registered. Since all three aliases are registered, this works, but it's a
dependency that nobody would think to flag.

For `DataEntryComplete` (single, non-aggregate) entries, there is no click event on the
main component — only the `target` sub-component potentially gets one from a display
handler's `buildTargetSpecificHoverData` (currently unused by the registered handlers).
The teleport link is instead attached to the *location* inside the hover, via
`DataHelper.buildLocation(location, true)`.

## Page rendering: `PageStore`

Single-file, 57 lines, fully static (`PageStore.java:13-56`). State is a single
`ConcurrentHashMap<CommandSender, List<BaseComponent[]>>`, holding the last search's
rendered lines per sender.

`setSearchResults(sender, results)` replaces the entry and automatically shows page 0.
`removeSearchResults(sender)` clears. `showPage(sender, pageNum)` prints 14 rows (hard-
coded, even though the header math uses 15 as the page size — that's an off-by-one
that displays "Page 1/3" when there are really 15-per-page's worth of lines, but only
shows 14). From `PageStore.java:37-48`:

```java
if (results.size() < pageNum * 15) {
    sender.sendMessage(Formatter.error("Error: " + (pageNum + 1) + " is not a valid page."));
    return false;
}
sender.sendMessage(Formatter.getPageHeader((pageNum + 1), (int) Math.round(Math.ceil(results.size() / 15D))));
for (int i = pageNum * 15; i < (pageNum * 15) + 14; i++) {
    if (i >= results.size()) break;
    ...
}
```

Notice the `< pageNum * 15` guard is also off: a page with fewer than `pageNum * 15`
results (i.e., exactly one short of a multiple of 15) should still be valid if
`pageNum >= 1`, but the code rejects it. In practice, players rarely notice because
most searches return either the 1000-record cap or a small handful.

`setSearchResults` is called by `SearchCallback.success` only. `removeSearchResults` is
called on `empty()` and on `error()`. There's no TTL; entries live forever in the map
until the same sender runs another successful search.

## Rollback/restore: `runApplier`

`/omni rollback <params>` and `/omni restore <params>` both route to
`runApplier(sender, paramString, sort)` (`OmniCommands.java:193-271`). Logic:

1. Build `QuerySession`, force `Flag.NO_GROUP` so grouping doesn't blur the
   per-record rollback.
2. Kick parsing via `newQueryFromArguments`, set sort order *after* parsing.
3. Once parsing completes, run `query(session)` directly (bypasses `Async.lookup` —
   this path uses `actionablesLimit` instead of `lookupSizeLimit`).
4. For each result that implements `Actionable`, call `.rollback()` or `.restore()`
   depending on sort direction. Collect `ActionResult`s in an in-memory list.
5. Report `x reversals. y skipped` to the sender. For skips, dump skip reasons.
6. For player senders, stash results in `Omniscience.addLastActionResults(uuid, list)`
   for `/omni undo`.

Worth noting: `sort == NEWEST_FIRST` means rollback, `sort == OLDEST_FIRST` means
restore. The mapping is implicit; there's no enum `Intent.ROLLBACK` vs
`Intent.RESTORE` separation, only which direction we iterate. If the sort gets set
elsewhere by a flag handler the semantics break silently.

## Undo: `runUndo`

`/omni undo` pulls the last list of `ActionResult`s for a player, reverses it, and
walks each transaction (`OmniCommands.java:273-333`). The implementation is
copy-pasted duck-typing: for each result, check if the transaction is a
`LocationTransaction` with an inventory, or an `originalState` that's a `BlockState`,
or a `finalState` that's an `Entity`, and reverse accordingly. No dispatch through
the `Actionable` interface — the undo logic is divorced from the per-entry rollback
logic that originally created the transactions. A new entry type with a custom
`Actionable` would roll back fine but be unreversible.

Also: `lastActionResults` is in-memory (stored in `OmniCore.lastActionResults`). Server
restart eats it. Mentioned in doc 01 as a known limitation.

## Tool toggle: `runTool`

`/omni tool` (`OmniCommands.java:335-374`) toggles the data wand for a player. State
lives in `OmniCore.activeWandList` (a `Set<UUID>`). The interaction is convoluted —
the same command does at least four things depending on inventory state:

- Wand inactive → activate it, give the player the wand item (creating or hand-
  moving), print "Activated".
- Wand active, item not in inventory → add the item, print "Added to inventory".
- Wand active, item in inventory but not in hand → swap to hand, print "Moved to hand".
- Wand active, item in hand → deactivate, print "Deactivated".

The logic is duplicated across the "has active wand" / "doesn't" branches. A table-
driven or explicit state-machine refactor would halve the LOC.

## Events list: `runEvents`

Two lines (`OmniCommands.java:376-381`):

```java
List<String> enabledEvents = OmniApi.getEnabledEvents();
enabledEvents.sort(String::compareToIgnoreCase);
sender.sendMessage(Formatter.bonus(String.join(", ", enabledEvents)));
```

Pulls the enabled set from `OmniEventRegistrar` (via `OmniApi`), sorts, prints
comma-separated. Useful for players who want to know what actions can actually be
searched on this server.

## Teleport: `runTeleport`

`OmniCommands.java:383-408`:

```java
world = Bukkit.getWorld(UUID.fromString(worldArg));
Location newLocation = new Location(world,
    Double.parseDouble(xArg) + 0.5D,
    Double.parseDouble(yArg) + 0.5D,
    Double.parseDouble(zArg) + 0.5D);
player.teleport(newLocation);
```

Block centre offset (`+0.5D`). No safety checks, no yaw/pitch preservation, no
destination validation. The method isn't responsible for authorising the teleport —
`omniscience.mayuse` gates it at the command level. But see the pain points: the
permission was meant to cover *users of the plugin*, not specifically "can teleport to
arbitrary coordinates."

## The AI handler: `AiHandler`

Stand-alone, 233 lines (`AiHandler.java`), instantiated once per
`OmniCommands.register` call. It is invoked as `/omni ai <question>` and dispatches to
Vertex AI's Gemini `generateContent` endpoint, streams back the text, parses out any
`/omni ...` commands, and renders them as clickable chips in chat.

### Gate checks

Three gates before the request fires (`AiHandler.java:64-91`):

1. `OmniConfig.INSTANCE.isAIEnabled()` — config-level kill switch.
2. API key present and not `your-api-key-here` (the default placeholder).
3. Cooldown: 10 seconds per player UUID, tracked in a `ConcurrentHashMap<UUID, Long>`.
   Console is exempt.

If all pass, sends "Thinking..." and kicks the HTTP call async.

### Prompt loading

`AiHandler.java:47-58`:

```java
File promptFile = new File(plugin.getDataFolder(), "ai-prompt.txt");
if (!promptFile.exists()) plugin.saveResource("ai-prompt.txt", false);
systemPrompt = Files.readString(promptFile.toPath(), StandardCharsets.UTF_8);
```

The system prompt lives in `ai-prompt.txt` (shipped as a default resource). Read once
at construction, cached in a field. `reloadPrompt()` exists but nothing calls it — no
hot reload command yet.

### HTTP call

`queryVertexAI(question)` (`AiHandler.java:121-193`) builds a Vertex AI
`generateContent` URL with the API key in the query string (not a header), POSTs a
JSON body with a `system_instruction` and `contents` array, reads the response line
by line, and parses out `candidates[0].content.parts[0].text`.

- URL: `https://aiplatform.googleapis.com/v1/publishers/google/models/{model}:generateContent?key={key}`
- Connect timeout 30s, read timeout 60s
- Raw `HttpURLConnection` with `OutputStream`/`BufferedReader` plumbing
- Gson for JSON shape

Non-200 responses dump the error stream into the exception message. The entire 60s
HTTP call blocks the async Bukkit task's thread; a backed-up server could stack up AI
requests on the async scheduler.

### Threading

`AiHandler.java:96-118`:

```java
new BukkitRunnable() {
    public void run() {
        try {
            String response = queryVertexAI(question);
            new BukkitRunnable() {
                public void run() {
                    sender.sendMessage(Formatter.formatSecondaryMessage("--- AI Response ---"));
                    sendFormattedResponse(sender, response);
                }
            }.runTask(plugin);
        } catch (Exception e) {
            new BukkitRunnable() {
                public void run() {
                    sender.sendMessage(Formatter.error("AI request failed: " + e.getMessage()));
                }
            }.runTask(plugin);
            e.printStackTrace();
        }
    }
}.runTaskAsynchronously(plugin);
```

Outer `BukkitRunnable` on the async scheduler does the HTTP call. On success, an inner
`BukkitRunnable` on the sync scheduler renders to the player. On failure, another
inner `BukkitRunnable` on the sync scheduler reports the error. Three anonymous
runnables stacked. It works, but it's noise: virtual threads + direct
`player.sendMessage` (Paper's chat is thread-safe) would be two lines.

### Response rendering

`sendFormattedResponse` (`AiHandler.java:195-232`) does three things:

1. Strip markdown code fences and `**bold**` / `*italic*` markers.
2. Split on newlines, iterate each non-empty line.
3. For each line, run it through `COMMAND_PATTERN` (`AiHandler.java:36`):

   ```java
   private static final Pattern COMMAND_PATTERN =
       Pattern.compile("`(/omni[^`]+)`|(/omni\\s+\\S+(?:\\s+\\S+)*)");
   ```

4. If the line contains a backticked or bare `/omni ...` command, split it into
   prefix / command / suffix, render the command as a bold aqua clickable
   `TextComponent` with `SUGGEST_COMMAND` click (which puts it in the player's chat
   input), and send the three components to the player via
   `player.spigot().sendMessage(prefix, cmdComponent, suffixComponent)`.
5. Otherwise, render as a plain gray chat line.

The `SUGGEST_COMMAND` action means clicking doesn't execute the command — it fills the
chat box with it so the player can edit. That's a deliberate UX choice: the AI might
suggest something slightly wrong, and the player can review before hitting enter.

Non-player senders (console) just get the text.

## Display handlers

Extension point that lets an event's entry mutate specific parts of the default
renderer's output. Sits behind `DataKeys.DISPLAY_METHOD` — a string stored on the
`DataEntry` at write time — matched via `DisplayHandler.handles(displayTag)` at read
time.

### Interface (`DisplayHandler.java`)

Four methods (one default):

```java
boolean handles(String displayTag);

Optional<String> buildTargetMessage(DataEntry entry, String target, QuerySession session);

Optional<List<String>> buildAdditionalHoverData(DataEntry entry, QuerySession session);

default Optional<TextComponent> buildTargetSpecificHoverData(DataEntry entry, String target, QuerySession session) {
    return Optional.empty();
}
```

The renderer calls each in turn from inside `SearchCallback.buildComponent`:

- `buildTargetMessage` (`SearchCallback.java:82-84`) — replace the target text. Used to
  change "stone" → "iron sword into chest" for item-transaction events.
- `buildAdditionalHoverData` (`SearchCallback.java:101-107`) — append lines to the
  hover tooltip. The "Recipients: …" line added this session for messages goes here.
- `buildTargetSpecificHoverData` (`SearchCallback.java:89-92`, 135-137, 151-153) —
  replace the *target sub-component* entirely, including its own hover/click. Used by
  `ItemDisplayHandler` to optionally swap in a custom-named component.

### `SimpleDisplayHandler`

Abstract base at `SimpleDisplayHandler.java:1-15`. Sixteen lines. Provides a `String
displayTag` field, a constructor that takes it, and a `handles(String)` that
case-insensitively equals-compares. Everything concrete extends this.

### Registered handlers (four)

Set up in `OmniCore.registerDisplayHandlers` (`OmniCore.java:176-180`):

```java
displayHandlerList.add(new MessageDisplayHandler());
displayHandlerList.add(new ItemDisplayHandler());
displayHandlerList.add(new DamageDisplayHandler());
displayHandlerList.add(new TeleportDisplayHandler());
```

Dispatched by `OmniCore.getDisplayHandler(key)` at `OmniCore.java:192-194` which
streams the list and returns the first `handles(key)` match. No validation that tags
are unique; first-wins. Since there are only four and their tags don't overlap, this
doesn't bite today.

#### `MessageDisplayHandler` (tag: `"message"`)

`MessageDisplayHandler.java:18-69`. Used by chat events (`say`, `private-message`,
…).

- `buildTargetMessage`: if grouping is disabled (`Flag.NO_GROUP`) and the entry has a
  `MESSAGE` key, return the message as the target text. Otherwise return empty
  (default rendering uses the "Message" generic target). This is what makes `/omni s
  a:say -ng` show the actual chat lines.
- `buildAdditionalHoverData`: modified this session to add a `Recipients: …` line if
  `DataKeys.RECIPIENT` is set. Splits the comma-separated UUID list, resolves each via
  `Bukkit.getOfflinePlayer(uuid).getName()`, caps at `MAX_NAMES = 12` resolved names,
  and appends "… +N more" if more exist. Falls back to the raw UUID string if the
  player has no cached name or the token isn't a valid UUID.

The offline-player resolution is a local lookup against the user cache — no mojang
API round-trip — so it's cheap. But an entry with 50 recipients (a server-wide
broadcast) will loop 50 times even though only 12 are rendered; the trim could happen
earlier.

#### `ItemDisplayHandler` (tag: `"item"`)

`ItemDisplayHandler.java:16-65`. Used by container-transaction events
(`chest-deposit`, `chest-withdraw`, `drop`, `pickup`, …).

- `buildTargetMessage`: append " into X" / " from X" where X is the entity (chest
  type, mob type) the item was transferred through. Direction depends on whether the
  event name contains `"withdraw"`.
- `buildAdditionalHoverData`: append `Name: …`, `Enchants: …`, and `Lore:` followed
  by each lore line (each prefixed with `  ` and `DARK_PURPLE`). These come from
  `DataKeys.NAME`, `DataKeys.ITEM_ENCHANTS`, `DataKeys.ITEM_LORE` respectively.
- `buildTargetSpecificHoverData`: if `NAME` is set and differs from the raw target,
  wrap the custom name in a `TextComponent`. Explicitly does *not* attempt to
  deserialize the original `ItemStack` — a comment at `ItemDisplayHandler.java:58-59`
  blames 1.20.5+ component format changes:

  ```java
  // Just return the custom name as a TextComponent - don't try to deserialize ItemStack
  // (ItemStack deserialization is broken in 1.20.5+ due to component format changes)
  ```

  The bungee `Item` hover content (`net.md_5.bungee.api.chat.hover.content.Item`)
  existed for exactly this — render an item hover with NBT tooltip — but it relies on
  server-side serialization formats that broke with the 1.20.5 component shift. This
  is direct evidence that the bungee-chat layer is stale.

#### `DamageDisplayHandler` (tag: `"damage"`)

`DamageDisplayHandler.java:12-30`. Used by player/entity damage events.

- `buildTargetMessage`: empty (use default target).
- `buildAdditionalHoverData`: append `Damage Cause: …` and `Damage Amount: …`
  from `DataKeys.DAMAGE_CAUSE` / `DataKeys.DAMAGE_AMOUNT`.

#### `TeleportDisplayHandler` (tag: `"teleport"`)

`TeleportDisplayHandler.java:12-29`. Used by `teleport` events.

- `buildTargetMessage`: empty.
- `buildAdditionalHoverData`: append `Teleport Cause: …` from
  `DataKeys.TELEPORT_CAUSE`.

### How many entries use display handlers?

Across the ~40+ registered event types, only the ones whose listeners explicitly set
`DataKeys.DISPLAY_METHOD` use a handler. By my search:

- Chat listeners set `"message"`.
- Container / drop / pickup / item-frame / inventory-move listeners set `"item"`.
- Damage listeners set `"damage"`.
- Teleport listener sets `"teleport"`.

Most block and entity events don't set it and fall through to the default renderer.
That means the "customize the line" extension point is used by a minority of events —
roughly a third.

## Result helpers (`CommandResult`, `UseResult`)

Two enums/classes under `command/result/`. Both are partially-plumbed vestiges of the
old command dispatch. `CommandResult.java:1-39` is a `{Status, reason}` pair with
`success()` / `failure(reason)` factories. `UseResult.java:1-10` is an enum with five
values (`SUCCESS`, `NO_COMMAND_SENDER`, `NO_PLAYER_SENDER`, `NO_PERMISSION`,
`OTHER_ERROR`).

Neither is used after the Cloud migration. Greps return zero call sites in the
current code. They predate the migration and should be deleted.

## AsyncCallback interface

Three methods: `success(List<DataEntry>)`, `empty()`, `error(Exception)`
(`AsyncCallback.java:7-14`). Only one implementation: `SearchCallback`. The
interface exists because older versions of the plugin had a separate `LookupCallback`
for rollback/restore; that second implementation was inlined into `runApplier`
during some earlier refactor, leaving the interface with a single consumer. Could
become a sealed interface with a single permitted subtype, or just disappear.

## Pain points

### Bungee chat API everywhere

Every component-producing site is on `net.md_5.bungee.api.chat.*`:

- `SearchCallback.buildComponent` builds `TextComponent` / `ComponentBuilder` /
  `HoverEvent` / `ClickEvent`
- `PageStore.showPage` sends via `Player.spigot().sendMessage(BaseComponent[])`
- `AiHandler.sendFormattedResponse` constructs `TextComponent` with
  `setColor(net.md_5.bungee.api.ChatColor.AQUA)` and sends via `player.spigot()`
- `DataHelper.buildLocation` returns `BaseComponent[]`
- `Formatter` is string-based but uses `org.bukkit.ChatColor` which is *also* legacy
- Display handlers return `Optional<TextComponent>` and pass `ChatColor` string
  concatenations in hover data

Paper has been Adventure-native since 1.16.5. Every one of those sites is a chore to
migrate: `TextComponent` → `Component.text()`, `ComponentBuilder` → the Adventure
builder API, `HoverEvent.SHOW_TEXT` → `HoverEvent.showText(Component)`,
`ClickEvent.RUN_COMMAND` → `ClickEvent.runCommand(...)`, and so on. The display
handler interface would need to change return types. This is the single biggest rip
in the v2 rewrite.

### `Formatter` leaks `org.bukkit.ChatColor`

`Formatter.java:1-47` imports `org.bukkit.ChatColor.*` statically and returns raw
`String` with embedded colour codes. That's a *third* API surface (after bungee
chat and Adventure). Every call site expects `String` but a clean Adventure pipeline
would want `Component`. Porting `Formatter` is an entry point for the whole chain.

### `SearchCallback.buildComponent` is one straight method

120 lines of straight-line code. Hard to unit-test (no injection of anything — it
reads from `entry.data`, calls `displayHandler.build…` directly, dispatches to
`DataHelper.buildLocation` which constructs its own bungee components). Hard to
extend: the `DisplayHandler` interface only lets a handler TWEAK parts (target text,
hover additions, target sub-component) — it can't restructure the line itself, can't
change the prefix, can't reorder, can't add a new click event to the "main" text.

The sensible refactor is a pipeline of composable renderers:

```
EntryRenderPipeline
  .prefix(OriginTagRenderer)         // [WE], [FAWE]
  .source(SourceRenderer)
  .verb(VerbRenderer)
  .quantity(QuantityRenderer)
  .target(TargetRenderer)            // default or display-handler-driven
  .suffix(TimeSuffixRenderer)
  .location(LocationRenderer)
  .hover(HoverBuilder
      .staticRows(SourceHover, EventHover, OriginHover, QuantityHover, TargetHover)
      .aggregateCount()
      .displayHandlerExtensions())
  .click(DetailCommandClick.orNone())
```

Each piece is a pure function from `DataEntry` + `QuerySession` to
`Component`/`Optional<Component>`. The full pipeline is the composition. Easy to
unit-test, easy to override, easy to add a new stage.

### Nested `BukkitRunnable`s in `AiHandler`

Three anonymous inner `BukkitRunnable` subclasses to coordinate async HTTP + sync
chat response + sync error reporting. The whole thing is a callback hell pattern from
when Java had no good alternative. Paper 1.21 is on JDK 21 (per the toolchain in doc
00). Virtual threads + `Thread.ofVirtual().start(...)` + direct `sender.sendMessage`
(Paper's Adventure-native message path *is* thread-safe; bungee's isn't quite but
works in practice on 1.21) would replace this with ~10 lines of flat code.

### `PageStore` leaks memory

The `ConcurrentHashMap<CommandSender, List<BaseComponent[]>>` retains entries
indefinitely. Entries are only removed when the same sender runs another search that
returns zero results (`empty()` → `removeSearchResults`) or errors out (`error(e)` →
`removeSearchResults`) or runs a *successful* search (which overwrites). If a player
logs out, their entry stays. If the same player rejoins with a new session, the map
still holds the stale `CommandSender` and the new one. Each entry holds up to
`limits.lookup.size` (default 1000) `BaseComponent[]` arrays — each of which is a
complete rendered line with hover tooltips — so the memory footprint per stuck entry
is non-trivial.

Mitigations that should exist but don't:

- Listen for `PlayerQuitEvent` and `PageStore.removeSearchResults(player)`.
- TTL entries with a max idle time (15 min since last access).
- Limit total entries (LRU).

### `/omnitele` is exposed

```
/omnitele <worldUuid> <x> <y> <z>
```

Anyone with `omniscience.mayuse` (i.e., anyone who can use Omniscience) can teleport
to arbitrary coordinates. The permission was meant to mean "can run Omniscience
things," not "free-fly teleport." The command is only *supposed* to be invoked via
the click event in a search hover. There is no binding between "I clicked a search
result hover" and "I'm running /omnitele" — they're functionally identical from the
server's perspective.

Options for v2:

- Tokenise: emit the click target as `/omnitele <ephemeralToken>` where the token is
  a random UUID stored in a short-lived map keyed to (player, world, x, y, z).
  Clicking the link resolves the token; entering the command manually gets "no such
  token."
- Permission node `omniscience.commands.teleport` distinct from `.mayuse`, and only
  grant it to users who already have free-fly (admins/staff). This doesn't fix the
  vector but makes the footprint explicit.
- Move the click handler inside Paper's packet listener (intercept the click event)
  rather than running a server command. More work, but eliminates the attack surface.

### Hard-coded aliases

`registerWithAliases` takes a `List<String>` literal at every call site
(`OmniCommands.java:90, 95, 100, 105, 110, 114, 118, 122`). Want to rename `page` to
`p2` because `p` conflicts with your economy plugin? Rebuild the plugin. Cloud
supports programmatic config-driven registration but the current code doesn't wire it
up.

### The Cloud migration didn't replace anything below the command handler

The migration swapped the *dispatch* layer but not the *response* layer. `PageStore`,
`SearchCallback`, `AiHandler`, `DataHelper.buildLocation`, every display handler,
`Formatter` — all of them still look the same as pre-migration. The migration is a
thin veneer at the top; the legacy Spigot world lives underneath.

### `OmniCore.getDisplayHandler` is O(n)

Five registered handlers, four in use, so this doesn't matter in practice. But
`OmniCore.java:192-194` streams the list and `filter`s on every call. In
`SearchCallback.buildComponent` (called once per record, up to 1000 records per
search), we spend 4,000 filter evaluations per search. A `Map<String, DisplayHandler>`
keyed by tag would be O(1). Again, not urgent, but trivial to fix.

### `AsyncCallback` has one implementation

`SearchCallback` is the only user. The `error()` / `empty()` / `success()` trio is
fine as a shape, but if there's only one implementation, inline it or replace with
lambdas. The interface was the old sealed approach to the Mongo-future world when
`CompletableFuture` wasn't ergonomic in the codebase.

### `CommandResult` and `UseResult` are dead

Zero call sites in the post-migration code. Delete both files.

### `ItemDisplayHandler`'s disabled item-hover is a regression

The original `Item` hover (NBT tooltip on the item-target component) would render the
full item tooltip including durability, CustomModelData, enchantments-as-icons, etc.
The 1.20.5 component format shift broke the bungee `Item.setTag(ItemTag)` path and
the fix (re-implement against `Item` component NBT) was deferred, so the handler
degrades to a plain text component with the custom name. Adventure's `HoverEvent.ShowItem`
works in 1.21 but we aren't on Adventure here.

## Modernization hotspots

1. **Adventure everywhere.** Migrate every rendered component to
   `net.kyori.adventure.text.Component` + `TextComponent.Builder`. `HoverEvent.showText`,
   `HoverEvent.showItem`, `ClickEvent.runCommand/suggestCommand`. Kill all uses of
   `net.md_5.bungee.api.chat.*` and `net.md_5.bungee.api.ChatColor`. The display
   handler interface changes with this — `Optional<TextComponent>` becomes
   `Optional<Component>`, `Optional<List<String>>` becomes `Optional<List<Component>>`.

2. **MiniMessage templates for copy.** Turn every `Formatter.error("foo")` and every
   hard-coded `ChatColor.DARK_GRAY + "Source: "` concatenation into a MiniMessage
   template loaded from `messages.yml`. Paper's `MiniMessage.miniMessage().deserialize(
   "<gray>Source: <white><source></white></gray>", placeholders)` produces a
   `Component`. Consolidates copy, unblocks i18n, removes the `Formatter` third-API-
   surface problem.

3. **Split `SearchCallback.buildComponent` into a composable pipeline.** See the
   sketch in the pain points. Each stage is a `Function<RenderContext, Optional<Component>>`
   registered via a `RendererRegistry`. Unit-testable, extensible, diff-friendly.

4. **Virtual threads for `AiHandler`'s HTTP call.** Replace the double-nested
   `BukkitRunnable` with `Thread.ofVirtual().name("omni-ai-" + playerId).start(() ->
   …)`. Inside, do the HTTP call, then dispatch the player message via Adventure
   (thread-safe). No scheduler hop needed on the response path.

5. **`PageStore` with a bounded, TTL'd cache.** `Caffeine.newBuilder()
   .expireAfterAccess(Duration.ofMinutes(15)).maximumSize(1000).build()`. Listen for
   `PlayerQuitEvent` and invalidate. No more unbounded growth.

6. **Nonce-based `/omnitele`.** Emit click targets as `/omnitele <token>` where
   `token` is a short-lived UUID keyed to the real coordinates in a side map. Manual
   invocation gets a "no such token" message. Alternatively drop the command entirely
   and use Paper's incoming-packet listener to intercept clicks.

7. **Cloud annotation-based command discovery.** Cloud 2.x supports
   `@CommandMethod` / `@CommandPermission` / `@Argument` annotations via
   `AnnotationParser`. New subcommands become new annotated methods; no need to touch
   `OmniCommands.register`. Aliases can be declared alongside: `@CommandMethod("omni|o|
   omniscience search|s|sc|lookup|l <params>")`.

8. **Configurable aliases.** Optional config block:
   ```yaml
   commands:
     search: [search, s, sc, lookup, l]
     page: [page, p, pg]
     ...
   ```
   Plumbing is straightforward; only sensible once i18n is in because admins localising
   their server will want native-language command names too.

9. **O(1) display handler dispatch.** Build a `Map<String, DisplayHandler>` in
   `registerDisplayHandler`; look up by exact tag. Also catch duplicate registrations
   and log a warning.

10. **Delete dead code.** `CommandResult`, `UseResult`, `AsyncCallback` (after
    inlining `SearchCallback` usage).

11. **Make the renderer testable.** Wrap the entry → components transformation in a
    `EntryRenderer` object that takes a `DataEntry` and a `RenderContext` (session,
    display handler registry, location builder, formatter) and returns `Component`.
    Then write tests: "source-less entry renders without source name," "aggregate
    entry includes count in prefix," "extended flag emits location row," etc.

12. **Kill `PageStore` statics; inject.** Replace with a `PageService` injected into
    `SearchCallback` and the page subcommand handler. Scope is plugin-wide so still
    singleton-ish, but no longer a file of static methods.

## What v2 should keep

- **The display handler extension point.** It's a reasonable shape — a tag-based
  dispatch that lets event types customize their rendering. Adventure equivalents of
  `buildTargetMessage`, `buildAdditionalHoverData`, `buildTargetSpecificHoverData`
  would continue to work.

- **The async `Async.lookup → AsyncCallback` pattern**, just with
  `CompletableFuture<List<DataEntry>>` exposed directly instead of wrapped behind a
  callback interface. The underlying "off-thread query, back to main for render" flow
  is correct.

- **Page-based result display.** Paginated scrollable chat is the right UX for this
  tool. Keep it.

- **The detail-click drill-down** (`buildDetailCommand`) is a good UX — click a grouped
  row to see the constituent events. Worth preserving, just move the command
  construction into a dedicated `ClickCommandBuilder` that's testable and that handles
  the `omniscience` vs `omni` vs `o` alias choice without hard-coding.

- **AI help.** The `/omni ai` flow is genuinely useful. Tidy the threading, port the
  renderer to Adventure, make the system prompt hot-reloadable, and keep the chip-
  clickable-command pattern.
