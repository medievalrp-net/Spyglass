# v1 Dissection — 03: Events and Entries

Files covered:

- **API entry model** (`v1API/src/main/java/net/medievalrp/v1/api/entry/`): `DataEntry.java`, `DataEntryComplete.java`, `DataAggregateEntry.java`, `OEntry.java`, `EntryQueue.java`, `ActionResult.java`, `Actionable.java`, `ActionableException.java`, `SkipReason.java`.
- **Core entry impls** (`the v1 core/src/main/java/net/medievalrp/v1/api/entry/`): `BlockEntry.java`, `ContainerEntry.java`, `EntityEntry.java`, `EntryQueueRunner.java`.
- **Listener hierarchy** (`the v1 core/src/main/java/net/medievalrp/v1/listener/`): 30 classes across `block/` (12), `item/` (7), `entity/` (4), `chat/` (2), `player/` (3), plus top-level `sg`, `WandInteractListener`, `PluginInteractionListener`, `CraftBookSignListener`, `PermissionListener`.

## Responsibility

Everything between a Bukkit event firing and a `DataWrapper` being handed to storage. Three jobs:

1. **Extract** relevant state out of a Bukkit event (the listener classes).
2. **Build** a typed record describing what happened (the `OEntry` builder chain + the `DataEntry` subclass tree).
3. **Buffer and drain** those records asynchronously into the storage layer (`EntryQueue` + `EntryQueueRunner`).

There is also a secondary, mostly-orthogonal responsibility: **rollback/restore**. `DataEntry` subclasses in Core implement `Actionable` so the same record that was written during logging can be walked backward during `/sg rollback`. That path is covered in depth in `06-rollback.md`; this doc only notes the coupling.

## Classes

### API side

- **`DataEntry`** (`DataEntry.java:11`) — abstract parent of every entry type. Exposes a single public field `data` of type `DataWrapper`. Provides a `static DataEntry from(String eventName, boolean isAggregate)` factory at line 15 that reflectively instantiates the registered subclass via `Class.getConstructor().newInstance()`. Equals/hashCode/toString delegate entirely to `data`.
- **`DataEntryComplete`** (`DataEntryComplete.java:12`) — extends `DataEntry`. Adds `getRelativeTime()` and `getTime()` for rendering timestamps in search results. All non-aggregate search hits are some subclass of this.
- **`DataAggregateEntry`** (`DataAggregateEntry.java:8`) — extends `DataEntry`. Adds a `date` string + `setDate(Calendar)` for the "group by day" flag path. Never implements `Actionable`; aggregates can't be rolled back by definition.
- **`OEntry`** (`OEntry.java:35`) — the builder monolith. Nested classes: `SourceBuilder` (holds the raw source object), `EventBuilder` (holds the event name + `DataWrapper`, has every typed method), `EntryBuilder` (root-level factory with `source/player/entity/plugin/environment` entry points), `PlayerEventBuilder` (subclass of `EventBuilder` adding player-only methods). The public API is `OEntry.create().source(x).brokeBlock(transaction).save()`.
- **`EntryQueue`** (`EntryQueue.java:9`) — a static holder around a single `LinkedBlockingDeque<DataWrapper>`. Only operation that adds: `submit(wrapper)` with event-name validation. No cap, no backpressure, no `drainTo`.
- **`ActionResult`** (`ActionResult.java:5`) — immutable struct: `boolean changeWasApplied`, `SkipReason reason`, `Transaction transaction`. Constructed via `ActionResult.success(transaction)` or `ActionResult.skipped(reason)`. Carries the before/after of one rollback step.
- **`Actionable`** (`Actionable.java:3`) — two-method interface: `ActionResult rollback() throws Exception`, `ActionResult restore() throws Exception`. Default helper `skipped(SkipReason)` returns a pre-wrapped `ActionableException`.
- **`ActionableException`** (`ActionableException.java:3`) — checked exception wrapping an `ActionResult` so a rollback step can short-circuit up the stack.
- **`SkipReason`** (`SkipReason.java:3`) — enum: `INVALID_LOCATION`, `INVALID`, `OCCUPIED`, `UNIMPLEMENTED`, `UNKNOWN`.

### Core side

These three all live under `net.medievalrp.v1.api.entry` despite being Core-internal — the API/impl boundary leak called out in `00-overview.md`.

- **`BlockEntry`** (`BlockEntry.java:21`) — `extends DataEntryComplete implements Actionable`. `rollback()` reads `ORIGINAL_BLOCK` from `data`, deserializes via `DataHelper.getBlockDataFromWrapper`, forces the block's material, then restores tile entity state (`handleTileEntity` at line 75 covers Container / Sign / Banner / Jukebox). `restore()` is the symmetric op on `NEW_BLOCK`.
- **`ContainerEntry`** (`ContainerEntry.java:11`) — `extends DataEntryComplete implements Actionable`. Single-slot rollback/restore: pulls `ITEM_SLOT`, `BEFORE/AFTER.ITEMSTACK`, and swaps. Note this does **not** extend `BlockEntry` (which is the docblock mental model from the overview — it's wrong on that point; both extend `DataEntryComplete` independently but `ContainerEntry` does not inherit `BlockEntry`'s block-restore logic). Container deposit/withdraw rollback is fundamentally slot-diff, not block-state-swap, so the separation is correct even if the naming suggests otherwise.
- **`EntityEntry`** (`EntityEntry.java:11`) — `extends DataEntryComplete implements Actionable`. `rollback()` re-spawns the killed entity from its captured NBT (via `ReflectionHandler.loadEntityFromNBT`). `restore()` returns `SkipReason.UNIMPLEMENTED` — entity "restore" has no sensible meaning.
- **`EntryQueueRunner`** (`EntryQueueRunner.java:10`) — `Runnable`. Drains the entire deque into a `List<DataWrapper>` every invocation, then hands the whole batch to `v1.getStorageHandler().records().write(...)`. Scheduled by `sg.onEnable` at 20-tick intervals asynchronously.

### Listeners

Every listener other than the top-level five extends **`sg`** (`sg.java:7`), which is a thin abstract `Listener` that holds an `ImmutableList<String>` of event names it handles and an `enabled` flag toggled by `sg.enableEvents`. A single `protected boolean isEnabled(String event)` method defers to the registrar singleton.

Block listeners (`listener/block/`, 12 classes):

| File | Handles | Bukkit events |
|---|---|---|
| `EventBreakListener.java` | `break` | `BlockBreakEvent`, `BlockExplodeEvent`, `EntityBreakDoorEvent`, `EntityExplodeEvent`, `BlockBurnEvent` |
| `EventPlaceListener.java` | `place` | `BlockPlaceEvent`, `BlockMultiPlaceEvent`, `SignChangeEvent` |
| `EventDecayListener.java` | `decay` | `LeavesDecayEvent`, `BlockFadeEvent` |
| `EventFormListener.java` | `form` | `BlockFormEvent` |
| `EventIgniteListener.java` | `ignite` | `BlockIgniteEvent` (also writes `player-source` metadata to propagate identity through subsequent explode events) |
| `EventGrowListener.java` | `grow` | `BlockGrowEvent`, `StructureGrowEvent` |
| `EventUseListener.java` | `use` | `PlayerInteractEvent` (filtered to Openable/Switch/Repeater/NoteBlock/etc.) |
| `EventBookshelfListener.java` | `bookshelf-insert`, `bookshelf-remove` | `PlayerInteractEvent` on `CHISELED_BOOKSHELF` (1.20+) |
| `EventBrushListener.java` | `brush` | `PlayerInteractEvent` on `SUSPICIOUS_SAND/GRAVEL` with a 100-tick delayed verify (1.20+) |
| `EventDecoratedPotListener.java` | `pot-insert`, `pot-remove` | `PlayerInteractEvent` on `DECORATED_POT` (1.20+) |
| `EventSculkListener.java` | `sculk` | `BlockReceiveGameEvent` on sculk sensors / shriekers (1.19+) |
| `EventCrafterListener.java` | `craft` | `CrafterCraftEvent` (1.21+) |
| `EventVaultListener.java` | `vault` | `PlayerInteractEvent` on `VAULT` with a 1-tick delayed key-consumption check (1.21+) |

Item listeners (`listener/item/`, 7 classes):

| File | Handles | Bukkit events |
|---|---|---|
| `EventContainerListener.java` | `open`, `close` | `InventoryOpenEvent`, `InventoryCloseEvent` (Container / DoubleChest holders only) |
| `EventDropListener.java` | `drop` | `PlayerDropItemEvent`, `EntityDropItemEvent`, `BlockDispenseEvent` |
| `EventInventoryListener.java` | `withdraw`, `deposit`, `clone` | `InventoryClickEvent`, `InventoryDragEvent` — defers slot-diffing to `InventoryUtil.identifyTransactions` |
| `EventPickupListener.java` | `pickup` | `EntityPickupItemEvent` |
| `EventEntityItemListener.java` | `entity-withdraw`, `entity-deposit` | `PlayerArmorStandManipulateEvent`, `PlayerInteractAtEntityEvent`, `EntityDamageByEntityEvent` (item frames) |
| `EventBundleListener.java` | `bundle-insert`, `bundle-extract` | `InventoryClickEvent` on bundles (1.21+) |
| `EventShulkerListener.java` | `shulker-open`, `shulker-close`, `shulker-deposit`, `shulker-withdraw` | Four separate event handlers on shulker inventories |

Entity listeners (`listener/entity/`, 4 classes):

| File | Handles | Bukkit events |
|---|---|---|
| `EventDeathListener.java` | `death` | `EntityDeathEvent` (also forwards drops to `droppedItem`) |
| `EventHitListener.java` | `hit`, `shot` | `EntityDamageByEntityEvent` (splits player-vs-projectile paths) |
| `EventInteractAtEntity.java` | `named` | `PlayerInteractAtEntityEvent` with a name tag |
| `EventMountListener.java` | `mount`, `dismount` | `EntityMountEvent`, `EntityDismountEvent` |

Chat listeners (`listener/chat/`, 2 classes):

| File | Handles | Bukkit events |
|---|---|---|
| `EventCommandListener.java` | `command` | `PlayerCommandPreprocessEvent`, `ServerCommandEvent` |
| `EventSayListener.java` | `say` | `AsyncChatEvent` (Paper's Adventure-native chat; builds `RECIPIENT` as comma-separated UUIDs) |

Player listeners (`listener/player/`, 3 classes):

| File | Handles | Bukkit events |
|---|---|---|
| `EventJoinListener.java` | `join` | `PlayerJoinEvent` (captures host string) |
| `EventQuitListener.java` | `quit` | `PlayerQuitEvent` |
| `EventTeleportListener.java` | `teleport` | `PlayerTeleportEvent` (filters `UNKNOWN` cause) |

Top-level (`listener/`, 5 classes):

- **`sg.java`** — the abstract base described above.
- **`WandInteractListener.java`** — Not an `sg`. Implements `Listener` directly. Three handlers: `onPlayerJoin` auto-activates the wand for players with `v1.commands.search.autotool`; `onPlayerInteract` performs a location lookup when clicking with the wand material; `onBlockPlace` does the same for block-place interactions. Builds `QuerySession` + `SearchConditionGroup` in-line and hands to `Async.lookup`. This is not an event-logging listener — it's a query-entry-point listener.
- **`PluginInteractionListener.java`** — `PluginEnable/DisableEvent`. Toggles WorldEdit integration via `v1.onWorldEditStatusChange`; the CraftBook branch is empty.
- **`CraftBookSignListener.java`** — single handler. Writes a `useSign` entry on any sign right-click. Only registered if `sg.doCraftBookInteraction()` + CraftBook is enabled.
- **`PermissionListener.java`** — `PlayerCommandSendEvent` handler with an empty method body. Registered nowhere that I can find. Dead code.

## Flow of one event end-to-end: Block Break

Canonical path, because break is the most-exercised event and touches everything.

**Step 1 — Bukkit fires `BlockBreakEvent`.** A player swings, a block breaks.

**Step 2 — `EventBreakListener.onBlockBreak` fires** (`EventBreakListener.java:39`). The listener was registered back at `sg.registerEventHandlers` → `sg.enableEvents` (`sg.java:172`) which only registered it because `config.yml` had `events.break.enabled: true` and `sg.setup` added the entry at `sg.java:89`.

The handler body:
```java
OEntry.create()
      .source(event.getPlayer())
      .brokeBlock(new LocationTransaction<>(event.getBlock().getLocation(),
                                            event.getBlock().getState(),
                                            null))
      .save();
```
Plus three auxiliary calls: `saveContainerDrops` (drops from chests destroyed with the block), `saveMultiBreak` (cascading breaks for beds, stacked cacti, etc.), and `saveDependantBreaks` (torches on the broken wall, carpets on top).

**Step 3 — `OEntry.create()`** (`OEntry.java:44`) returns a new `EntryBuilder`.

**Step 4 — `.source(event.getPlayer())`** (`OEntry.java:636`) walks the instanceof ladder. Player is an `OfflinePlayer`, matches the first branch, returns `new EventBuilder(new SourceBuilder(source))`. Note: the fact that it's actually a `Player` is never captured in a type-safe way; the downstream code will `instanceof` again.

**Step 5 — `.brokeBlock(transaction)`** (`OEntry.java:124`). Sets `eventName = "break"`. Pulls the `getOriginalState()` optional from the transaction and writes:
- `ORIGINAL_BLOCK` → `DataWrapper.ofBlock(block)` (material + block data)
- `TARGET` → block material name
- Plus tile entity data via `writeExtraStateData` (sign lines, container inventory, banner patterns, jukebox disc).

Then `writeLocationData(transaction.getLocation())` drops `LOCATION.X/Y/Z/WORLD` onto the wrapper. Returns `new OEntry(sourceBuilder, this)`.

**Step 6 — `.save()`** (`OEntry.java:53`). Validates `sg.isEventRegistered(eventName)` — throws `IllegalArgumentException` otherwise. Sets `EVENT_NAME` and `CREATED` (current `Date`). Then walks another `instanceof` ladder on the source to decide whether to key the entry as `PLAYER_ID` (UUID string) or `CAUSE` (string like "environment", "console", "pl@SomePlugin", entity type, etc.). Finally: `EntryQueue.submit(wrapper)`.

**Step 7 — `EntryQueue.submit`** (`EntryQueue.java:16`). Re-validates event name + registration, then `queue.add(wrapper)`. Returns immediately; caller is on the Bukkit main thread.

**Step 8 — Later, up to 1 second later, `EntryQueueRunner.run`** (`EntryQueueRunner.java:13`) fires on the async scheduler. Drains the entire deque via `while(!queue.isEmpty()) queue.poll()`. If anything was drained, calls `storageHandler.records().write(batchWrappers)`. On exception, prints stack trace and loses the batch.

**Step 9 — Storage** (covered in `02-data-and-storage.md`) flattens each `DataWrapper` to a Mongo `Document` and bulk-inserts.

**Step 10 — Much later, `/sg rollback p:alice 1h r:20`** queries for `BlockEntry` records (the event-class mapping — `"break"` → `BlockEntry.class` — comes from `sg.registerEventWrapperClasses` at line 103). `DataEntry.from(eventName, false)` (`DataEntry.java:15`) does `sg.getEventClass("break").orElse(DataEntryComplete.class).getConstructor().newInstance()`, then assigns `entry.data = <loaded wrapper>`. The `Actionable.rollback()` method on the reconstituted `BlockEntry` reverses step 5.

## Event-name registration: how the two registries connect

v1 has **two** parallel event-name tables and they are not the same thing. You cannot skip this if you want to understand how a custom event plugs in.

### Table A: name → past tense + enabled flag (`sg.eventMapping`)

Populated at config load time by `sg.setup` (`sg.java:87-90`):
```java
ConfigurationSection section = configuration.getConfigurationSection("events");
for (String key : section.getKeys(false)) {
    ConfigurationSection innerSection = section.getConfigurationSection(key);
    sg.INSTANCE.addEvent(key, innerSection.getString("past"), innerSection.getBoolean("enabled"));
}
```
External plugins can add entries through `sg.registerEvent(name, pastTense)` (`sg.java:91`), which delegates to `sg.registerEvent(String, String)` (`sg.java:233`), which calls `addEvent(event, pastTense, enabled=true)`. No way to register as initially disabled — external events always enable themselves.

This table is consulted by:
- `sg.isEventRegistered` (called from `OEntry.save` and `EntryQueue.submit` for validation)
- `sg.getEventPastTense` (used in search-result rendering)
- `sg.enableEvents` (to decide which `sg` instances to hook up with the `PluginManager`)

### Table B: name → `DataEntry` subclass (`sg.eventMap`)

Populated by the hard-coded `registerEventWrapperClasses` (`sg.java:103-111`):
```java
registerEvent("break", BlockEntry.class);
registerEvent("place", BlockEntry.class);
registerEvent("grow", BlockEntry.class);
registerEvent("form", BlockEntry.class);
registerEvent("death", EntityEntry.class);
registerEvent("withdraw", ContainerEntry.class);
registerEvent("deposit", ContainerEntry.class);
```
Only **seven** events get a dedicated subclass. Every other registered event (`break` vs `decay` vs `use` vs `command` vs `join` vs ... ~40 total) falls through to `DataEntryComplete` at `DataEntry.from` line 21 — which means those events have no `Actionable` implementation and can't be rolled back. That's fine for `command` or `join`, but means `ignite`, `decay`, `entity-deposit`, `shulker-*`, `pot-*`, `bookshelf-*`, `bundle-*`, etc. are all unrecoverable.

External plugins **cannot** add to Table B. The `Iv1.registerEvent(String, String)` signature only adds to Table A. The Class-typed overload `sg.registerEvent(String, Class<? extends DataEntry>)` is package-private and there's no API passthrough. A consumer plugin wanting their custom event to be rolled back has no hook.

### How `sg.handles` ties in

`sg.enableEvents` (`sg.java:172`) iterates `eventMapping` (Table A) and for each `(name, PastTenseWithEnabled)` pair finds the first `sg` where `listener.handles(name)` returns true. If found AND enabled, `pm.registerEvents(listener, plugin)`. One listener can claim multiple names via its `ImmutableList<String>` ctor arg (see `EventHitListener` handling both `hit` and `shot`). No name can be claimed by two listeners — `findFirst` silently takes the first.

The mechanism is sound and pluggable enough that `config.yml` can genuinely flip events off. The catch is that the `sg` instances themselves are hard-coded in `sg`'s constructor (`sg.java:29-76`) — external plugins can register arbitrary listeners with Bukkit directly, of course, but there's no API to plug an `sg` into this registry. If you want an external event to respect config's `events.foo.enabled: false` flag, you have to re-implement the gate.

## Pain points

### 1. Listener boilerplate is copy-paste

The 30 `sg` classes follow nearly the same shape: one or more `@EventHandler`, usually `ignoreCancelled = true, priority = MONITOR`, extract a handful of fields, call `OEntry.create().source(x).<verb>(...).save()`. The similarity is striking:

- `EventDecayListener.onLeavesDecay` (`EventDecayListener.java:19`) is five lines.
- `EventFormListener.onBlockForm` (`EventFormListener.java:18`) is three.
- `EventPickupListener.onEntityPickupItem` (`EventPickupListener.java:17`) is one expression.
- `EventDeathListener.onEntityDeath` (`EventDeathListener.java:19`) is six.

Every listener rebuilds the same extract-transform-submit pipeline by hand. A generic `EventExtractor<BukkitEvent, EntryRecord>` registry would collapse most of these to a one-liner declaring a mapping. The outliers (`EventBreakListener` with its dependant-break cascade, `EventInventoryListener` with its transaction diffing, `EventVaultListener` / `EventBrushListener` with delayed verification) would still need bespoke code, but that'd be five classes instead of thirty.

### 2. `OEntry` is a 700-line God class

`OEntry.java` owns, in one file:

- `OEntry` itself (the completed builder that exposes `with(...)` + `save()`)
- `SourceBuilder` (stores the source object)
- `EventBuilder` (stores the event name + wrapper; has `brokeBlock`, `placedBlock`, `decayedBlock`, `grewBlock`, `formedBlock`, `dropped`, `droppedItem`, `pickup`, `said`, `ranCommand`, `hit`, `shot`, `kill`, `removedFromItemFrame`, `putIntoItemFrame`, `putIntoArmorStand`, `removedFromArmorStand`, `opened`, `closed`, `use`, `mount`, `deposited`, `withdrew`, `ignited`, `named`, `custom`, `customWithLocation` — **27 typed verbs**)
- `EntryBuilder` (the root-level `.source/.player/.entity/.plugin/.environment` factory)
- `PlayerEventBuilder` (adds `signInteract`, `cloned`, `quit`, `joined`, `teleported` — player-only verbs)
- Four protected helpers (`writeExtraStateData`, `writeGenericDamageData`, `writeLastDamageData`, `writeLocationData`, `writeItemData`)
- Five private helpers for item display formatting (`getItemNbtString`, `getItemDisplayName`, `getItemLore`, `getItemEnchantments`, `formatEnchantmentName`, `toRoman`)

Adding a new event type means editing this file. Every consumer sees every verb in their IDE autocomplete. The file violates both SRP (it's a builder AND a serializer AND an item formatter) and OCP (can't extend without modification — which is why `with(DataKey, Object)` exists).

### 3. `with(DataKey, Object)` is the escape-hatch band-aid

`OEntry.with` (`OEntry.java:48`) was added during the WorldEdit origin-tagging work this session:
```java
public OEntry with(DataKey key, Object value) {
    eventBuilder.getWrapper().set(key, value);
    return this;
}
```
It's used in exactly two places: `WorldEditLogger.java:138`, `:164` and `FaweBatchLogger.java:163`. Each one slaps `.with(DataKeys.ORIGIN, "worldedit")` / `"fawe"` onto an otherwise-stock `brokeBlock` / `placedBlock` call. This is a clean workaround but it admits the real problem: the builder methods bake all their fields in at call time, and the only way to decorate a built entry is to reach into the wrapper directly. Any future "add this extra field for a specific subset of sites" work will end up as more `.with()` calls. That's fine tactically but it means the typed-verb surface on `EventBuilder` isn't actually typed all the way — half of fields can only be set by string-keyed `DataKey` lookup.

### 4. API/impl boundary leak

`BlockEntry.java`, `ContainerEntry.java`, `EntityEntry.java`, and `EntryQueueRunner.java` are under `net.medievalrp.v1.api.entry` in the **Core module**. The package root is the same as the API module's `net.medievalrp.v1.api.entry`. At runtime they coexist in one classloader because Core shades the API. An external plugin that imports v1API sees `DataEntry`, `DataEntryComplete`, `DataAggregateEntry`, `OEntry`, `EntryQueue`, `ActionResult`, `Actionable`, `ActionableException`, `SkipReason` — but **not** `BlockEntry` / `ContainerEntry` / `EntityEntry` / `EntryQueueRunner`. The package partition lies: the namespace hierarchy suggests a unified API surface but the module boundary leaves three Core-only classes floating in "API" space.

This mostly works until someone tries to publish `v1API` separately for external consumers. The listener-based `sg.registerEventWrapperClasses` references `BlockEntry.class` etc. directly (`sg.java:104-110`), which means Core can't compile without those classes, but the classes themselves pretend to be API-level.

### 5. `EntryQueue` is lossy on crash

`EntryQueue.getQueue()` is a static `LinkedBlockingDeque<DataWrapper>` (`EntryQueue.java:11`). `EntryQueueRunner` (`EntryQueueRunner.java:13`) is scheduled at `sg.onEnable` for 20-tick intervals (`sg.java:74-77`) — i.e. once per second. If the server crashes, SIGKILLs, or restarts within that one-second window, every queued entry is lost. `sg.onDisable` (`sg.java:95`) is empty — no flush-on-shutdown.

1 second of latency is acceptable for a forensics tool during normal operation. Zero flush on disable is not — it means a graceful `/stop` right after a burst of block-break events drops those events. This is worse than it sounds for the rollback case: a griefer could race the queue if they knew the drain interval.

### 6. `ContainerEntry` does not extend `BlockEntry`

The overview doc in this series says "ContainerEntry extends BlockEntry so inherits". Reading `ContainerEntry.java:11` — it extends `DataEntryComplete` directly, not `BlockEntry`. The overview is wrong on that point; this doc is the source of truth. The practical effect is that `ContainerEntry`'s rollback/restore is slot-level only; if you want the container-as-a-block rolled back you need `BlockEntry` for the place event plus `ContainerEntry` for each slot change. The split is correct (the two operations have different semantics) but the docs need fixing.

### 7. `EntityEntry.restore` is `UNIMPLEMENTED`

`EntityEntry.restore()` (`EntityEntry.java:44`) returns `ActionResult.skipped(SkipReason.UNIMPLEMENTED)`. That's correct — you can't "restore" a death; you can only "rollback" (revive). The design question is whether entity rollback (resurrecting mobs from stored NBT) is a feature anyone wants. `ReflectionHandler.loadEntityFromNBT` is explicitly called out as "UNSAFE" in the code comment at `EntityEntry.java:37`. Deferred resurrection of a mob whose NBT schema changed between MC versions is going to fail loudly.

### 8. Source-handling is an `instanceof` staircase done twice

`EntryBuilder.source` (`OEntry.java:636`) has 8 `instanceof` branches selecting the right wrapping. Then `OEntry.save` (`OEntry.java:60`) has another 6 `instanceof` branches on the same source object to build the `causeId` string. The second cascade also adds the only place where a `BlockCommandSender` captures location data (`OEntry.java:74-84`) — which means a command block source writes `X/Y/Z/WORLD` into the top-level wrapper, bypassing `LOCATION.X/Y/Z/WORLD`, which is inconsistent with every other location write. Bug risk in queries that expect `LOCATION.*`.

### 9. `sg.getEventMapping` returns a weird empty map on exception

`sg.java:100-166` tries to return `ImmutableMap.copyOf(eventMapping)`. If that throws, it returns a custom 60-line anonymous `Map` with `size() → 0`, `isEmpty() → false`, `containsKey → false`, and every other method returning null. No logging, no exception propagation. The ImmutableMap.copyOf cannot realistically throw on a `HashMap<String, PastTenseWithEnabled>` — this is dead defensive code that would silently corrupt caller logic if it ever did trigger. Delete it.

### 10. `PermissionListener` is dead

`PermissionListener.java:20` has an empty `onPlayerCommandSend` body. Ripgrep confirms it's never registered. Delete.

### 11. `sg.handles` is `List.contains` — linear scan every event lookup

`sg.java:24` is `events.contains(event)`. For a listener handling one event (the common case) that's fine. But `sg.enableEvents` (`sg.java:174`) filters `listeners.stream()` with that predicate for every event in the mapping — O(events × listeners) at startup. With 40 events and 30 listeners that's 1200 comparisons; not hot but not clean either. A `Map<String, sg>` built once would be more honest.

### 12. Two listeners use delayed-task verification

`EventBrushListener` (`EventBrushListener.java:102`) schedules a 100-tick (5 second) `runTaskLater` to verify the brush completed. `EventVaultListener` (`EventVaultListener.java:87`) schedules a 1-tick `runTaskLater` to check if a trial key was consumed. Both maintain their own `ConcurrentHashMap<String, Long>` deduplication windows with their own cleanup. This is a pattern that will recur for any Minecraft mechanic without a direct event (which is plenty of them — 1.22+ will add more). The bespoke implementations should collapse into a shared "schedule a verify-then-log after interaction" helper.

### 13. Listener-embedded `DataWrapper` construction

Several listeners (see `EventBookshelfListener.java:45-49`, `EventDecoratedPotListener.java:52-57`, `EventShulkerListener.java:77-82`, `EventBundleListener.java:57-63`, `EventCrafterListener.java:34-40`, `EventBrushListener.java:133-144`, `EventVaultListener` is not in this list — it uses the typed `use()` verb) build a fresh `DataWrapper`, manually set `TARGET`/`ITEMSTACK`/`QUANTITY`/`DISPLAY_METHOD`, then hand it to `OEntry.create().source(p).customWithLocation(eventName, wrapper, location).save()`. This is the opt-out path for events `OEntry`'s typed verbs don't cover. It works but every listener using `custom`/`customWithLocation` is bypassing half the point of having a typed builder — the `DataKey`s are stringly-set, the display method is set by convention, and there's no compile-time check that the event's expected schema matches what was actually written. Adding a new event becomes "scatter conventions across one more file."

### 14. No backpressure on `EntryQueue`

`LinkedBlockingDeque` is unbounded. A single WorldEdit `//set` of a 100k-block region dumps 100k wrappers into memory before the async task drains once. The drain is fast (it's just a bulk insert) but if storage stalls (network hiccup, Mongo primary election) the queue grows without limit. There's no `Queue.offer(timeout)` path and no max-size check in `EntryQueue.submit`.

### 15. `EventInventoryListener.onInventoryDrag` forgets to call `.save()`

`EventInventoryListener.java:84`:
```java
case DEPOSIT:
    if (d()) {
        OEntry.create().player(e.getWhoClicked()).deposited(transaction, loc, null);
    }
    break;
```
Notice: no `.save()` at the end. The line builds an `OEntry` and throws it away. Inventory-drag deposits are silently dropped. This is a live bug, not a modernization hotspot.

### 16. `EventBreakListener.getStyle` is a 160-line `switch`

`EventBreakListener.java:240-402`. Every dependant block type hard-coded. Missing many 1.17+/1.18+ blocks (mangrove saplings, cherry leaves, azalea, etc. aren't in the list — confirmed by reading through the `case` list). Cannot be updated at runtime. Should be a data-driven config or derived from `BlockData` (Attachable, Waterlogged, etc.) wherever possible.

## Modernization hotspots

### 1. Sealed interface hierarchy for `DataEntry`

Java 21 sealed types + records give us:
```java
public sealed interface DataEntry permits BlockEntry, ContainerEntry, EntityEntry, ChatEntry, InteractionEntry, PlayerEntry, AggregateEntry {
    DataWrapper data();
    String eventName();
    default String pastTense() { ... }
}
```
`Actionable` becomes a secondary sealed interface: `sealed interface RollbackableEntry extends DataEntry permits BlockEntry, ContainerEntry, EntityEntry`. The `DataEntry.from(String, boolean)` reflective factory at `DataEntry.java:15` goes away — pattern-match on the event name or on the sealed type directly.

### 2. Typed event records replacing the `OEntry` monolith

Each of the 27 verbs on `EventBuilder` becomes a record:
```java
public record BrokeBlockEvent(UUID sourceId, BlockState original, Location location, Instant at, Map<DataKey, Object> extras) implements DataEntry.Source { ... }
```
The builder's typed methods become constructors / static factories on the record. A single `EntryWriter.write(DataEntry entry)` method replaces `OEntry.save`, using pattern-matching `switch` to flatten into a `DataWrapper` (or skip the wrapper layer entirely — see `02-data-and-storage.md`).

The `.with(DataKey, Object)` escape hatch becomes a typed `extras` Map on each record, or — better — specific subtypes like `BrokeBlockEvent.WithOrigin`. Origin tagging gets expressed as a record component rather than a side-channel.

### 3. Listener → Extractor → Entry pipeline

Replace the 30 copy-pasted listener classes with an extractor registry:
```java
@EventExtractor(handles = "break")
public class BlockBreakExtractor implements Extractor<BlockBreakEvent, BrokeBlockEvent> {
    public BrokeBlockEvent extract(BlockBreakEvent e) { ... }
    public List<DataEntry> cascade(BrokeBlockEvent primary, BlockBreakEvent e) { /* dependants */ }
}
```
A single generic listener registered via Paper's lifecycle API iterates `PluginManager.callEvent` paths, looks up the extractor by Bukkit event class, applies it, enqueues the result. Listeners that need multi-event handling (break handles 5 Bukkit events) register multiple extractors sharing the same output type.

Annotation-driven registration + a `ServiceLoader` or similar discovery mechanism means external plugins can drop in a jar with their own extractor and the core picks it up without Core code edits.

### 4. Virtual threads (JDK 21) for queue drain

The current `EntryQueueRunner` runs on a Bukkit async scheduler at a 1-second cadence. Move to:
```java
Thread.ofVirtual().name("sg-queue-drain-", 0).start(() -> {
    while (running) {
        DataEntry entry = queue.take();
        storage.write(List.of(entry)); // or batch with drainTo(batch, 100)
    }
});
```
Per-entry latency collapses from up-to-1s to milliseconds. Drain thread blocks on `take()` when idle, zero CPU. Batching becomes explicit via `queue.drainTo(batch, maxSize)` with a short poll. Virtual threads mean we don't care about the "blocking" thread's cost.

### 5. Flush on disable

`sg.onDisable` should drain the queue synchronously with a timeout:
```java
void onDisable() {
    shutdownFlag = true;
    List<DataEntry> pending = new ArrayList<>();
    queue.drainTo(pending);
    try {
        storage.write(pending).get(5, SECONDS);
    } catch (TimeoutException | InterruptedException | ExecutionException ex) {
        plugin.getLogger().warning("Flushed " + pending.size() + " entries with errors: " + ex);
    }
}
```
Or: write-through mode where the queue is bypassed entirely and every `save()` goes straight to storage on a virtual thread. For a ~1k events/sec server that's probably fine.

### 6. Type-safe event registration

Kill `sg.registerEventWrapperClasses` as a hard-coded switch. Move the mapping to the sealed type itself:
```java
public sealed interface DataEntry {
    String eventName();
    // ...
}
public record BrokeBlockEvent(...) implements RollbackableEntry {
    @Override public String eventName() { return "break"; }
    @Override public ActionResult rollback() { ... }
}
```
The registry becomes `Map<String, Class<? extends DataEntry>>` populated by scanning the sealed permits list at startup. External plugins extend the sealed type via a second-tier `sealed interface ExtensionEntry extends DataEntry permits ...` declared in their plugin, registered via an `v1Extension` service.

### 7. Pluggable event-name → entry-class mapping (keep)

The current string-keyed dispatch (`sg.getEventClass`, `sg.eventMapping`) is fine as a concept — it lets the wire format (Mongo's `Event` field) stay stable across refactors. Keep the external-facing string name, just make the Java side type-safe.

### 8. Generic `sg` base with config-gated enable

Current `sg.isEnabled(event)` does a lookup every time an event fires. Modern pattern: the generic dispatcher only registers with `PluginManager` when the event is enabled; toggling requires a re-register. Paper's `LifecycleEventManager` supports this. Kills the per-event `isEnabled("xyz")` call inside every listener method.

### 9. Merge delayed-verification listeners into a helper

`EventBrushListener` + `EventVaultListener` both schedule-and-check. Extract a `DelayedInteractionTracker` or similar: `tracker.trackInteraction(player, block, 100, () -> ifBlockChanged(originalState, () -> log("brush", ...)))`.

### 10. Data-drive `EventBreakListener.getStyle`

Replace the 160-line switch with a config file `dependent-blocks.yml` shipped with the plugin + overridable. Better: derive from block data types where possible (`BlockData instanceof Attachable`, etc.) and fall back to a list only for edge cases.

### 11. Bounded queue + backpressure policy

Swap `LinkedBlockingDeque` unbounded for `new LinkedBlockingDeque<>(capacity)` with `offer(entry, timeout)`. Define a policy for what happens when full: drop with counter, block caller (backpressures the tick), or write-through bypass. The current behavior (OOM if storage stalls) is the worst option.

### 12. Fix `EventInventoryListener.onInventoryDrag` missing `.save()`

Not a v2 thing; fix the extant bug.

### 13. Adventure-native chat path everywhere

`EventSayListener` is the one modern listener using `AsyncChatEvent` + `PlainTextComponentSerializer`. Most display-layer code still uses legacy `ChatColor`. Related to `05-commands-and-display.md`, not this doc, but worth noting: the event-capture layer here is already Paper-native; the downstream rendering drops back to Bungee Components.

### 14. First-class `source` modeling

`OEntry`'s `.source(Object)` is an `instanceof` staircase. Replace with a sealed `EventSource`:
```java
sealed interface EventSource permits PlayerSource, EntitySource, BlockSource, PluginSource, ConsoleSource, CommandBlockSource, EnvironmentSource { }
```
Every extractor returns a `DataEntry` whose source field is a sealed variant. `EntryWriter.toWrapper` pattern-matches on the variant to set `PLAYER_ID` or `CAUSE`. No more dual `instanceof` cascades.

### 15. Remove the dead `PermissionListener`

Zero behavior, zero registrations. Delete.

### 16. Collapse the `sg.getEventMapping` exception branch

Delete the 60-line anonymous-Map fallback at `sg.java:104-164`. Return the `ImmutableMap` directly or let the exception propagate.

## What v2 should keep

- **String-keyed event names.** They're the wire-format identity in Mongo documents and `/sg e:` parameter values. They've been stable across years of deploy. Replacing them with integer IDs or enum ordinals would break every existing record. Keep.
- **Per-event enable/disable from config.** `events.break.enabled` is a real knob admins use. Preserve the semantic even if the registration mechanism changes.
- **Past-tense display strings.** The `broke`/`placed`/`ignited` verbs make search output read like English. Keep the mapping, move it to records (`@Override public String pastTense() { return "broke"; }`).
- **The separation of logging and rollback.** `OEntry` writes; `DataEntry.rollback()` reads the same wrapper back. The round-trip through storage is the isolation boundary. Don't collapse it — the forensics use case (query historical state without being able to modify it) depends on rollback being an explicit operation on reconstructed records, not a shortcut.
- **The `ActionResult` / `SkipReason` contract.** Rollback steps can fail cleanly (`SkipReason.OCCUPIED`) or fatally (`ActionableException`). Both kinds compose into a batch result. Keep the model.
- **Async queue drain.** The concept is correct — writes should not block the Bukkit main thread. Just fix the latency (virtual threads), fix the flush (on disable), and add backpressure.
- **`InventoryUtil.identifyTransactions` as the inventory-diff abstraction.** `EventInventoryListener` and `EventShulkerListener` both defer to it. The diffing logic is genuinely nasty (see the comment in `EventInventoryListener.java:32-38`) and having a single source of truth for "what changed in this inventory click" is the right call. Keep and expand.
- **The `customWithLocation` / `custom` escape hatch** (renamed, typed). Not every event fits a typed builder verb, and the custom path is how 1.20+/1.21+ events got added without editing `OEntry`. In v2 that becomes a generic `DataEntry` record — but the principle of "typed path for common events, structured-wrapper path for long-tail" is right.
