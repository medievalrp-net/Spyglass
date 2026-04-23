# Omniscience Dissection — 01: Core Lifecycle

Files covered: `Omniscience.java`, `OmniCore.java`, `OmniEventRegistrar.java`, `OmniConfig.java`, `src/main/resources/plugin.yml`, `src/main/resources/config.yml`.

## Responsibility

Bootstrap the plugin: read config, stand up the storage backend, register event wrappers / parameters / flags / display handlers, wire up Bukkit listeners, register commands, start the async queue drainer. Expose the plugin-wide singleton API that the rest of the code reaches for static lookups.

## Classes

- **`Omniscience`** — `JavaPlugin` subclass. Holds two statics: `INSTANCE` (`OmniCore`) and `PLUGIN_INSTANCE` (`Omniscience` itself). Entire plugin's public surface to the rest of the code reaches through static methods on this class (`Omniscience.getStorageHandler()`, `Omniscience.hasActiveWand()`, etc.). Every static delegates to the corresponding `OmniCore` instance method.
- **`OmniCore`** — the actual plugin state. Implements `IOmniscience`. Holds:
  - `List<ParameterHandler> parameterHandlerList`
  - `Map<String, Class<? extends DataEntry>> eventMap`
  - `List<FlagHandler> flagHandlerList`
  - `List<DisplayHandler> displayHandlerList`
  - `Map<UUID, List<ActionResult>> lastActionResults` (for `/omni undo`)
  - `Set<UUID> activeWandList`
  - `WorldEditHandler worldEditHandler`
  - `StorageHandler storageHandler`

  `onEnable` is the main wiring method: saves default config, sets up `OmniConfig`, connects storage, registers event-wrapper classes, parameters, flags, display handlers, commands, listeners, and schedules the `EntryQueueRunner` at 20-tick intervals.
- **`OmniConfig`** — singleton (`OmniConfig.INSTANCE`). Reads `config.yml` and exposes typed getters for: DB type and name, wand material, debug flag, database limits, defaults (radius, time), integration toggles (FAWE, CraftBook, WorldEdit), AI settings, record expiry, pool size, max radius, etc. Also defines a `DatabaseType` inner enum mapping `"mongodb"`/`"dynamodb"` strings to `StorageHandler` subclass constructors via reflection (`invokeConstructor()`).
- **`OmniEventRegistrar`** — another singleton (`OmniEventRegistrar.INSTANCE`). Owns the `Map<String, PastTenseWithEnabled>` of every registerable event (e.g., `"break"` → `PastTense("broke", enabled=true)`) and the list of `OmniListener` instances. `enableEvents()` actually registers them with Bukkit's `PluginManager` (only if the event is enabled in config).

## Load order, as wired

```
onLoad() → (empty; ignored)
onEnable():
  1. PLUGIN_INSTANCE = this
  2. INSTANCE = new OmniCore()
  3. OmniCore.onEnable(plugin, scheduler):
     a. OmniApi.setCore(this)
     b. saveDefaultConfig()
     c. OmniConfig.INSTANCE.setup(config)
     d. storageHandler = dbType.invokeConstructor() ; connect()
     e. registerEventWrapperClasses()   // event name → DataEntry subclass
     f. registerParameters()            // 17 ParameterHandler instances
     g. registerFlags()                 // 5–7 FlagHandler instances
     h. registerDisplayHandlers()       // 4 DisplayHandler instances
     i. registerCommands(plugin)        // Cloud registration
     j. registerEventHandlers(plugin)   // WandInteractListener, PluginInteractionListener,
                                        // optional CraftBookSignListener,
                                        // then OmniEventRegistrar.INSTANCE.enableEvents(pm, plugin)
     k. scheduler.runTaskTimerAsynchronously(EntryQueueRunner, 20, 20)
     l. (two empty if-blocks intended for FAWE / WE integration config wiring)
     m. log "Omniscience is Awake."
onDisable():
  INSTANCE.onDisable(plugin)   // no-op body
```

## Config.yml schema (as consumed by OmniConfig)

```yaml
display:
  format: d/M/yy hh:mm:ss       # getDateFormat()
  simpleFormat: d/M/yy          # getSimpleDateFormat()
wand:
  material: redstone_lamp       # getWandMaterial()
debug: false                    # isDebugEnabled()

database:
  type: mongodb | dynamodb      # getDbType() → DatabaseType enum
  name: Omniscience             # getDatabaseName()
  dataTableName: DataEntry      # getTableName()

mongodb:
  user / password / usesauth / authenticationDatabase
  servers:                      # List<Map<String, Map<String, Object>>> with address+port
    - ServerA:
        address: 127.0.0.1
        port: 27017

storage:
  expireRecords: 4w             # getRecordExpiry() → fed into DateUtil.parseTimeString
  maxPoolSize: 10               # getMaxPoolSize()
  minPoolSize: 2                # getMinPoolSize()
  purgeBatchLimit: 100000       # getPurgeBatchLimit()

defaults:
  enabled: true                 # areDefaultsEnabled() → gates FlagIgnoreDefault + FlagGlobal registration
  radius: 5                     # getDefaultRadius()
  time: 3d                      # getDefaultSearchTime()

limits:
  radius: 250                   # getRadiusLimit()
  lookup.size: 1000             # getLookupLimit()
  actionables: 10000            # getActionablesLimit()

events:
  break: {enabled: true, past: "broke"}
  place: {enabled: true, past: "placed"}
  ...40+ event definitions

ai:
  enabled: false                # isAIEnabled()
  apiKey: your-api-key-here     # getAIApiKey()
  model: gemini-2.0-flash       # getAIModelId()

integration:
  craftbookSigns: true          # doCraftBookInteraction()
  worldEdit: true               # doWorldEditInteraction()
  fastAsyncWorldEdit: true      # doFaweInteraction() — read but unused in onEnable's empty if-block
```

## Singleton-access pattern

Every piece of state that anything else in the plugin wants is reached through `Omniscience.<static>()`. The `Omniscience` class exposes around **25 static delegates** — `getStorageHandler()`, `getDataEntryClass()`, `getParameterHandler()`, `getFlagHandler()`, `getFlagHandlers()`, `getDisplayHandler()`, `getParameters()`, `getEvents()`, `hasActiveWand()`, `wandActivateFor()`, `wandDeactivateFor()`, `onWorldEditStatusChange()`, `addLastActionResults()`, `getLastActionResults()`, `registerEvent()` (2×), `registerDisplayHandler()`, `registerFlagHandler()`, `registerParameterHandler()`, `logDebug()`, `getPluginInstance()`, `getInstance()`.

All of these are ambient global state. No dependency injection, no explicit wiring, nothing testable in isolation. `OmniApi` (in the API module) is a second copy of the same pattern for external plugins.

## Pain points

1. **Dead integration if-blocks.** Lines 79–87 of `OmniCore.onEnable` read config booleans for `fastAsyncWorldEdit` and `worldEdit` integration, check whether the plugin is enabled, and then do nothing inside the if-blocks. WorldEdit wiring actually happens inside `registerFlags()` via `onWorldEditStatusChange(true)` at line 154 — not via the integration config toggles. So turning `integration.worldEdit: false` in config doesn't actually disable anything; the checks are half-wired.
2. **`registerFlags` has a side-effecting WE hook.** The method's name suggests pure flag registration, but it also calls `onWorldEditStatusChange(true)` if WE is enabled. Violates the least-surprise principle and makes flag registration order-sensitive with plugin-load order.
3. **Two singleton classes.** `Omniscience` holds two statics (`INSTANCE`, `PLUGIN_INSTANCE`). `OmniConfig.INSTANCE`. `OmniApi` holds a core reference. `OmniEventRegistrar.INSTANCE`. Five separate singleton patterns for one plugin.
4. **`OmniCore` implements `IOmniscience` but isn't fully public.** The class is package-private (`final class OmniCore`) — it can only be reached via `OmniApi.getCore()` (also package-private) or the static delegates on `Omniscience`. `IOmniscience` is the cleaner interface but code inside Core still calls `OmniCore`-specific package-private methods, so the interface contract isn't enforced.
5. **Reflection-based storage instantiation.** `DatabaseType.invokeConstructor()` calls `class.newInstance()` (deprecated) to build the storage handler. No DI, no ServiceLoader. Adding a new DB backend means editing the enum.
6. **Config is a Bukkit `FileConfiguration`.** Flat key reads, no type safety, no validation. Misspelling a key silently defaults. `OmniConfig.setup()` parses eagerly at startup into ~40 instance fields.
7. **`lastActionResults` is in-memory only.** If the server restarts, nobody can `/omni undo` their last rollback. Acceptable for a forensics tool but surprising.
8. **No `onDisable` teardown.** `OmniCore.onDisable` is empty. Storage connection stays open (relies on JVM shutdown), scheduler is Bukkit's so it gets cleaned, but there's no flush of pending entries. If the server stops mid-burst, in-flight entries in `EntryQueue` die unsubmitted.

## Modernization hotspots

1. **Kill singletons, use constructor injection.** `OmniCore` takes a `StorageHandler`, `ConfigProvider`, `EventRegistry`, etc. via its constructor. `Omniscience` (the Paper plugin) builds them. Nothing needs a static accessor. Makes testing possible.
2. **Type-safe config with Configurate or a record-based loader.** Define a `record OmniConfig(Database db, Storage storage, Defaults defaults, Limits limits, Map<String, EventConfig> events, AiConfig ai, IntegrationConfig integration) {}`. Load once, pass around. Bukkit's `FileConfiguration` is a relic.
3. **Paper plugin lifecycle (paper-plugin.yml).** Adopt the modern Paper bootstrap/lifecycle API for ordered startup, access to `LifecycleEventManager` for clean command / integration wiring, proper disable hooks.
4. **Drop the deprecated `Class.newInstance()`.** Use `MethodHandles.Lookup` or a static factory method on each storage backend. Better yet, use a `ServiceLoader` or DI registry keyed by name.
5. **Fix the integration toggles.** Either delete the dead branches or wire them. Currently config options that imply "turn this off" don't actually turn it off, which is worse than not having the option at all.
6. **Flush queue on disable.** `onDisable` should drain the `EntryQueue` synchronously (with a timeout) so in-flight events make it to Mongo. Alternatively: write-through mode.
7. **Collapse the five singletons into one injected registry.** `Registry { storage, parameters, flags, displays, events, config, worldEdit, ai }` passed into every listener/command/handler at construction time.
8. **`IOmniscience` interface should be narrower and consumer-focused.** Right now it's a dumping ground (~30 methods). Split into `OmniscienceApi` (for external plugins: register event, register handler, query) and `OmniscienceContext` (for internal use: storage, registries). External consumers shouldn't see `getStorageHandler()`.

## What v2 should keep

- Decoupling storage behind a `StorageHandler` / `RecordHandler` interface. The abstraction is sound; just needs modernizing.
- Per-event enable/disable from config. Useful and worth preserving.
- TTL-based retention via `storage.expireRecords`. Low-maintenance win. Keep.
- Reading defaults + limits from config. Keep.
