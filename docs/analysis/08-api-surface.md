# v1 Dissection — 08: API Surface

Files covered:

- **The "public" API** (`v1API/src/main/java/net/medievalrp/v1/api/`): `sg.java`, `interfaces/Iv1.java`, `interfaces/WorldEditHandler.java`.
- **Supporting types shipped in the API jar** (not re-dissected here; see other docs): `data/DataKey`, `data/DataKeys`, `data/DataWrapper`, `entry/OEntry`, `entry/Actionable`, `parameter/ParameterHandler`, `flag/FlagHandler`, `display/DisplayHandler`.
- **Internal counterpart on the plugin class** (`the v1 core/.../v1.java`): the bag of static delegates that actually drives internal code.
- **Consumer reference**: `whispernet/src/main/java/net/medievalrp/whispernet/bridges/SpyglassBridge.java` — the only known external plugin that compiles against the API.

## Responsibility

This is the contract between v1 and everything else. Two audiences use it:

1. **External plugins** that want to log custom events, register extra parameters/flags/display handlers, or inspect v1's registries. WhisperNet is the canonical example. These consumers compile against the `v1API` module and look at `net.medievalrp.v1.api.*` as their surface.
2. **the v1 core itself** — listeners, commands, storage, display. Core is the implementor of the contract (`sg implements Iv1`, `sg.setCore(this)`) but also a *consumer* of it via `sg.isEventRegistered`, `sg.getEventPastTense`, `sg.getEventClass`, etc. Paradoxically Core also bypasses `sg` constantly and goes straight to the plugin class statics.

The two audiences share a single flat namespace under `net.medievalrp.v1.api.*`. There is no @ApiStatus annotation, no versioning marker, no Javadoc telling a consumer what is stable, what is internal, or what is dead on arrival. The implicit contract is "whatever compiles, compiles."

## Classes and interfaces

### The static accessor: `sg`

`sg.java:16` — a class with one static field and 26 static methods. Pure façade over a held `Iv1`. Diagrammatically:

```
sg (public, final-ish)
 ├── static Iv1 v1 (private, set once)
 ├── setCore(Iv1) throws IllegalAccessException
 ├── getv1() → Iv1
 └── 24 delegates that forward to `v1.foo(...)`
```

Every method on `sg` is a one-liner passthrough except `getEnabledEvents()` (`sg.java:79`), which filters the registry by `.isEnabled()`, and `getEventPastTense()` (`sg.java:103`), which does a small null-safe containsKey/get. The façade adds no behavior; it exists so external plugins can `sg.registerEvent(...)` without importing `Iv1` or knowing about `sg`.

### The core interface: `Iv1`

`Iv1.java:13` — a 19-method interface (18 after `registerWorldEditHandler` is kicked). Implemented by `sg`. Every `sg.foo()` routes to a method here.

| Method | Concern |
|---|---|
| `getEventClass(String)` | Events — reverse lookup of rollback-capable `DataEntry` subclass |
| `getEvents()` | Events — full registry (name → past-tense + enabled) |
| `registerEvent(String, String)` | Events — register name + past tense |
| `getParameters()` | Parameters — list of handlers |
| `getParameterHandler(String)` | Parameters — by alias |
| `registerParameterHandler(ParameterHandler)` | Parameters — add |
| `getFlagHandler(String)` | Flags — by alias |
| `registerFlagHandler(FlagHandler)` | Flags — add |
| `areDefaultsEnabled()` | Config — config-gate |
| `getDefaultTime()` | Config — default search horizon string (e.g. `3d`) |
| `getDefaultRadius()` | Config — default radius |
| `getMaxRadius()` | Config — upper bound |
| `getDateFormat()` / `getSimpleDateFormat()` | Config — display-layer format |
| `info/warning/severe(String)` | Logging — thin wrappers over the plugin logger |
| `log(Level, String, Throwable)` | Logging — another wrapper |
| `registerWorldEditHandler(WorldEditHandler)` | WE — stored, never read |

This is a grab-bag. No grouping, no sub-interfaces. A consumer that wants to "log a custom event" touches `registerEvent`, `getEventClass`, and nothing else — but the IDE surfaces all 19 methods. An implementor has to provide all 19 or the whole plugin doesn't work.

### The dead corner: `WorldEditHandler`

`WorldEditHandler.java:3` — two-method interface (`enableWorldEditLogging()` / `disableWorldEditLogging()`). `sg` stores a registered handler in a field (`sg.java:38`, `sg.java:322-324`) and never calls either method. There is no `getWorldEditHandler()` accessor. There is no path from any other code in the repo into either interface method. The entire concept is inert.

Grep trail:

```
v1API/.../interfaces/Iv1.java:49: void registerWorldEditHandler(WorldEditHandler handler);
v1API/.../sg.java:114: public static void registerWorldEditHandler(WorldEditHandler handler) {
the v1 core/.../sg.java:38: private WorldEditHandler worldEditHandler;
the v1 core/.../sg.java:322: public void registerWorldEditHandler(WorldEditHandler handler) {
the v1 core/.../sg.java:323: this.worldEditHandler = handler;
```

That's it. No reader of `worldEditHandler`. Dead API.

### The ambient "API" types in the `api` package (covered elsewhere, noted here for scope)

These live under `net.medievalrp.v1.api.*` in the v1API jar and count as part of the contract whether the author intended it or not:

- `data/DataKey` — dotted-path type used everywhere in the wrapper and in queries.
- `data/DataKeys` — the canonical 40-ish constants. Includes `DataKeys.ORIGIN` (added this session) — a consumer can `OEntry.with(DataKeys.ORIGIN, "whatever")` and that string lands in storage with no validation.
- `data/DataWrapper` — god object; consumers handle it directly when using `OEntry.customWithLocation` (WhisperNet does exactly this, `SpyglassBridge.java:50-62`).
- `entry/OEntry` — the builder. External entry point for logging any event. The only thing WhisperNet actually uses from the API that isn't a data key.
- `entry/DataEntry` / `DataEntryComplete` / `DataAggregateEntry` — query-result types. Consumers inspecting search results would see these.
- `entry/Actionable` / `ActionResult` / `ActionableException` / `SkipReason` — rollback contract.
- `parameter/ParameterHandler` / `BaseParameterHandler` — contract for registering custom search parameters.
- `flag/FlagHandler` / `BaseFlagHandler` / `Flag` — same for flags.
- `display/DisplayHandler` / `SimpleDisplayHandler` — and for display customizations.
- `query/Query` / `QuerySession` / `QueryBuilder` / `FieldCondition` / `MatchRule` / `SearchCondition` / `SearchConditionGroup` — query algebra. External plugins could construct a `QuerySession` to drive a lookup, though no consumer currently does.
- `util/DataHelper`, `DateUtil`, `Formatter`, `TypeUtil`, `InventoryUtil`, `PastTenseWithEnabled`, `reflection/ReflectionHandler` — helpers. `PastTenseWithEnabled` is load-bearing (returned from `getEvents()`); the rest are implementation detail that happen to ride along in the jar.

## What an external plugin CAN do through the API

A consumer plugin with `v1API` on its classpath has, in practice, these entry points:

### Register a custom event name

```java
sg.registerEvent("chat-whisper", "whispered");
```

Calls `Iv1.registerEvent(String, String)` → `sg.addEvent(name, pastTense, enabled=true)`. Adds to Table A in the event-registry split (see `03-events-and-entries.md`). The event is immediately enabled — there's no overload to register as disabled.

This does **not** register a `DataEntry` subclass for the event. The plugin-class static `v1.registerEvent(String, Class<? extends DataEntry>)` (`v1.java:95`) does, but `Iv1` has no such method, and `sg` doesn't expose it. So an external plugin can register a custom event's name and past-tense, but every entry read back for that event name falls through to `DataEntryComplete` and can't be rolled back.

### Register a custom `ParameterHandler`

```java
sg.registerParameterHandler(new MyCustomParameter());
```

Adds to `sg.parameterHandlerList` (after an alias-conflict check, `sg.java:247-254`). Your parameter's aliases become valid in the `/sg search` DSL.

### Register a custom `FlagHandler`

```java
sg.registerFlagHandler(new MyFlag());
```

Same pattern. No conflict check here — first-match-wins on flag alias.

### Register a custom `DisplayHandler`

This is the one register call that `sg` doesn't expose. `Iv1` has no `registerDisplayHandler` method. The only way to add one is the plugin-class static:

```java
v1.registerDisplayHandler(new MyDisplayHandler());
```

A consumer plugin can reach `net.medievalrp.v1.v1` because it ends up on the classpath (the v1 core ships it), but that's the `JavaPlugin` subclass — definitionally internal. Depending on it tightly couples the consumer to Core, not API. This asymmetry (`registerParameterHandler` / `registerFlagHandler` on `sg`; `registerDisplayHandler` only on the plugin class) is a straight-up oversight.

### Build and save an `OEntry`

```java
OEntry.create()
      .source(player)
      .customWithLocation("chat-whisper", wrapper, player.getLocation())
      .save();
```

This is the primary external-facing path. WhisperNet uses exactly this (`SpyglassBridge.java:32`). `OEntry.create()` returns an `EntryBuilder`; `.source(x)` walks an instanceof ladder (`OEntry.java:636`) and returns an `EventBuilder`; `.customWithLocation` or one of 26 other typed verbs on `EventBuilder` emits a stub-populated `DataWrapper` and returns an `OEntry`; `.save()` validates the event name against `sg.isEventRegistered`, stamps `EVENT_NAME` + `CREATED`, derives `PLAYER_ID`/`CAUSE`, and drops onto `EntryQueue`.

The `.with(DataKey, Object)` escape hatch (`OEntry.java:48`) lets a consumer attach arbitrary key/value pairs onto a built entry. This includes the session-added `DataKeys.ORIGIN` — no value validation anywhere, consumer can write any string.

### Look up handlers and events

```java
sg.getParameterHandler("p") → Optional<ParameterHandler>
sg.getFlagHandler("ng") → Optional<FlagHandler>
sg.getParameters() → List<ParameterHandler>
sg.getEvents() → Map<String, PastTenseWithEnabled>
sg.getEnabledEvents() → List<String>
sg.isEventRegistered(name) → boolean
sg.isEventEnabled(name) → boolean
sg.getEventPastTense(name) → String
sg.getEventClass(name) → Optional<Class<? extends DataEntry>>
```

These are all read-only introspection. Adequate for a consumer checking "does v1 know about my event?" before attempting a `.save()`.

### Read defaults

```java
sg.areDefaultsEnabled() → boolean
sg.getDefaultRadius() → int
sg.getDefaultTime() → String (like "3d")
sg.getRadiusLimit() → int
sg.getDateFormat() / getSimpleDateFormat() → String
```

No reason an external plugin would *want* the date-format strings, but they're exposed.

### Log through v1's logger

```java
sg.info("hello")
sg.warning("careful")
sg.severe("bad")
sg.log(Level.FINE, "stuff", ex)
```

Each method routes through `Iv1.info/warning/severe/log` which routes to `v1.getPluginInstance().getLogger().foo(...)` (`sg.java:267-284`). Entirely redundant with `Bukkit.getLogger()` or the consumer's own `plugin.getLogger()`. Makes v1's log prefix the reporter for messages about the consumer, which is wrong on its face — a WhisperNet warning should say `[WhisperNet]`, not `[v1]`.

### Register a `WorldEditHandler` (dead path)

```java
sg.registerWorldEditHandler(handler);
```

Handler is stored. Nothing calls it. This is on the API surface, which makes it discoverable; a consumer seeing it in autocomplete would reasonably assume it works.

## What the API does NOT expose (but Core reaches for anyway)

The plugin class `v1` (`v1.java:20`) holds two statics — `INSTANCE` (the `sg`) and `PLUGIN_INSTANCE` (itself) — and a pile of delegates. Around half the delegates parallel `Iv1`. The other half are Core-only and have no API counterpart:

| `v1` static | What it does | Why Core needs it | Why API doesn't have it |
|---|---|---|---|
| `getStorageHandler()` | Returns the `StorageHandler` | `EntryQueueRunner` submits batches to `records().write(...)`; `MongoRecordHandler.query` is reached via this | Consumers shouldn't hit storage directly |
| `getDataEntryClass(String)` | Delegate to `getEventClass` | `DataEntry.from` uses it reflectively | Already on API as `sg.getEventClass` — this is a duplicate |
| `getDisplayHandler(String)` | Look up display handler by tag | `SearchCallback.buildComponent` | `sg` has no `getDisplayHandler` — inconsistency |
| `registerDisplayHandler(DisplayHandler)` | Add a display handler | `sg.registerDisplayHandlers` | Not on `Iv1`; external plugins can't register displays via API |
| `getFlagHandlers()` | Full flag list | Used by `SearchParameterHelper` tab-complete | `sg` has no list accessor for flags |
| `getEvents()` (returns `ImmutableSet<String>` — different from `sg.getEvents()` which returns `Map<String, PastTenseWithEnabled>`) | | `SpyglassCommands` | Two methods with the same name return different shapes on the two façades |
| `hasActiveWand(Player)`, `wandActivateFor(Player)`, `wandDeactivateFor(Player)` | Wand state machine | `WandInteractListener`, `SpyglassCommands.runTool` | Private to the plugin; never meant for consumers |
| `onWorldEditStatusChange(boolean)` | Toggle FlagWorldEditSel on/off | `PluginInteractionListener`, `sg` itself | Internal |
| `addLastActionResults(UUID, List<ActionResult>)`, `getLastActionResults(UUID)` | Per-player rollback history | `SpyglassCommands.runApplier`, `runUndo` | Internal |
| `logDebug(String)` | Gated-on-config log | Core-side diagnostics | Would be a sensible API addition; isn't |
| `getPluginInstance()` / `getInstance()` | Hand out the `JavaPlugin` or `Iv1` | Everywhere | `sg.getv1()` returns `Iv1`; the plugin-class route also hands out the raw `JavaPlugin` |
| `registerEvent(String, Class<? extends DataEntry>)` | Event name → rollback class | Only called from `sg.registerEventWrapperClasses` | Not exposed — Table B is closed to consumers |

Half of this is straight "Core needs internal state." Half could be on the API (`logDebug`, `getDisplayHandler`, `registerDisplayHandler`, `getFlagHandlers`, the `Class`-typed `registerEvent`) and just isn't. Which set is "API" and which is "internal" was never decided — classes fell onto one side or the other based on who happened to need them when.

## The two-shell design

There are two parallel static façades:

```
net.medievalrp.v1.api.sg ← 26 static methods, delegates to Iv1
net.medievalrp.v1.v1 ← 25 static methods, delegates to sg

Both point at the same sg instance.
  sg.setCore(sg) // called once from sg.onEnable (sg.java:46)
  v1.INSTANCE = sg // set in v1.onEnable (v1.java:143)
```

The intent seems to be: external plugins use `sg`, internal code uses `v1` (or `v1.getPluginInstance()`). In practice that boundary is leaky from both sides:

- `OEntry.save()` (API-module code) calls `sg.isEventRegistered` at `OEntry.java:54` — API-to-API, fine.
- `OEntry.save()` via `EntryQueue.submit` calls `sg.isEventRegistered` again at `EntryQueue.java:24` — redundant but fine.
- `DataEntry.from` (API-module code) calls `sg.getEventClass` at `DataEntry.java:20` — fine.
- `DataEntry.translateToPastTense` calls `sg.getEventPastTense` at `DataEntry.java:44` — fine.
- `sg` (Core-module code that is the implementation) also imports and calls `sg.setCore` at `sg.java:46` — the implementation calls its own façade to register itself. Self-referential bootstrap.
- Every Core listener and command bypasses `sg` entirely and uses `v1.getStorageHandler()`, `v1.getPluginInstance()`, `v1.hasActiveWand(...)`, etc. `sg.java:268-283` implements `Iv1.info/warning/severe/log` by reaching `v1.getPluginInstance().getLogger()` — another example of Core code going *through* the plugin class statics.

Net effect: `sg` is the badge we show external plugins, but internally almost nothing touches it. Search the v1 core for `sg.` and you find one hit (the `setCore` call). Search for `v1.` and you find 60+ usages across 20 files. The "API" is cosmetic.

### `sg.setCore` and "half-encapsulation"

`sg.java:20`:

```java
public static void setCore(Iv1 sg) throws IllegalAccessException {
    if (v1 != null) {
        throw new IllegalAccessException("v1's instance cannot be replaced.");
    }
    v1 = sg;
}
```

Visibility: `public`. Throws a checked exception (`IllegalAccessException`) to signal "already set." Anyone on the classpath can call `sg.setCore(myEvilImpl)` once at startup before `sg` gets to it. The `if (v1 != null)` guard is the only protection, and it's first-come-first-served. In a `/reload` scenario where `sg` onEnable tries to re-register itself, `setCore` throws and Core logs `SEVERE` + disables itself (`sg.java:46-50`). A bespoke `AlreadyRegisteredException` or `IllegalStateException` would express the intent better than hijacking `IllegalAccessException` (which in the Java-reflection world means something specific).

### `OEntry.save()` calls `sg` for validation — fine, but vacuous

`OEntry.save()` at line 54:

```java
if (!sg.isEventRegistered(eventBuilder.getEventName())) {
    throw new IllegalArgumentException(...);
}
```

Then `EntryQueue.submit` at line 24 does the same check. Two validations of the same condition on the same path. Not a correctness problem — just redundant.

## API/impl boundary leaks (inherited from the overview)

The v1API jar declares interfaces and base classes at `net.medievalrp.v1.api.*`. The the v1 core module *also* puts concrete implementation classes at `net.medievalrp.v1.api.*` — same package, different module. When Core shades the API jar into its final output these coexist at runtime; from a classpath perspective the distinction vanishes.

Examples (confirmed by file listing — see `03-events-and-entries.md` and `04-query-dsl.md`):

- `the v1 core/src/main/java/net/medievalrp/v1/api/entry/BlockEntry.java`
- `the v1 core/src/main/java/net/medievalrp/v1/api/entry/ContainerEntry.java`
- `the v1 core/src/main/java/net/medievalrp/v1/api/entry/EntityEntry.java`
- `the v1 core/src/main/java/net/medievalrp/v1/api/entry/EntryQueueRunner.java`
- `the v1 core/src/main/java/net/medievalrp/v1/api/parameter/*` — 14 parameter impls
- `the v1 core/src/main/java/net/medievalrp/v1/api/flag/*` — 8 flag impls plus `FlagWorldEditSel`

A consumer plugin that shades only `v1API` gets the interfaces and the six API-module parameters/flags (`WorldParameter`, `RecipientParameter`, `ServerParameter`, plus the base classes), but not the Core-module ones. Their IDE's package browser, however, shows `net.medievalrp.v1.api.parameter` as one namespace in the jar — confusion is inevitable.

This matters for two reasons. First, consumer expectations: "Block events use `BlockEntry`, so let me reference that class." — won't compile against the API. Second, future API publishing: if v1 ever publishes `v1API-0.1.jar` to a real Maven repo, the classes listed above would be missing from it, breaking anything that tried to depend on them. The package layout actively lies about where classes come from.

## `OEntry` is in the API — good, except…

`OEntry` is the right thing to expose. It's the entry-point builder for writing logs. WhisperNet only compiles because `OEntry` is on the API module. Good.

The problem: `OEntry` is *also* the builder Core uses internally. Every one of the 27 typed verbs (`brokeBlock`, `placedBlock`, `said`, ...) sits on `OEntry.EventBuilder` at `OEntry.java:103`. Those verbs are Core-shaped; consumers rarely want them. A consumer writing a chat-whisper plugin doesn't need `brokeBlock`; they need `customWithLocation`. But their IDE shows all 27 verbs as valid method completions, plus the `.with(DataKey, Object)` escape hatch, plus the `SourceBuilder`/`EventBuilder`/`PlayerEventBuilder` nested types. The consumer's world is bigger than it needs to be because the internal builder and the external builder are the same class.

Corollary: any internal change to `OEntry` is an API change. Adding a new typed verb for a new 1.22-era event (a hypothetical `spongeAbsorbed(...)` for sponge absorption) is both a Core feature *and* an API release. There is no fence.

## `DataKeys.ORIGIN` — unbounded public key

`DataKeys.ORIGIN = DataKey.of("Origin")` at `DataKeys.java:52`. Added this session for WE/FAWE origin tagging. `OEntry.with(DataKeys.ORIGIN, "worldedit")` and `OEntry.with(DataKeys.ORIGIN, "fawe")` are the two current internal writes.

Nothing prevents a consumer from writing `DataKeys.ORIGIN, "purple-monkey-dishwasher"`. The display layer (`SearchCallback.formatOriginTag`) has a `switch` with three arms: `"worldedit"` → `[WE]`, `"fastasyncworldedit"`/`"fawe"` → `[FAWE]`, anything else → pass-through literal. So a consumer-set origin shows up in chat as `[purple-monkey-dishwasher]`. The contract is "whatever you set goes."

Is that fine? Informally yes — origin is a loose tag, not a query axis (yet). But there's no documented set of valid values and no allowlist enforcement. If v1 ever adds `-origin=worldedit` as a search flag, the possible values space becomes part of the API by accident.

## Pain points

1. **`Iv1` is a ~19-method grab-bag.** No separation between consumer-facing and internal methods. `registerWorldEditHandler` (dead), `info/warning/severe/log` (wrappers around the plugin logger), `getSimpleDateFormat()` (display-layer implementation detail), and `registerParameterHandler` (genuinely for consumers) are all on the same interface. A consumer reading this file has no signal for what's stable vs experimental vs plumbing.

2. **`sg.setCore` is `public` but gated by a checked `IllegalAccessException`.** The visibility says "anyone can call this." The exception says "but don't." Either make it package-private (which closes it off from consumers *and* from any future Core split) or throw a runtime exception. The checked exception forces `sg.onEnable` into a try/catch that logs SEVERE and disables the plugin on a condition that should be a `preconditions` fast-fail.

3. **`v1` (the plugin class) is the ACTUAL go-to for internal code.** Static-delegate count: 25 on `v1`, 26 on `sg`. Internal callers use `v1.getStorageHandler()` / `v1.getPluginInstance()` / etc. The `sg` abstraction is largely decorative — grep finds exactly one Core-side call (`sg.java:46` to `setCore`). If `sg` disappeared tomorrow, only `OEntry`, `EntryQueue`, and `DataEntry` would stop compiling (because they reach `sg` for event-name validation and past-tense lookup).

4. **`OEntry` is dual-purposed.** It's both the consumer-facing builder and the internal builder. Internal-specific verbs pollute the consumer API; consumer-specific changes couple to internal code. The `.with(DataKey, Object)` backdoor admits that the typed verbs aren't actually sufficient and lets any field be set from outside — which means any "private" field is effectively public once it has a `DataKey` constant.

5. **`DataKeys.ORIGIN` has no value contract.** Any consumer can set any string. There is no enum, no documented set of valid values, no validator. The display switch treats unknown values as literal — acceptable today, a compatibility surface tomorrow.

6. **`Iv1.log(Level, String, Throwable)` is over-abstracted.** `sg.log(...)` delegates to `v1.getPluginInstance().getLogger().log(...)`. Every consumer could call their own `plugin.getLogger()` directly. The wrapper pretends v1 owns the logging concern for its consumers; it doesn't.

7. **No versioning on the API surface.** The `v1API` jar has a version (currently `V0.1-Alpha`, in the root Gradle), but consumers shade it into their own jars — they don't declare a runtime dependency on a particular version. Breaking changes to the API class files will manifest as `NoSuchMethodError` at plugin-enable time with no clean upgrade story. There is no compat shim, no @Deprecated rung on the ladder, no @ApiStatus.Stable marker saying "this method stays."

8. **No stability markers anywhere.** Everything is implicitly "figure it out." The WhisperNet consumer uses `DataWrapper.set` (`SpyglassBridge.java:51`), `DataKeys.TARGET`, `DataKeys.DISPLAY_METHOD`, `DataKeys.MESSAGE`, `DataKeys.RECIPIENT`, `OEntry.create().source(...).customWithLocation(...).save()`. Every one of these is load-bearing without annotation. If `DataWrapper.set` ever validates inputs more aggressively or if `customWithLocation` picks up a new required argument, WhisperNet breaks silently at runtime.

9. **Asymmetric register methods.** `sg` exposes `registerEvent`, `registerParameterHandler`, `registerFlagHandler`, `registerWorldEditHandler` (dead). No `registerDisplayHandler`. To register a custom display a consumer must reach `v1.registerDisplayHandler(...)` on the plugin class — at which point they're depending on Core, not API. Consumers that want to ship a jar against a published API can't register displays without shading more than they should.

10. **`sg.registerEvent(String, String)` doesn't register a rollback class.** Consumers can name an event and supply a past tense, but they can't say "events named `chat-whisper` should use `my.Plugin.WhisperEntry` for rollback." That second table is Core-only (`sg.registerEvent(String, Class<? extends DataEntry>)` is package-private, its statics-counterpart on `v1` is `public` but not on `sg`). Consumer-added events can't be rolled back.

11. **Logger delegates misattribute.** `sg.warning("WhisperNet message lost")` appears in console with v1's prefix, not WhisperNet's. Technically correct — it's called through v1 — but semantically wrong.

12. **`sg.getRadiusLimit` is spelled `getMaxRadius` on `Iv1`.** `sg.java:71` / `Iv1.java:45`. Minor, but they should match — the string "max radius" vs "radius limit" is the same concept at two names.

13. **`getv1()` exposes the implementation handle.** `sg.getv1()` returns `Iv1` — a consumer can get the raw implementation and call any method on it directly, bypassing whatever method stability the façade might imply. Works fine today, undermines any future stability guarantees.

14. **The API module has no `package-info.java`.** No module-info, no package doc, no overview. A consumer cloning `v1API` has only class-level Javadoc to read, and most classes have none.

## Modernization hotspots

### 1. Split `Iv1`

Two narrower interfaces:

```java
// External-plugin surface. Stable. Published.
public interface v1Api {
    // Registration
    void registerEvent(String name, String pastTense);
    void registerEvent(String name, String pastTense, Class<? extends DataEntry> rollbackClass);
    void registerParameterHandler(ParameterHandler handler);
    void registerFlagHandler(FlagHandler handler);
    void registerDisplayHandler(DisplayHandler handler);

    // Query
    OEntry newEntry(); // factory, replaces static OEntry.create()
    CompletableFuture<List<DataEntry>> query(QuerySession session);

    // Introspection
    boolean isEventRegistered(String name);
    boolean isEventEnabled(String name);
    String getEventPastTense(String name);
    Set<String> getEnabledEvents();
    Optional<ParameterHandler> findParameter(String alias);
    Optional<FlagHandler> findFlag(String alias);
    int getDefaultRadius();
    int getMaxRadius();
    String getDefaultTime();
}

// Internal-only surface. Package-private or in a separate `internal` package.
public interface v1Context extends v1Api {
    StorageHandler storage();
    sg config();
    DisplayRegistry displays();
    Map<UUID, List<ActionResult>> actionHistory();
    boolean hasActiveWand(Player p);
    void wandActivate(Player p);
    void wandDeactivate(Player p);
    void onWorldEditStatusChange(boolean enabled);
}
```

External plugins see only `v1Api`. Core uses `v1Context` for its internal reach. The plugin class's 25 static delegates collapse into "inject the context." The API surface shrinks to what external plugins actually use.

### 2. Lose the static singleton

Replace `sg.setCore(...)` and `v1.INSTANCE` with Bukkit's service registry:

```java
// Core, during onEnable:
Bukkit.getServicesManager().register(
    v1Api.class, this, v1, ServicePriority.Normal);

// Consumer, during onEnable:
RegisteredServiceProvider<v1Api> provider =
    Bukkit.getServicesManager().getRegistration(v1Api.class);
if (provider != null) {
    this.sg = provider.getProvider();
}
```

No statics to set, no `IllegalAccessException`, no bootstrap ordering concern other than the standard Bukkit soft-depend. Consumers that want the API outside Bukkit (tests, rendering scripts) get a `ServiceLoader` fallback that constructs a fake impl.

### 3. Properly isolate the API jar

Move concrete impl classes out of `net.medievalrp.v1.api.*`:

- `BlockEntry` / `ContainerEntry` / `EntityEntry` → `net.medievalrp.v1.core.entry.*`
- `EntryQueueRunner` → `net.medievalrp.v1.core.runtime.*`
- `EventParameter`, `PlayerParameter`, etc. → `net.medievalrp.v1.core.parameter.*`
- `FlagGlobal`, `FlagNoChat`, etc. → `net.medievalrp.v1.core.flag.*`
- The `Parameter*` implementations in the *API* module that Core doesn't need (`WorldParameter`, `RecipientParameter`, `ServerParameter`) — debatable; they feel like Core code that happens to live in the API for historical reasons.

After this, the API jar's `net.medievalrp.v1.api.*` contains exactly the interfaces, data types, and contract classes. Publishing it as a real Maven artifact becomes possible.

### 4. Add `@ApiStatus` annotations

JetBrains annotations (`org.jetbrains.annotations.ApiStatus`) are a standard Minecraft-ecosystem choice:

```java
@ApiStatus.Stable
public interface v1Api { ... }

@ApiStatus.Experimental
public void registerCustomEventWithRollback(...);

@ApiStatus.Internal
public interface v1Context { ... }

@ApiStatus.ScheduledForRemoval(inVersion = "1.0")
@Deprecated
public interface WorldEditHandler { ... }
```

Consumers' IDEs surface these as warnings. CI can enforce no-experimental-in-release.

### 5. Narrow `OEntry`

Extract an `OEntry.External` with the fluent surface consumers want (`create()`, `.source()`, `.custom()`, `.customWithLocation()`, `.with()`) and an `OEntry.Internal` with every typed verb (`brokeBlock`, `placedBlock`, etc.). Internal code uses Internal; consumers see External.

Better: collapse `EventBuilder`'s 27 typed verbs into listener-side extractors (see the sealed-type rewrite in `03-events-and-entries.md`) so `OEntry` is just the factory + `.with()` + `.save()`. Consumers never see the verbs because the verbs live with the event types.

### 6. Deprecate `WorldEditHandler`

No callers. Either remove the interface and the `registerWorldEditHandler` plumbing or wire it to the current `onWorldEditStatusChange` path. Shipped code shouldn't have dead API.

### 7. Fix `registerDisplayHandler` asymmetry

If `registerParameterHandler` and `registerFlagHandler` are on the API, so should `registerDisplayHandler`. One-line fix: add to `Iv1`, add to `sg`, add to `sg`. Should have been there from day one.

### 8. Give `DataKeys.ORIGIN` a contract

Replace the raw string with an `Origin` enum (or sealed class with a `Custom(String)` case for extensibility):

```java
public sealed interface Origin permits Origin.Bukkit, Origin.WorldEdit, Origin.Fawe, Origin.Custom {
    final class Bukkit implements Origin { }
    final class WorldEdit implements Origin { }
    final class Fawe implements Origin { }
    record Custom(String tag) implements Origin { }
}
```

`OEntry.with(DataKeys.ORIGIN, Origin origin)` — typed. Unknown values become `Custom(tag)` explicitly rather than an arbitrary string in the field.

### 9. Publish a real Maven artifact

`net.medievalrp:v1-api:0.1-alpha` on a public repo (or at minimum a GitHub Packages reg). Consumers declare a normal `compileOnly` dependency instead of shading the jar. Version mismatches become compile-time issues instead of runtime `NoSuchMethodError`.

### 10. Semver-style API versioning

With published artifacts, semver becomes meaningful: `0.x` → "move fast, may break"; `1.0` → "stable, backward-compatible within major." Breaking changes bump major; deprecations land in the previous major with a two-release runway. Every non-trivial API addition since this session's work (the `DataKeys.ORIGIN` constant, the `OEntry.with(...)` method, the `DisplayHandler.buildAdditionalHoverData` expansions) would be a minor bump on a properly versioned artifact.

### 11. Drop the logging delegates from the API

`sg.info/warning/severe/log` serve no purpose. Consumers should use their own `plugin.getLogger()`. Remove `Iv1.info/warning/severe/log` and the four `sg` static wrappers. Keep `v1`'s logger for internal Core use.

### 12. Replace `sg.setCore` checked exception

```java
static void setCore(v1Api impl) {
    if (INSTANCE != null) throw new IllegalStateException("Already set");
    INSTANCE = impl;
}
```

Package-private, unchecked exception, no try/catch required in the caller. If step 2 (service registry) lands, `setCore` disappears entirely.

### 13. Add `package-info.java` + Javadoc

At minimum:

- `net/medievalrp/v1/api/package-info.java` — overview, stability note, version reference.
- Every interface method in the consumer-facing surface gets a Javadoc paragraph.
- A `docs/consumer-guide.md` adjacent to these analysis docs covering the three common patterns (log an event, register a parameter, query historical data).

## What v2 should keep

- **The static accessor pattern is fine as sugar, just not as architecture.** `sg.isEventRegistered(...)` is ergonomic. Keep a static helper class as a thin shim over a service-locator-based `v1Api` instance. The difference is that the instance is authoritative and the statics are convenience, not the other way around.
- **`OEntry` as the external entry-point for logging.** The builder shape is good. Consumers want `create().source().verb().save()` and that's exactly what's there. Narrow the surface, but the concept lands.
- **`DataKeys` as a central registry of canonical keys.** Having one place to see every field v1 writes is a nice readability property and worth preserving. Make the keys typed (see `02-data-and-storage.md`) but keep the single-file roster.
- **Parameter / Flag / Display handler interfaces.** These are genuinely extensible and external plugins can legitimately supply their own. The interfaces are small and well-scoped. Keep them; only move impls out of the `api.*` package.
- **The event-name string as wire-format identity.** External plugins register by name; storage keys by name; search queries filter by name. One stable string. Don't replace with integer IDs or enum ordinals — the existing string registry is the lowest-coupling way to let consumers add event names and have them work across the whole stack.
- **Consumer-friendly `OEntry.customWithLocation`.** WhisperNet uses this and nothing else. It's the "I have a location and I want to log an event with arbitrary fields" escape hatch. Keep it; make the name fancier (`logCustomEvent`?) if you like, but the shape is correct.
