# Omniscience Dissection — 08: API Surface

Files covered:

- **The "public" API** (`OmniscienceAPI/src/main/java/net/medievalrp/omniscience/api/`): `OmniApi.java`, `interfaces/IOmniscience.java`, `interfaces/WorldEditHandler.java`.
- **Supporting types shipped in the API jar** (not re-dissected here; see other docs): `data/DataKey`, `data/DataKeys`, `data/DataWrapper`, `entry/OEntry`, `entry/Actionable`, `parameter/ParameterHandler`, `flag/FlagHandler`, `display/DisplayHandler`.
- **Internal counterpart on the plugin class** (`Omniscience-Core/.../Omniscience.java`): the bag of static delegates that actually drives internal code.
- **Consumer reference**: `whispernet/src/main/java/net/medievalrp/whispernet/bridges/OmniBridge.java` — the only known external plugin that compiles against the API.

## Responsibility

This is the contract between Omniscience and everything else. Two audiences use it:

1. **External plugins** that want to log custom events, register extra parameters/flags/display handlers, or inspect Omniscience's registries. WhisperNet is the canonical example. These consumers compile against the `OmniscienceAPI` module and look at `net.medievalrp.omniscience.api.*` as their surface.
2. **Omniscience-Core itself** — listeners, commands, storage, display. Core is the implementor of the contract (`OmniCore implements IOmniscience`, `OmniApi.setCore(this)`) but also a *consumer* of it via `OmniApi.isEventRegistered`, `OmniApi.getEventPastTense`, `OmniApi.getEventClass`, etc. Paradoxically Core also bypasses `OmniApi` constantly and goes straight to the plugin class statics.

The two audiences share a single flat namespace under `net.medievalrp.omniscience.api.*`. There is no @ApiStatus annotation, no versioning marker, no Javadoc telling a consumer what is stable, what is internal, or what is dead on arrival. The implicit contract is "whatever compiles, compiles."

## Classes and interfaces

### The static accessor: `OmniApi`

`OmniApi.java:16` — a class with one static field and 26 static methods. Pure façade over a held `IOmniscience`. Diagrammatically:

```
OmniApi (public, final-ish)
 ├── static IOmniscience omniscience              (private, set once)
 ├── setCore(IOmniscience) throws IllegalAccessException
 ├── getOmniscience() → IOmniscience
 └── 24 delegates that forward to `omniscience.foo(...)`
```

Every method on `OmniApi` is a one-liner passthrough except `getEnabledEvents()` (`OmniApi.java:79`), which filters the registry by `.isEnabled()`, and `getEventPastTense()` (`OmniApi.java:103`), which does a small null-safe containsKey/get. The façade adds no behavior; it exists so external plugins can `OmniApi.registerEvent(...)` without importing `IOmniscience` or knowing about `OmniCore`.

### The core interface: `IOmniscience`

`IOmniscience.java:13` — a 19-method interface (18 after `registerWorldEditHandler` is kicked). Implemented by `OmniCore`. Every `OmniApi.foo()` routes to a method here.

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

`WorldEditHandler.java:3` — two-method interface (`enableWorldEditLogging()` / `disableWorldEditLogging()`). `OmniCore` stores a registered handler in a field (`OmniCore.java:38`, `OmniCore.java:322-324`) and never calls either method. There is no `getWorldEditHandler()` accessor. There is no path from any other code in the repo into either interface method. The entire concept is inert.

Grep trail:

```
OmniscienceAPI/.../interfaces/IOmniscience.java:49:    void registerWorldEditHandler(WorldEditHandler handler);
OmniscienceAPI/.../OmniApi.java:114:    public static void registerWorldEditHandler(WorldEditHandler handler) {
Omniscience-Core/.../OmniCore.java:38:    private WorldEditHandler worldEditHandler;
Omniscience-Core/.../OmniCore.java:322:    public void registerWorldEditHandler(WorldEditHandler handler) {
Omniscience-Core/.../OmniCore.java:323:        this.worldEditHandler = handler;
```

That's it. No reader of `worldEditHandler`. Dead API.

### The ambient "API" types in the `api` package (covered elsewhere, noted here for scope)

These live under `net.medievalrp.omniscience.api.*` in the OmniscienceAPI jar and count as part of the contract whether the author intended it or not:

- `data/DataKey` — dotted-path type used everywhere in the wrapper and in queries.
- `data/DataKeys` — the canonical 40-ish constants. Includes `DataKeys.ORIGIN` (added this session) — a consumer can `OEntry.with(DataKeys.ORIGIN, "whatever")` and that string lands in storage with no validation.
- `data/DataWrapper` — god object; consumers handle it directly when using `OEntry.customWithLocation` (WhisperNet does exactly this, `OmniBridge.java:50-62`).
- `entry/OEntry` — the builder. External entry point for logging any event. The only thing WhisperNet actually uses from the API that isn't a data key.
- `entry/DataEntry` / `DataEntryComplete` / `DataAggregateEntry` — query-result types. Consumers inspecting search results would see these.
- `entry/Actionable` / `ActionResult` / `ActionableException` / `SkipReason` — rollback contract.
- `parameter/ParameterHandler` / `BaseParameterHandler` — contract for registering custom search parameters.
- `flag/FlagHandler` / `BaseFlagHandler` / `Flag` — same for flags.
- `display/DisplayHandler` / `SimpleDisplayHandler` — and for display customizations.
- `query/Query` / `QuerySession` / `QueryBuilder` / `FieldCondition` / `MatchRule` / `SearchCondition` / `SearchConditionGroup` — query algebra. External plugins could construct a `QuerySession` to drive a lookup, though no consumer currently does.
- `util/DataHelper`, `DateUtil`, `Formatter`, `TypeUtil`, `InventoryUtil`, `PastTenseWithEnabled`, `reflection/ReflectionHandler` — helpers. `PastTenseWithEnabled` is load-bearing (returned from `getEvents()`); the rest are implementation detail that happen to ride along in the jar.

## What an external plugin CAN do through the API

A consumer plugin with `OmniscienceAPI` on its classpath has, in practice, these entry points:

### Register a custom event name

```java
OmniApi.registerEvent("chat-whisper", "whispered");
```

Calls `IOmniscience.registerEvent(String, String)` → `OmniEventRegistrar.addEvent(name, pastTense, enabled=true)`. Adds to Table A in the event-registry split (see `03-events-and-entries.md`). The event is immediately enabled — there's no overload to register as disabled.

This does **not** register a `DataEntry` subclass for the event. The plugin-class static `Omniscience.registerEvent(String, Class<? extends DataEntry>)` (`Omniscience.java:95`) does, but `IOmniscience` has no such method, and `OmniApi` doesn't expose it. So an external plugin can register a custom event's name and past-tense, but every entry read back for that event name falls through to `DataEntryComplete` and can't be rolled back.

### Register a custom `ParameterHandler`

```java
OmniApi.registerParameterHandler(new MyCustomParameter());
```

Adds to `OmniCore.parameterHandlerList` (after an alias-conflict check, `OmniCore.java:247-254`). Your parameter's aliases become valid in the `/omni search` DSL.

### Register a custom `FlagHandler`

```java
OmniApi.registerFlagHandler(new MyFlag());
```

Same pattern. No conflict check here — first-match-wins on flag alias.

### Register a custom `DisplayHandler`

This is the one register call that `OmniApi` doesn't expose. `IOmniscience` has no `registerDisplayHandler` method. The only way to add one is the plugin-class static:

```java
Omniscience.registerDisplayHandler(new MyDisplayHandler());
```

A consumer plugin can reach `net.medievalrp.omniscience.Omniscience` because it ends up on the classpath (Omniscience-Core ships it), but that's the `JavaPlugin` subclass — definitionally internal. Depending on it tightly couples the consumer to Core, not API. This asymmetry (`registerParameterHandler` / `registerFlagHandler` on `OmniApi`; `registerDisplayHandler` only on the plugin class) is a straight-up oversight.

### Build and save an `OEntry`

```java
OEntry.create()
      .source(player)
      .customWithLocation("chat-whisper", wrapper, player.getLocation())
      .save();
```

This is the primary external-facing path. WhisperNet uses exactly this (`OmniBridge.java:32`). `OEntry.create()` returns an `EntryBuilder`; `.source(x)` walks an instanceof ladder (`OEntry.java:636`) and returns an `EventBuilder`; `.customWithLocation` or one of 26 other typed verbs on `EventBuilder` emits a stub-populated `DataWrapper` and returns an `OEntry`; `.save()` validates the event name against `OmniApi.isEventRegistered`, stamps `EVENT_NAME` + `CREATED`, derives `PLAYER_ID`/`CAUSE`, and drops onto `EntryQueue`.

The `.with(DataKey, Object)` escape hatch (`OEntry.java:48`) lets a consumer attach arbitrary key/value pairs onto a built entry. This includes the session-added `DataKeys.ORIGIN` — no value validation anywhere, consumer can write any string.

### Look up handlers and events

```java
OmniApi.getParameterHandler("p")       → Optional<ParameterHandler>
OmniApi.getFlagHandler("ng")           → Optional<FlagHandler>
OmniApi.getParameters()                → List<ParameterHandler>
OmniApi.getEvents()                    → Map<String, PastTenseWithEnabled>
OmniApi.getEnabledEvents()             → List<String>
OmniApi.isEventRegistered(name)        → boolean
OmniApi.isEventEnabled(name)           → boolean
OmniApi.getEventPastTense(name)        → String
OmniApi.getEventClass(name)            → Optional<Class<? extends DataEntry>>
```

These are all read-only introspection. Adequate for a consumer checking "does Omniscience know about my event?" before attempting a `.save()`.

### Read defaults

```java
OmniApi.areDefaultsEnabled()           → boolean
OmniApi.getDefaultRadius()             → int
OmniApi.getDefaultTime()               → String (like "3d")
OmniApi.getRadiusLimit()               → int
OmniApi.getDateFormat() / getSimpleDateFormat() → String
```

No reason an external plugin would *want* the date-format strings, but they're exposed.

### Log through Omniscience's logger

```java
OmniApi.info("hello")
OmniApi.warning("careful")
OmniApi.severe("bad")
OmniApi.log(Level.FINE, "stuff", ex)
```

Each method routes through `IOmniscience.info/warning/severe/log` which routes to `Omniscience.getPluginInstance().getLogger().foo(...)` (`OmniCore.java:267-284`). Entirely redundant with `Bukkit.getLogger()` or the consumer's own `plugin.getLogger()`. Makes Omniscience's log prefix the reporter for messages about the consumer, which is wrong on its face — a WhisperNet warning should say `[WhisperNet]`, not `[Omniscience]`.

### Register a `WorldEditHandler` (dead path)

```java
OmniApi.registerWorldEditHandler(handler);
```

Handler is stored. Nothing calls it. This is on the API surface, which makes it discoverable; a consumer seeing it in autocomplete would reasonably assume it works.

## What the API does NOT expose (but Core reaches for anyway)

The plugin class `Omniscience` (`Omniscience.java:20`) holds two statics — `INSTANCE` (the `OmniCore`) and `PLUGIN_INSTANCE` (itself) — and a pile of delegates. Around half the delegates parallel `IOmniscience`. The other half are Core-only and have no API counterpart:

| `Omniscience` static | What it does | Why Core needs it | Why API doesn't have it |
|---|---|---|---|
| `getStorageHandler()` | Returns the `StorageHandler` | `EntryQueueRunner` submits batches to `records().write(...)`; `MongoRecordHandler.query` is reached via this | Consumers shouldn't hit storage directly |
| `getDataEntryClass(String)` | Delegate to `getEventClass` | `DataEntry.from` uses it reflectively | Already on API as `OmniApi.getEventClass` — this is a duplicate |
| `getDisplayHandler(String)` | Look up display handler by tag | `SearchCallback.buildComponent` | `OmniApi` has no `getDisplayHandler` — inconsistency |
| `registerDisplayHandler(DisplayHandler)` | Add a display handler | `OmniCore.registerDisplayHandlers` | Not on `IOmniscience`; external plugins can't register displays via API |
| `getFlagHandlers()` | Full flag list | Used by `SearchParameterHelper` tab-complete | `OmniApi` has no list accessor for flags |
| `getEvents()` (returns `ImmutableSet<String>` — different from `OmniApi.getEvents()` which returns `Map<String, PastTenseWithEnabled>`) | | `OmniCommands` | Two methods with the same name return different shapes on the two façades |
| `hasActiveWand(Player)`, `wandActivateFor(Player)`, `wandDeactivateFor(Player)` | Wand state machine | `WandInteractListener`, `OmniCommands.runTool` | Private to the plugin; never meant for consumers |
| `onWorldEditStatusChange(boolean)` | Toggle FlagWorldEditSel on/off | `PluginInteractionListener`, `OmniCore` itself | Internal |
| `addLastActionResults(UUID, List<ActionResult>)`, `getLastActionResults(UUID)` | Per-player rollback history | `OmniCommands.runApplier`, `runUndo` | Internal |
| `logDebug(String)` | Gated-on-config log | Core-side diagnostics | Would be a sensible API addition; isn't |
| `getPluginInstance()` / `getInstance()` | Hand out the `JavaPlugin` or `IOmniscience` | Everywhere | `OmniApi.getOmniscience()` returns `IOmniscience`; the plugin-class route also hands out the raw `JavaPlugin` |
| `registerEvent(String, Class<? extends DataEntry>)` | Event name → rollback class | Only called from `OmniCore.registerEventWrapperClasses` | Not exposed — Table B is closed to consumers |

Half of this is straight "Core needs internal state." Half could be on the API (`logDebug`, `getDisplayHandler`, `registerDisplayHandler`, `getFlagHandlers`, the `Class`-typed `registerEvent`) and just isn't. Which set is "API" and which is "internal" was never decided — classes fell onto one side or the other based on who happened to need them when.

## The two-shell design

There are two parallel static façades:

```
net.medievalrp.omniscience.api.OmniApi       ← 26 static methods, delegates to IOmniscience
net.medievalrp.omniscience.Omniscience       ← 25 static methods, delegates to OmniCore

Both point at the same OmniCore instance.
  OmniApi.setCore(omniCore)          // called once from OmniCore.onEnable (OmniCore.java:46)
  Omniscience.INSTANCE = omniCore    // set in Omniscience.onEnable (Omniscience.java:143)
```

The intent seems to be: external plugins use `OmniApi`, internal code uses `Omniscience` (or `Omniscience.getPluginInstance()`). In practice that boundary is leaky from both sides:

- `OEntry.save()` (API-module code) calls `OmniApi.isEventRegistered` at `OEntry.java:54` — API-to-API, fine.
- `OEntry.save()` via `EntryQueue.submit` calls `OmniApi.isEventRegistered` again at `EntryQueue.java:24` — redundant but fine.
- `DataEntry.from` (API-module code) calls `OmniApi.getEventClass` at `DataEntry.java:20` — fine.
- `DataEntry.translateToPastTense` calls `OmniApi.getEventPastTense` at `DataEntry.java:44` — fine.
- `OmniCore` (Core-module code that is the implementation) also imports and calls `OmniApi.setCore` at `OmniCore.java:46` — the implementation calls its own façade to register itself. Self-referential bootstrap.
- Every Core listener and command bypasses `OmniApi` entirely and uses `Omniscience.getStorageHandler()`, `Omniscience.getPluginInstance()`, `Omniscience.hasActiveWand(...)`, etc. `OmniCore.java:268-283` implements `IOmniscience.info/warning/severe/log` by reaching `Omniscience.getPluginInstance().getLogger()` — another example of Core code going *through* the plugin class statics.

Net effect: `OmniApi` is the badge we show external plugins, but internally almost nothing touches it. Search Omniscience-Core for `OmniApi.` and you find one hit (the `setCore` call). Search for `Omniscience.` and you find 60+ usages across 20 files. The "API" is cosmetic.

### `OmniApi.setCore` and "half-encapsulation"

`OmniApi.java:20`:

```java
public static void setCore(IOmniscience omni) throws IllegalAccessException {
    if (omniscience != null) {
        throw new IllegalAccessException("Omniscience's instance cannot be replaced.");
    }
    omniscience = omni;
}
```

Visibility: `public`. Throws a checked exception (`IllegalAccessException`) to signal "already set." Anyone on the classpath can call `OmniApi.setCore(myEvilImpl)` once at startup before `OmniCore` gets to it. The `if (omniscience != null)` guard is the only protection, and it's first-come-first-served. In a `/reload` scenario where `OmniCore` onEnable tries to re-register itself, `setCore` throws and Core logs `SEVERE` + disables itself (`OmniCore.java:46-50`). A bespoke `AlreadyRegisteredException` or `IllegalStateException` would express the intent better than hijacking `IllegalAccessException` (which in the Java-reflection world means something specific).

### `OEntry.save()` calls `OmniApi` for validation — fine, but vacuous

`OEntry.save()` at line 54:

```java
if (!OmniApi.isEventRegistered(eventBuilder.getEventName())) {
    throw new IllegalArgumentException(...);
}
```

Then `EntryQueue.submit` at line 24 does the same check. Two validations of the same condition on the same path. Not a correctness problem — just redundant.

## API/impl boundary leaks (inherited from the overview)

The OmniscienceAPI jar declares interfaces and base classes at `net.medievalrp.omniscience.api.*`. The Omniscience-Core module *also* puts concrete implementation classes at `net.medievalrp.omniscience.api.*` — same package, different module. When Core shades the API jar into its final output these coexist at runtime; from a classpath perspective the distinction vanishes.

Examples (confirmed by file listing — see `03-events-and-entries.md` and `04-query-dsl.md`):

- `Omniscience-Core/src/main/java/net/medievalrp/omniscience/api/entry/BlockEntry.java`
- `Omniscience-Core/src/main/java/net/medievalrp/omniscience/api/entry/ContainerEntry.java`
- `Omniscience-Core/src/main/java/net/medievalrp/omniscience/api/entry/EntityEntry.java`
- `Omniscience-Core/src/main/java/net/medievalrp/omniscience/api/entry/EntryQueueRunner.java`
- `Omniscience-Core/src/main/java/net/medievalrp/omniscience/api/parameter/*` — 14 parameter impls
- `Omniscience-Core/src/main/java/net/medievalrp/omniscience/api/flag/*` — 8 flag impls plus `FlagWorldEditSel`

A consumer plugin that shades only `OmniscienceAPI` gets the interfaces and the six API-module parameters/flags (`WorldParameter`, `RecipientParameter`, `ServerParameter`, plus the base classes), but not the Core-module ones. Their IDE's package browser, however, shows `net.medievalrp.omniscience.api.parameter` as one namespace in the jar — confusion is inevitable.

This matters for two reasons. First, consumer expectations: "Block events use `BlockEntry`, so let me reference that class." — won't compile against the API. Second, future API publishing: if Omniscience ever publishes `OmniscienceAPI-0.1.jar` to a real Maven repo, the classes listed above would be missing from it, breaking anything that tried to depend on them. The package layout actively lies about where classes come from.

## `OEntry` is in the API — good, except…

`OEntry` is the right thing to expose. It's the entry-point builder for writing logs. WhisperNet only compiles because `OEntry` is on the API module. Good.

The problem: `OEntry` is *also* the builder Core uses internally. Every one of the 27 typed verbs (`brokeBlock`, `placedBlock`, `said`, ...) sits on `OEntry.EventBuilder` at `OEntry.java:103`. Those verbs are Core-shaped; consumers rarely want them. A consumer writing a chat-whisper plugin doesn't need `brokeBlock`; they need `customWithLocation`. But their IDE shows all 27 verbs as valid method completions, plus the `.with(DataKey, Object)` escape hatch, plus the `SourceBuilder`/`EventBuilder`/`PlayerEventBuilder` nested types. The consumer's world is bigger than it needs to be because the internal builder and the external builder are the same class.

Corollary: any internal change to `OEntry` is an API change. Adding a new typed verb for a new 1.22-era event (a hypothetical `spongeAbsorbed(...)` for sponge absorption) is both a Core feature *and* an API release. There is no fence.

## `DataKeys.ORIGIN` — unbounded public key

`DataKeys.ORIGIN = DataKey.of("Origin")` at `DataKeys.java:52`. Added this session for WE/FAWE origin tagging. `OEntry.with(DataKeys.ORIGIN, "worldedit")` and `OEntry.with(DataKeys.ORIGIN, "fawe")` are the two current internal writes.

Nothing prevents a consumer from writing `DataKeys.ORIGIN, "purple-monkey-dishwasher"`. The display layer (`SearchCallback.formatOriginTag`) has a `switch` with three arms: `"worldedit"` → `[WE]`, `"fastasyncworldedit"`/`"fawe"` → `[FAWE]`, anything else → pass-through literal. So a consumer-set origin shows up in chat as `[purple-monkey-dishwasher]`. The contract is "whatever you set goes."

Is that fine? Informally yes — origin is a loose tag, not a query axis (yet). But there's no documented set of valid values and no allowlist enforcement. If Omniscience ever adds `-origin=worldedit` as a search flag, the possible values space becomes part of the API by accident.

## Pain points

1. **`IOmniscience` is a ~19-method grab-bag.** No separation between consumer-facing and internal methods. `registerWorldEditHandler` (dead), `info/warning/severe/log` (wrappers around the plugin logger), `getSimpleDateFormat()` (display-layer implementation detail), and `registerParameterHandler` (genuinely for consumers) are all on the same interface. A consumer reading this file has no signal for what's stable vs experimental vs plumbing.

2. **`OmniApi.setCore` is `public` but gated by a checked `IllegalAccessException`.** The visibility says "anyone can call this." The exception says "but don't." Either make it package-private (which closes it off from consumers *and* from any future Core split) or throw a runtime exception. The checked exception forces `OmniCore.onEnable` into a try/catch that logs SEVERE and disables the plugin on a condition that should be a `preconditions` fast-fail.

3. **`Omniscience` (the plugin class) is the ACTUAL go-to for internal code.** Static-delegate count: 25 on `Omniscience`, 26 on `OmniApi`. Internal callers use `Omniscience.getStorageHandler()` / `Omniscience.getPluginInstance()` / etc. The `OmniApi` abstraction is largely decorative — grep finds exactly one Core-side call (`OmniCore.java:46` to `setCore`). If `OmniApi` disappeared tomorrow, only `OEntry`, `EntryQueue`, and `DataEntry` would stop compiling (because they reach `OmniApi` for event-name validation and past-tense lookup).

4. **`OEntry` is dual-purposed.** It's both the consumer-facing builder and the internal builder. Internal-specific verbs pollute the consumer API; consumer-specific changes couple to internal code. The `.with(DataKey, Object)` backdoor admits that the typed verbs aren't actually sufficient and lets any field be set from outside — which means any "private" field is effectively public once it has a `DataKey` constant.

5. **`DataKeys.ORIGIN` has no value contract.** Any consumer can set any string. There is no enum, no documented set of valid values, no validator. The display switch treats unknown values as literal — acceptable today, a compatibility surface tomorrow.

6. **`IOmniscience.log(Level, String, Throwable)` is over-abstracted.** `OmniCore.log(...)` delegates to `Omniscience.getPluginInstance().getLogger().log(...)`. Every consumer could call their own `plugin.getLogger()` directly. The wrapper pretends Omniscience owns the logging concern for its consumers; it doesn't.

7. **No versioning on the API surface.** The `OmniscienceAPI` jar has a version (currently `V0.1-Alpha`, in the root Gradle), but consumers shade it into their own jars — they don't declare a runtime dependency on a particular version. Breaking changes to the API class files will manifest as `NoSuchMethodError` at plugin-enable time with no clean upgrade story. There is no compat shim, no @Deprecated rung on the ladder, no @ApiStatus.Stable marker saying "this method stays."

8. **No stability markers anywhere.** Everything is implicitly "figure it out." The WhisperNet consumer uses `DataWrapper.set` (`OmniBridge.java:51`), `DataKeys.TARGET`, `DataKeys.DISPLAY_METHOD`, `DataKeys.MESSAGE`, `DataKeys.RECIPIENT`, `OEntry.create().source(...).customWithLocation(...).save()`. Every one of these is load-bearing without annotation. If `DataWrapper.set` ever validates inputs more aggressively or if `customWithLocation` picks up a new required argument, WhisperNet breaks silently at runtime.

9. **Asymmetric register methods.** `OmniApi` exposes `registerEvent`, `registerParameterHandler`, `registerFlagHandler`, `registerWorldEditHandler` (dead). No `registerDisplayHandler`. To register a custom display a consumer must reach `Omniscience.registerDisplayHandler(...)` on the plugin class — at which point they're depending on Core, not API. Consumers that want to ship a jar against a published API can't register displays without shading more than they should.

10. **`OmniApi.registerEvent(String, String)` doesn't register a rollback class.** Consumers can name an event and supply a past tense, but they can't say "events named `chat-whisper` should use `my.Plugin.WhisperEntry` for rollback." That second table is Core-only (`OmniCore.registerEvent(String, Class<? extends DataEntry>)` is package-private, its statics-counterpart on `Omniscience` is `public` but not on `OmniApi`). Consumer-added events can't be rolled back.

11. **Logger delegates misattribute.** `OmniApi.warning("WhisperNet message lost")` appears in console with Omniscience's prefix, not WhisperNet's. Technically correct — it's called through Omniscience — but semantically wrong.

12. **`OmniApi.getRadiusLimit` is spelled `getMaxRadius` on `IOmniscience`.** `OmniApi.java:71` / `IOmniscience.java:45`. Minor, but they should match — the string "max radius" vs "radius limit" is the same concept at two names.

13. **`getOmniscience()` exposes the implementation handle.** `OmniApi.getOmniscience()` returns `IOmniscience` — a consumer can get the raw implementation and call any method on it directly, bypassing whatever method stability the façade might imply. Works fine today, undermines any future stability guarantees.

14. **The API module has no `package-info.java`.** No module-info, no package doc, no overview. A consumer cloning `OmniscienceAPI` has only class-level Javadoc to read, and most classes have none.

## Modernization hotspots

### 1. Split `IOmniscience`

Two narrower interfaces:

```java
// External-plugin surface. Stable. Published.
public interface OmniscienceApi {
    // Registration
    void registerEvent(String name, String pastTense);
    void registerEvent(String name, String pastTense, Class<? extends DataEntry> rollbackClass);
    void registerParameterHandler(ParameterHandler handler);
    void registerFlagHandler(FlagHandler handler);
    void registerDisplayHandler(DisplayHandler handler);

    // Query
    OEntry newEntry();     // factory, replaces static OEntry.create()
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
public interface OmniscienceContext extends OmniscienceApi {
    StorageHandler storage();
    OmniConfig config();
    DisplayRegistry displays();
    Map<UUID, List<ActionResult>> actionHistory();
    boolean hasActiveWand(Player p);
    void wandActivate(Player p);
    void wandDeactivate(Player p);
    void onWorldEditStatusChange(boolean enabled);
}
```

External plugins see only `OmniscienceApi`. Core uses `OmniscienceContext` for its internal reach. The plugin class's 25 static delegates collapse into "inject the context." The API surface shrinks to what external plugins actually use.

### 2. Lose the static singleton

Replace `OmniApi.setCore(...)` and `Omniscience.INSTANCE` with Bukkit's service registry:

```java
// Core, during onEnable:
Bukkit.getServicesManager().register(
    OmniscienceApi.class, this, omniscience, ServicePriority.Normal);

// Consumer, during onEnable:
RegisteredServiceProvider<OmniscienceApi> provider =
    Bukkit.getServicesManager().getRegistration(OmniscienceApi.class);
if (provider != null) {
    this.omni = provider.getProvider();
}
```

No statics to set, no `IllegalAccessException`, no bootstrap ordering concern other than the standard Bukkit soft-depend. Consumers that want the API outside Bukkit (tests, rendering scripts) get a `ServiceLoader` fallback that constructs a fake impl.

### 3. Properly isolate the API jar

Move concrete impl classes out of `net.medievalrp.omniscience.api.*`:

- `BlockEntry` / `ContainerEntry` / `EntityEntry` → `net.medievalrp.omniscience.core.entry.*`
- `EntryQueueRunner` → `net.medievalrp.omniscience.core.runtime.*`
- `EventParameter`, `PlayerParameter`, etc. → `net.medievalrp.omniscience.core.parameter.*`
- `FlagGlobal`, `FlagNoChat`, etc. → `net.medievalrp.omniscience.core.flag.*`
- The `Parameter*` implementations in the *API* module that Core doesn't need (`WorldParameter`, `RecipientParameter`, `ServerParameter`) — debatable; they feel like Core code that happens to live in the API for historical reasons.

After this, the API jar's `net.medievalrp.omniscience.api.*` contains exactly the interfaces, data types, and contract classes. Publishing it as a real Maven artifact becomes possible.

### 4. Add `@ApiStatus` annotations

JetBrains annotations (`org.jetbrains.annotations.ApiStatus`) are a standard Minecraft-ecosystem choice:

```java
@ApiStatus.Stable
public interface OmniscienceApi { ... }

@ApiStatus.Experimental
public void registerCustomEventWithRollback(...);

@ApiStatus.Internal
public interface OmniscienceContext { ... }

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

If `registerParameterHandler` and `registerFlagHandler` are on the API, so should `registerDisplayHandler`. One-line fix: add to `IOmniscience`, add to `OmniApi`, add to `OmniCore`. Should have been there from day one.

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

`net.medievalrp:omniscience-api:0.1-alpha` on a public repo (or at minimum a GitHub Packages reg). Consumers declare a normal `compileOnly` dependency instead of shading the jar. Version mismatches become compile-time issues instead of runtime `NoSuchMethodError`.

### 10. Semver-style API versioning

With published artifacts, semver becomes meaningful: `0.x` → "move fast, may break"; `1.0` → "stable, backward-compatible within major." Breaking changes bump major; deprecations land in the previous major with a two-release runway. Every non-trivial API addition since this session's work (the `DataKeys.ORIGIN` constant, the `OEntry.with(...)` method, the `DisplayHandler.buildAdditionalHoverData` expansions) would be a minor bump on a properly versioned artifact.

### 11. Drop the logging delegates from the API

`OmniApi.info/warning/severe/log` serve no purpose. Consumers should use their own `plugin.getLogger()`. Remove `IOmniscience.info/warning/severe/log` and the four `OmniApi` static wrappers. Keep `Omniscience`'s logger for internal Core use.

### 12. Replace `OmniApi.setCore` checked exception

```java
static void setCore(OmniscienceApi impl) {
    if (INSTANCE != null) throw new IllegalStateException("Already set");
    INSTANCE = impl;
}
```

Package-private, unchecked exception, no try/catch required in the caller. If step 2 (service registry) lands, `setCore` disappears entirely.

### 13. Add `package-info.java` + Javadoc

At minimum:

- `net/medievalrp/omniscience/api/package-info.java` — overview, stability note, version reference.
- Every interface method in the consumer-facing surface gets a Javadoc paragraph.
- A `docs/consumer-guide.md` adjacent to these analysis docs covering the three common patterns (log an event, register a parameter, query historical data).

## What v2 should keep

- **The static accessor pattern is fine as sugar, just not as architecture.** `OmniApi.isEventRegistered(...)` is ergonomic. Keep a static helper class as a thin shim over a service-locator-based `OmniscienceApi` instance. The difference is that the instance is authoritative and the statics are convenience, not the other way around.
- **`OEntry` as the external entry-point for logging.** The builder shape is good. Consumers want `create().source().verb().save()` and that's exactly what's there. Narrow the surface, but the concept lands.
- **`DataKeys` as a central registry of canonical keys.** Having one place to see every field Omniscience writes is a nice readability property and worth preserving. Make the keys typed (see `02-data-and-storage.md`) but keep the single-file roster.
- **Parameter / Flag / Display handler interfaces.** These are genuinely extensible and external plugins can legitimately supply their own. The interfaces are small and well-scoped. Keep them; only move impls out of the `api.*` package.
- **The event-name string as wire-format identity.** External plugins register by name; storage keys by name; search queries filter by name. One stable string. Don't replace with integer IDs or enum ordinals — the existing string registry is the lowest-coupling way to let consumers add event names and have them work across the whole stack.
- **Consumer-friendly `OEntry.customWithLocation`.** WhisperNet uses this and nothing else. It's the "I have a location and I want to log an event with arbitrary fields" escape hatch. Keep it; make the name fancier (`logCustomEvent`?) if you like, but the shape is correct.
