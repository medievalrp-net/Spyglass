# Spyglass — gap report

Source-code comparison between v1 (`../v1`) and v2 (this repo). Written after reading every Java file in both repos plus the test suites and analysis docs. The intent is to catalog every concrete difference so downstream work can be planned against a stable reference.

The v1 → v2 data migration tool is **intentionally out of scope** and is not treated as a gap here, per operator decision. Its status is recorded in the "Working-tree state" section below only as a factual note, not as something to restore.

---

## 0. Top-line verdict

The refactor was a substantial architectural success. All five Tier-1 modernization items from [`docs/analysis/10-modernization-hotspots.md`](../../analysis/10-modernization-hotspots.md) landed:

1. Typed event records replace `DataWrapper`.
2. POJO codec replaces hand-rolled BSON.
3. Sealed `QueryPredicate` replaces ad-hoc `SearchCondition` interfaces.
4. Adventure `Component` replaces deprecated Bungee chat everywhere.
5. API/plugin boundary is now genuinely clean (`@ApiStatus.Internal` throughout plugin internals; `api/` module has a narrow public surface).

The gaps that remain fall into three buckets:

- **Working-tree hygiene** — a half-committed refactor wave left files deleted on disk but not committed.
- **Deliberately or accidentally narrowed scope** — several v1 listeners, param handlers, and flags have no v2 equivalent.
- **Small bugs and dead code** — wrong events hooked, undeclared permissions, debug logging left in, dead flags parsed but never consumed.

None of these is an architectural problem. All are fixable surface-level changes.

---

## 1. Working-tree state

`git status` on `main` shows v1.0.0 tagged, followed by 7 "Refactor wave" commits, followed by an **uncommitted delta** consisting of:

| Status | Files | Notes |
|--------|-------|-------|
| `D` | 8 [`plugin/migration/*.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/migration/) sources | `MigrationCommand`, `MigrationService`, `V1ToV2Translator`, `V1DocumentReader`, `V1ItemDecoder`, `V1Schema`, `WorldNameLookup`, `MigrationProgressStore` |
| `D` | 2 migration tests | `MigrationIT.java`, `V1ToV2TranslatorTest.java` |
| `??` | [`api/event/BlockUseRecord.java`](../../../api/src/main/java/net/medievalrp/spyglass/api/event/BlockUseRecord.java) | New record type, untracked |
| `M` | ~30 files | The refactor waves themselves, not yet in HEAD |

**Implications:**
- The README still says `sg admin migrate-v1` works ([`README.md:33`](../../../README.md)). It does not in the current working tree — acceptable per operator decision, but the README needs to match reality.
- `spyglass.admin` permission is absent from [`plugin.yml`](../../../plugin/src/main/resources/plugin.yml).
- The working tree is not shippable as-is: the uncommitted modifications need review and a commit, and the deleted migration files need a final decision (delete cleanly or revert — operator has chosen delete).

---

## 2. Architectural shift summary

| Dimension | v1 | v2 |
|---|---|---|
| Event modeling | `DataWrapper` (string-keyed tree) → `Document` (BSON) — two serialization layers | Sealed `EventRecord` records → POJO codec → BSON, one layer |
| Query DSL | `SearchCondition` + `FieldCondition` + `MatchRule` enum + `SearchConditionGroup` | Sealed `QueryPredicate` permits `Eq, In, Range, Exists, Not, And, Or` |
| Threading | Sync `EntryQueueRunner` polled every 20 ticks via `BukkitScheduler` | Virtual-thread `sg-drain` loop with batch drain (up to 512 per batch) |
| Chat rendering | `net.md_5.bungee.api.chat.*` (deprecated) | Kyori Adventure `Component`s |
| Config | `FileConfiguration` (YAML) with 40 stringly-typed fields on a singleton enum | HOCON via Configurate, parsed into a Java record, with auto-merge for new event keys |
| Storage | TTL index only; queries are full-collection scans | 4 compound indexes that match query shapes + TTL |
| Lifecycle | Five singletons (`v1.INSTANCE`, `sg.INSTANCE`, `sg.v1`, `sg.INSTANCE`, `v1.PLUGIN_INSTANCE`) | DI-style: every collaborator constructed in `SpyglassPlugin.onEnable` and passed by reference |
| Public surface | API and core both under `net.medievalrp.v1.api.*` (boundary leak — concrete `Parameter*`/`Flag*` live in Core but in the `api` package) | Strict split: `net.medievalrp.spyglass.api` vs `net.medievalrp.spyglass.plugin`; everything plugin-side is `@ApiStatus.Internal` |
| API discovery | Static `sg.getv1()` | `Bukkit.getServicesManager().load(SpyglassApi.class)` |
| Backends | Mongo + a stub `DynamoStorageHandler` (writes are no-ops, queries return null) | Mongo only |
| Command framework | Cloud, hand-built for each subcommand | Cloud, factored through services and a single `SpyglassCommands.register` |

---

## 3. API surface comparison

### v1 v1API (18 files)

```
api/ sg (static delegates)
api/data/ DataKey, DataKeys, DataWrapper, Transaction, LocationTransaction, InventoryTransaction
api/display/ DisplayHandler + 5 impls (Simple, Item, Message, Damage, Teleport)
api/entry/ ActionResult, Actionable, ActionableException, DataAggregateEntry, DataEntry, DataEntryComplete, EntryQueue, OEntry (god class), SkipReason
api/flag/ BaseFlagHandler, Flag (4 enum values), FlagHandler
api/interfaces/ Iv1 (30-method grab-bag), WorldEditHandler
api/parameter/ BaseParameterHandler, ParameterException, ParameterHandler, RecipientParameter, ServerParameter, WorldParameter
api/query/ FieldCondition, MatchRule, Query, QueryBuilder, QuerySession, SearchCondition, SearchConditionGroup
api/util/ DataHelper, DateUtil, Formatter, InventoryUtil, PastTenseWithEnabled, TypeUtil, reflection/ReflectionHandler
```

### v2 api (25 files)

```
api/ SpyglassApi (interface)
api/event/ EventRecord (sealed) permits 16 record types + EventCatalog + Origin + Source + RecordContext + StoredItem + BlockSnapshot
api/extension/ DisplayRenderer
api/param/ QueryParamHandler + ParamParseException
api/query/ Flag, QueryPredicate (sealed), QueryRequest, QueryResult, Sort
api/rollback/ Rollbackable, RollbackEffect (sealed), RollbackReason (sealed), RollbackResult (sealed)
api/util/ BlockLocation, Duration
```

### Concept-by-concept mapping

| v1 concept | v2 concept | Status |
|---|---|---|
| `sg` static singleton | `Bukkit.getServicesManager().load(SpyglassApi.class)` | ✓ Improved |
| `DataKey` / `DataKeys` / `DataWrapper` | typed records | ✓ Eliminated |
| `Transaction<T>` / `LocationTransaction<T>` / `InventoryTransaction<T>` | Per-record fields (`originalBlock`/`newBlock`, `beforeItem`/`afterItem`) | ✓ Eliminated |
| `OEntry` builder god class | Per-record static `of(ctx, ...)` factories | ✓ Replaced |
| `EntryQueue` (static `LinkedBlockingDeque`) | `Recorder` interface + `AsyncRecorder` impl | ✓ DI'd |
| `EntryQueueRunner` (`runTaskTimerAsynchronously` every 20 ticks) | Virtual-thread `sg-drain` loop with `poll(250ms)` | ✓ Better |
| `DataEntry` + `DataEntryComplete` + `DataAggregateEntry` polymorphism | `EventRecord` records + `QueryResult.RecordAggregation` | ✓ Cleaner |
| `Actionable` + `ActionResult` + `SkipReason` + `ActionableException` | `Rollbackable` + `RollbackResult` + `RollbackReason` (sealed) + `RollbackEffect` (sealed) | ✓ Richer (separates effect from execution) |
| `DisplayHandler` (5 concrete impls) | `DisplayRenderer` (default no-op methods) | ◐ Narrower — registered renderers are never consulted by `ResultRenderer` |
| `FlagHandler` interface + 8 concrete `Flag*` classes | `Flag` enum (5 values) + parsing inline in [`QueryStringParser:53-99`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/param/QueryStringParser.java) | ◐ Less extensible |
| `ParameterHandler` (with `processDefault` + `doesConflict`) | `QueryParamHandler` (just `parse` + `suggestions` + `suppressesDefaultRadius`) | ◐ Simpler but loses defaults extension point |
| `Query` / `QueryBuilder` / `QuerySession` / `SearchCondition` / `SearchConditionGroup` (with `Operator.AND/OR`) | `QueryRequest` + `QueryPredicate` (sealed `Eq/In/Range/Exists/Not/And/Or`) | ✓ Improved |
| `MatchRule` enum | Equivalents are record types in `QueryPredicate` (no explicit `Excludes` — use `Not(In(...))`) | ✓ Cleaner |
| `Iv1` (30 methods) | `SpyglassApi` (8 methods) | ✓ Narrowed |
| `WorldEditHandler` (interface, registered, never invoked) | Removed (dead) | ✓ Deleted |
| `DateUtil.parseTimeStringToDate` | `Duration.parse` (typed record) | ✓ Improved + tested |
| `DataHelper`, `Formatter`, `InventoryUtil`, `PastTenseWithEnabled`, `TypeUtil`, `ReflectionHandler` | Pushed into plugin module ([`util/*`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/util/)) where they belong | ✓ Boundary fix |
| `RecipientParameter` (`rcp:`/`recipient`) | **No equivalent in plugin's registered params** | ✗ Lost |
| `ServerParameter` (`server`/`srv`) | Removed (multi-server non-goal) | ✓ Intentional |

### Public-surface contract differences worth flagging

- **v2's `EventRecord` is a sealed interface that permits only 16 specific records** ([`EventRecord.java:7-23`](../../../api/src/main/java/net/medievalrp/spyglass/api/event/EventRecord.java)). External plugins that want to register their own custom event records **cannot** — the seal makes the type closed. v1's `OEntry.create().source(p).custom(eventName, wrapper).save()` API let consumers register arbitrary string event names. That extension point is gone, and there's no replacement. If a downstream MedievalRP plugin (Reserv, Cauldron, WhisperNet, VestaPersona, etc.) wants to write its own audit records into the same store, it has no v2 API path.
- **`registerDisplayRenderer(eventName, renderer)`** ([`SpyglassApi.java:25`](../../../api/src/main/java/net/medievalrp/spyglass/api/SpyglassApi.java)) is stored in `SpyglassApiImpl` but never read back in [`ResultRenderer.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/render/ResultRenderer.java). The whole per-event display customization contract is half-built.

---

## 4. Plugin internals

### Lifecycle

| | v1 | v2 |
|---|---|---|
| Plugin entry | `v1.java` → `sg` (access-modifier split) | [`SpyglassPlugin.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/SpyglassPlugin.java) (single class) |
| Config | `sg.INSTANCE.setup(FileConfiguration)` — 40 fields, silently defaults misspellings | `SpyglassConfig.load(plugin)` — HOCON, record-shaped, with auto-merge of new event keys |
| `onDisable` | **Empty** — pending writes lost on shutdown | Drains recorder with 5s timeout, logs `drained / dropped / remaining`, unregisters services and WE subscriber |
| Listener registration | Two paths: `sg.registerEventHandlers` + `sg.enableEvents` (per-event from config) | Single pass over `List<RecordingListener>`; a listener only registers if at least one of its `events()` is enabled |
| Plugin/platform integrations | `PluginInteractionListener` re-registers WE flag handler when WE plugin enables/disables at runtime | No equivalent — WE integration only attempted at `onEnable` |

### Storage layer

**v1** ([`MongoStorageHandler.java`](../../../../v1/the v1 core/src/main/java/net/medievalrp/v1/io/mongo/MongoStorageHandler.java)) created two indexes plus TTL:
- `{Location.X, Location.Z, Location.Y, Created}`
- `{Created, EventName}` — **wrong field name** (code wrote `Event`, index targeted `EventName`, so never used)

**v2** ([`IndexManager.java:13-25`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/storage/IndexManager.java)) creates four:
- `{source.playerId: 1, occurred: -1}` — "who did X" queries
- `{event: 1, occurred: -1}` — "show me all X events" queries
- `{location.worldId: 1, location.x, location.z, location.y, occurred: -1}` — radius / bounding-box queries
- `{expiresAt: 1}` TTL

The v2 indexes match the real query shapes. ✓

**However,** [`MongoRecordStore.query()`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/storage/MongoRecordStore.java) has a subtler issue. When the query has no `event` predicate, `candidateTypes` returns every distinct record class — and v2 then runs **one Mongo query per record type**, merges + re-sorts in Java, then truncates to `limit`. With 13 distinct record classes in [`EventCatalog`](../../../api/src/main/java/net/medievalrp/spyglass/api/event/EventCatalog.java), an unfiltered search hits Mongo 13 times and returns up to `13 × limit` rows before truncating. For `limit=1000`, that's potentially 13k rows pulled to render 1k. v1's single `aggregate` pipeline was less prone to this.

Other storage notes:
- [`PredicateToBson.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/storage/PredicateToBson.java) is a clean ~50-line translator covering the entire predicate sealed hierarchy. ✓
- [`RollbackEffectCodec.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/storage/RollbackEffectCodec.java) is a polymorphic discriminator codec for the sealed `RollbackEffect` with a back-compat fallback for documents written before the discriminator existed. Non-trivial Mongo glue done right.
- [`UndoStack.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/rollback/UndoStack.java) persists undo history to Mongo with a 24h TTL index. v1's `lastActionResults` was a `Map<UUID, List<ActionResult>>` in `sg` that died on every restart.

### Listener parity table

| v1 listener | v1 events | v2 listener(s) | Status |
|---|---|---|---|
| `EventBreakListener` | `break` | `BlockBreakListener` + `MultiBlockBreakListener` + `ContainerDropListener` + `BlockExplodeListener` + `EntityExplodeListener` | ✓ Split cleanly |
| `EventPlaceListener` | `place` | `BlockPlaceListener` + `BlockMultiPlaceListener` | ✓ |
| `EventDecayListener` | `decay` | `LeavesDecayListener` + `BlockFadeListener` | ✓ |
| `EventFormListener` | `form` | `BlockFormListener` | ✓ |
| `EventGrowListener` | `grow` | `BlockGrowListener` + `StructureGrowListener` | ✓ |
| `EventIgniteListener` | `ignite` | `BlockIgniteListener` | ◐ **No player-source metadata propagation** — v1 tagged ignited blocks so fire spread still attributed to the original arsonist; v2 only logs the single `BlockIgniteEvent` |
| `EventUseListener` | `use` | `BlockUseListener` (untracked, new file) | ◐ Different block-tag set, no CraftBook integration |
| `EventBookshelfListener` | `bookshelf-insert/remove` | `BookshelfListener` | ✓ Uses real `ChiseledBookshelf.getSlot(clickedPosition)` |
| `EventBrushListener` | `brush` | `BrushListener` + `DelayedInteractionTracker` | ✓ |
| `EventCrafterListener` | `craft` | `CrafterListener` (emits `crafter`) | ◐ **Event name changed from `craft` to `crafter`** |
| `EventDecoratedPotListener` | `pot-insert/remove` | `DecoratedPotListener` | ✓ |
| `EventSculkListener` | `sculk` (sensor/shrieker activation) | `SculkListener` (hooks `SculkBloomEvent`) | ✗ **Wrong event hooked** — `SculkBloomEvent` fires when a sculk catalyst grows around a death, not when a sensor is triggered by a player. Semantic regression. |
| `EventVaultListener` | `vault` | `VaultListener` + `DelayedInteractionTracker` | ✓ |
| `EventCommandListener` | `command` (player + console) | `CommandListener` | ✗ **Drops `ServerCommandEvent`** — v1 logged console-typed commands, v2 only logs player-typed |
| `EventSayListener` | `say` | `ChatListener` | ✓ |
| `EventDeathListener` | `death` | `EntityDeathListener` | ✓ + entity NBT via `Bukkit.getUnsafe().serializeEntity` |
| `EventHitListener` | `hit`, `shot` | `EntityDamageListener` | ✓ |
| `EventInteractAtEntity` | `named` | **Missing** | ✗ Lost — name-tag renaming events |
| `EventMountListener` | `mount`, `dismount` | `EntityMountListener` + `EntityDismountListener` | ✓ |
| `EventBundleListener` | `bundle-insert/extract` | `BundleTransactionListener` | ◐ Better diff via `BundleMeta` snapshot, but contains **debug logging left in production** ([`:70-85`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/modern/BundleTransactionListener.java)) |
| `EventContainerListener` | `open`, `close` (any container) | `ContainerInteractListener` | ◐ **Drops `close` for non-shulker containers** — v1 logged close for any `Container`/`DoubleChest`; v2 only fires close for `ShulkerBox` |
| `EventDropListener` | `drop` (player + entity + dispenser) | `ItemDropListener` + `ContainerDropListener` | ✗ **Drops `EntityDropItemEvent` and `BlockDispenseEvent`** — v1 logged dispenser items and non-player entity drops; v2 doesn't |
| `EventInventoryListener` | `withdraw`, `deposit`, `clone` | `ContainerTransactionListener` + `ContainerDragListener` | ◐ **Drops `clone` event entirely** (creative-mode middle-click) |
| `EventPickupListener` | `pickup` | `ItemPickupListener` | ✓ |
| `EventEntityItemListener` | `entity-deposit/withdraw` (item frame + armor stand) | `ArmorStandManipulateListener` | ✗ **Drops item-frame events** — v1 logged `PlayerInteractAtEntityEvent` on item frames; v2 only handles armor stands |
| `EventShulkerListener` | `shulker-open/close/deposit/withdraw` | `ContainerInteractListener` + `ShulkerTransactionListener` | ✓ |
| `EventJoinListener` | `join` | `JoinListener` | ✓ |
| `EventQuitListener` | `quit` | `QuitListener` | ✓ |
| `EventTeleportListener` | `teleport` | `TeleportListener` | ✓ |
| `WandInteractListener` | wand right-click queries | `tool/WandInteractListener` | ✓ Improved (PDC marker on wand item) |
| `CraftBookSignListener` | `useSign` | **Missing** | ✗ Lost — CraftBook sign right-click logging |
| `PluginInteractionListener` | mid-runtime WE enable/disable | **Missing** | ◐ Acceptable loss |
| `PermissionListener` | (registered nowhere in v1) | **Missing** | ✓ Cleaning out dead code |

### Multi-block break dependencies

v1's [`EventBreakListener.saveDependantBreaks`](../../../../v1/the v1 core/src/main/java/net/medievalrp/v1/listener/block/EventBreakListener.java) tracks a `DependantStyle` taxonomy: `WALL` (wall banners, wall torches, cocoa, ladders, wall signs, tripwire hooks), `BOTTOM` (poppies, wheat, torches, redstone dust, carpets, rails, pressure plates, stems, signs), `TALL` (doors, tall grass, large fern, sunflowers, peonies), `ALL` (levers, buttons). A break records companion breaks for every dependent block — a torch on the side of a wall gets its own break record when the wall is destroyed.

v2's [`MultiBlockBreakListener`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/block/MultiBlockBreakListener.java) only handles **bed + door + tall plant pairs**. A rollback of a wall break in v2 will leave torches, levers, pressure plates, signs, ladders, rails, carpets, and pressure plates floating in air — they'll either vanish from physics or stay as orphans with no break record.

### WorldEdit / FAWE

| | v1 | v2 |
|---|---|---|
| Vanilla WE subscriber | `WorldEditLogger.java` | [`WorldEditSubscriber.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/worldedit/WorldEditSubscriber.java) | ✓ |
| FAWE batch processor | `fawe/FaweBatchLogger.java` | [`worldedit/FaweBatchLogger.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/worldedit/FaweBatchLogger.java) | ✓ |
| FAWE tile-NBT capture | `fawe/FaweTileCapture.java` | Inlined into v2's `FaweBatchLogger.parseContainerItems` | ✓ |
| Batched FAWE rollback | `FAWERollbackHandler.java` (dead code in v1, never called) | Removed | ✓ |
| `-we` flag | `FlagWorldEditSel.java` (has a bug where `isIgnoredDefault` return value is discarded) | [`QueryStringParser.java:70-86`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/param/QueryStringParser.java) + [`WorldEditSelection.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/worldedit/WorldEditSelection.java) | ✓ Fixed |

### Commands

v1 `/v1` (aliases `/o`, `/sg`) subcommands: `search`, `rollback`, `restore`, `page`, `undo`, `tool`, `events`, `ai`. Plus utility command `/sgtele`.

v2 `/sg` (aliases `/sg`, `/o2`, `/spyglass`) subcommands: `search`, `rollback`, `restore`, `page`, `undo`, `tool`, `events`, `help`.

| Subcommand | v1 | v2 | Notes |
|---|---|---|---|
| `search` | ✓ | ✓ | |
| `rollback` | ✓ | ✓ | |
| `restore` | ✓ | ✓ | |
| `page` | ✓ | ✓ | |
| `undo` | ✓ (ephemeral in-memory) | ✓ (Mongo-persisted, 24h TTL) | v2 better |
| `tool` | ✓ (in-memory `Set<UUID>`) | ✓ (Mongo-persisted) | v2 better |
| `events` | ✓ | ✓ | |
| `ai` | ✓ (Vertex AI) | — | Intentionally dropped |
| `help` | inline in handler | dedicated service | v2 cleaner |
| `/sgtele` | ✓ utility for clickable teleport from results | — | **Lost — v2 search results don't have clickable teleport**. v2's click event runs another search instead. |

### Param handlers

| v1 alias | v1 class | v2 alias | v2 class | Status |
|---|---|---|---|---|
| `p`/(positional) | `PlayerParameter` | `p`, `player` | `PlayerParam` | ✓ |
| `r` | `RadiusParameter` | `r`, `radius` | `RadiusParam` | ✓ |
| `t`, `since` | `TimeParameter` | `t`, `since` | `TimeParam` | ✓ |
| `a` | `EventParameter` | `a`, `action`, `event` | `EventParam` | ✓ |
| `b` | `BlockParameter` | `b`, `block` | `BlockParam` | ✓ |
| `e` | `EntityParameter` | `e`, `entity` | `EntityParam` | ✓ |
| `w`, `world` | `WorldParameter` | `w`, `world` | `WorldParam` | ✓ |
| `n` | `ItemNameParameter` | `iname`, `itemname` | `ItemNameParam` | ✓ Better (Or-across-all-item-paths) |
| `d` | `ItemDescParameter` | `ilore`, `itemlore` | `ItemLoreParam` | ✓ Better |
| (none) | — | `ench`, `enchant`, `enchantment` | `EnchantParam` | ✓ New |
| `m` | `MessageParameter` | — | — | ✗ **Lost** — can't search chat/command records by message content |
| `c` | `CauseParameter` | — | — | ✗ **Lost** — can't search by cause string (useful for environment sources) |
| `i` | `ItemParameter` | — | — | ✗ **Lost** — can't search by item material directly |
| `cu` | `CustomItemParameter` (yes/no item meta) | — | — | ✗ Lost (low value) |
| `ip` | `IpParameter` | — | — | ✗ **Lost** — can't search by IP (v2 stores IP in `JoinRecord.address` but no param exposes it) |
| `trg` | `TargetParameter` | — | — | ✗ **Lost** — direct target-field search |
| `rcp`, `recipient` | `RecipientParameter` | — | — | ✗ **Lost** — can't search chats by recipient |
| `srv`, `server` | `ServerParameter` | — | — | ✓ Intentional drop |

### Flags

| v1 flag | v2 flag | Notes |
|---|---|---|
| `-g` | `-g`/`-global` | ✓ |
| `-ng` | `-ng`/`-nogroup` | ✓ |
| `-ex` (FlagExtended) | `-ex`/`-extended` | ◐ **v2 parses `Flag.EXTENDED` but nothing reads it** — the v1 behavior (per-result inline location line) isn't implemented in [`ResultRenderer.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/render/ResultRenderer.java). Dead flag. |
| `-drain` (FlagDrain) | — | ✗ `Flag.DRAIN` defined in enum but **never parsed or consumed** anywhere |
| `-nc` | `-nc`/`-nochat` | ✓ Used in `MongoRecordStore.buildFilter` |
| `-order=...` | `-ord=...`/`-order=...` | ✓ |
| `-nod=...` | — | ✗ **Lost** — v1 let users skip individual default params (`-nod=r,t`); v2 only has the binary `defaults.enabled` config switch |
| `-we` | `-we`/`-worldedit` | ✓ — but checks `spyglass.worldedit` permission **which isn't declared in [`plugin.yml`](../../../plugin/src/main/resources/plugin.yml)**. Will always fail for non-ops. |

---

## 5. What v2 does better

1. **Typed events end-to-end** — compiler enforces what was runtime hope.
2. **Adventure throughout** — no deprecated Bungee chat anywhere.
3. **`onDisable` actually flushes** — v1 lost pending writes on shutdown.
4. **Indexes match queries** — v1 had a typo'd index that was never used.
5. **Mongo POJO codec** — eliminates the `DataWrapper` ↔ `Document` round-trip that caused v1's `components=null` bug.
6. **Tool state and undo are persistent** — v1's were in-memory and died on restart.
7. **Wand uses PDC marker** — v1 considered every `REDSTONE_LAMP` a wand; v2 stamps a PDC key so a normal glowstone can't trigger searches.
8. **Inventory drag in container is actually persisted** — v1's `EventInventoryListener.onInventoryDrag` builds an `OEntry` and never calls `.save()`; every drag was silently dropped.
9. **Test coverage** — v1: 5 test files with commented-out bodies and no assertions. v2: 12 test files with real assertions plus a Testcontainers IT exercising Mongo round-trips and verifying index existence.
10. **Item field projections** — v2's `ItemSerialization.storedItem` extracts `name`, `lore`, `enchants` as plain text for indexable queries; v1 stored a serialized `ItemStack` and queries had to traverse `meta.display-name` which only worked for some serialization paths.
11. **Cleaner FAWE hook** — v2 uses explicit `(sy, ly, lz, lx)` loops; clearer than v1's chunk char-array index trick.
12. **Coverage gates + JaCoCo wired** via Block 13; v1 had no coverage tracking at all.

---

## 6. Gaps — features lost in v2

### Lost listeners (capture gaps)

| Feature | v1 source | Impact |
|---|---|---|
| Console command logging (`ServerCommandEvent`) | `EventCommandListener.onServerCommand` | Operator-issued console commands no longer audited |
| Item-frame interactions (`entity-deposit`/`entity-withdraw`) | `EventEntityItemListener` item-frame branch | Can't track items placed in or removed from item frames |
| Name-tag renaming (`named` event) | `EventInteractAtEntity` | Can't track who renamed which entity to what |
| Container `close` for non-shulkers | `EventContainerListener.onInventoryClose` | Can't tell when players closed chests/barrels |
| Dispenser drops (`BlockDispenseEvent`) | `EventDropListener.onBlockDispense` | Dispenser item output not logged |
| Non-player entity drops (`EntityDropItemEvent`) | `EventDropListener.onEntityDropItem` | Mobs dropping items don't create records |
| Creative-mode `clone` event | `EventInventoryListener` CLONE branch | Can't track creative middle-click item cloning |
| CraftBook sign-use (`useSign`) | `CraftBookSignListener` | CraftBook MC sign interactions not logged (only relevant if CraftBook is in use) |
| Multi-block break dependencies (beyond bed + door + tall plant) | `EventBreakListener.saveDependantBreaks` + `DependantStyle` taxonomy | Rollbacks of walls with torches / signs / levers / rails / carpets on them leave orphaned attachments with no records |
| Player-source ignite chains | `EventIgniteListener` + `player-source` Bukkit metadata | Fire spread no longer attributes to the original arsonist |
| Mid-runtime WE plugin enable/disable | `PluginInteractionListener` | Minor — only matters if WE plugin is reloaded after server start |

### Lost query parameters

| v1 alias | What it did | Why it matters |
|---|---|---|
| `c:` | Search by cause string | Useful for querying environment sources (`c:environment`, `c:creeper`, etc.) |
| `m:` | Search by chat message content | Can't grep what was said |
| `i:` | Search by item material (direct) | Forces operators to use `b:` and hope `target` matches |
| `trg:` | Direct target-field search | General-purpose fallback |
| `ip:` | Search by IP address | Alt-account / ban-evasion tracking. v2 stores IP in `JoinRecord.address` but no search path |
| `rcp:` | Search chat by recipient UUID | Can't answer "who did Alice say that to?" from search alone; recipients are on hover but not queryable |
| `cu:` | Search for items with/without custom meta | Low value; acceptable loss |

### Lost flags

| v1 flag | What it did | Status in v2 |
|---|---|---|
| `-drain` (Flag.DRAIN) | Marks session for drain mode | `Flag.DRAIN` enum value defined, never parsed, never consumed anywhere |
| `-nod=param1,param2` | Skip individual default params | No equivalent — v2 has only binary `defaults.enabled` |

### Lost UX

- **Clickable teleport from search results.** v1's [`SearchCallback.buildComponent`](../../../../v1/the v1 core/src/main/java/net/medievalrp/v1/command/async/SearchCallback.java) wires `ClickEvent.Action.RUN_COMMAND` to `/sgtele <world> <x> <y> <z>`, so clicking a search result teleports the operator to where the event happened. v2's [`ResultRenderer.line`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/render/ResultRenderer.java) runs another `/sg search` instead. No way to jump to a location from results.

### API extension points that weren't replaced

- **Custom event registration** — v1's `OEntry.create().source(p).custom(eventName, wrapper)` is gone. No v2 equivalent. Sister plugins (Reserv, Cauldron, WhisperNet, VestaPersona) that used this path have no way to register audit events against Spyglass.
- **DisplayRenderer consultation** — the `registerDisplayRenderer` API is live but `ResultRenderer` never reads the registered renderers.

---

## 7. Bugs and dead code

| Issue | File | Note |
|---|---|---|
| `SculkListener` hooks the wrong event | [`SculkListener.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/modern/SculkListener.java) | Hooks `SculkBloomEvent` (catalyst growth on death) instead of sensor/shrieker activation by player. Semantic regression. |
| `crafter` vs `craft` event name | [`CrafterListener.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/modern/CrafterListener.java), [`EventCatalog.java:64`](../../../api/src/main/java/net/medievalrp/spyglass/api/event/EventCatalog.java) | v1 used `"craft"`, v2 uses `"crafter"`. Inconsistency only matters if cross-version querying is wanted. |
| `spyglass.worldedit` permission undeclared | [`plugin.yml`](../../../plugin/src/main/resources/plugin.yml), checked in [`QueryStringParser:74`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/param/QueryStringParser.java) | Bukkit treats undeclared perms as default false. `-we` flag always fails for non-ops. |
| Bundle debug logging in production | [`BundleTransactionListener:70-85`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/modern/BundleTransactionListener.java) | `plugin.getLogger().info("bundle-click: ...")` spams logs on every inventory click |
| `Flag.DRAIN` is dead | [`Flag.java`](../../../api/src/main/java/net/medievalrp/spyglass/api/query/Flag.java), nowhere else | Defined in enum, never parsed, never consumed |
| `Flag.EXTENDED` is dead (behavior) | parsed in [`QueryStringParser:69`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/param/QueryStringParser.java), never read | v1's per-result inline location line isn't in v2's renderer |
| `Flag.GLOBAL` barely useful | parsed in [`QueryStringParser:65-66`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/param/QueryStringParser.java) | Used only as a local bool to suppress default radius; the enum value itself never read |
| `Messages.java` unused | [`command/render/Messages.java`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/render/Messages.java) | Full MiniMessage loader with `messages.conf` resolution, never instantiated anywhere. PLAN.md Block 12a (MiniMessage extraction) was prepared but not completed. |
| `registerDisplayRenderer` half-built | [`SpyglassApi.java:25`](../../../api/src/main/java/net/medievalrp/spyglass/api/SpyglassApi.java), [`SpyglassApiImpl.java:65-67`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/api/SpyglassApiImpl.java) | Public API accepts registrations but `ResultRenderer` never looks them up |
| `MongoRecordStore.query` per-type fan-out | [`MongoRecordStore.java:97-108`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/storage/MongoRecordStore.java) | Up to 13 Mongo queries for an unfiltered search |
| `isWorldEditInstalled` doesn't check enabled state | [`SpyglassPlugin:233`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/SpyglassPlugin.java) | Edge case: another plugin disabling WE on startup |
| `Source` record is unsealed with 7 always-null fields | [`Source.java`](../../../api/src/main/java/net/medievalrp/spyglass/api/event/Source.java) | Storage cost small; type is begging to be a sealed interface `Player | Entity | Plugin | Console | CommandBlock | Environment` |

### Not actually bugs

- `QueryStringParser:46` has `int overrideLimit = 0` meaning "use config default". Subtle but deliberate; documented by call sites.
- `RollbackEngine.applyAll` asserts `Bukkit.isPrimaryThread()` — correct, this is the right thread gate.
- `PageCache` 15-min TTL — different from v1 (no TTL) but not a bug.

---

## 8. Schema differences (wire format)

v1 Mongo documents:
```
Event: "break"
Created: ISODate(...)
Player: "uuid-string" // or:
Cause: "environment" // for non-player sources
Target: "STONE"
Location: { X: 10, Y: 64, Z: 20, World: "world-uuid-string" }
OriginalBlock: { MaterialType: "STONE", BlockData: "minecraft:stone", Inventory: {...} }
NewBlock: { MaterialType: "AIR", BlockData: "minecraft:air" }
Origin: "worldedit" | "fawe" // only on WE-sourced records
Recipient: "uuid-csv" // chat recipients
Expires: ISODate(...)
```

v2 Mongo documents (POJO codec):
```
_id: UUID (binary)
event: "break"
schemaVersion: 1
occurred: ISODate(...)
expiresAt: ISODate(...)
origin: { kind: "player", detail: null }
source: { kind: "player", playerId: UUID, playerName: "Alice", ... } // 8 fields, most null
location: { worldId: UUID, worldName: "world", x: 10, y: 64, z: 20 }
target: "STONE"
originalBlock: { material: "STONE", blockData: "minecraft:stone", containerItems: [], signFront: [], signBack: [], bannerPatterns: [], jukeboxRecord: null }
newBlock: { material: "AIR", blockData: "minecraft:air", ... }
```

Intentional differences:
- `_id` typed as UUID (binary) vs v1's implicit ObjectId
- camelCase vs UpperCamelCase
- `location` split into `worldId` (UUID) + `worldName` (string) — v1 only stored UUID
- `source` is a typed sub-doc with 8 fields (most null per kind)
- `originalBlock` / `newBlock` carry typed inventory/sign/banner/jukebox arrays up front
- `schemaVersion` field for future migrations

The shape is similar to v1 but not identical. The README's claim that it's "intentionally similar so the migration tool can read v1 collections" no longer applies, since migration is out of scope. Nothing downstream depends on cross-version schema compatibility.

---

## 9. Tests

| Area | v1 | v2 |
|---|---|---|
| Test files | 5 | 12 |
| Tests with real assertions | 1 (`DataKeyTest.testGetter_Setter` — trivial) | ~40 |
| Mongo IT | — | [`MongoRecordStoreIT`](../../../plugin/src/test/java/net/medievalrp/spyglass/plugin/storage/MongoRecordStoreIT.java) (Testcontainers, exercises round-trip + index existence + limit + item-field queries) |
| Service-layer tests | — | 8 files under [`command/service/`](../../../plugin/src/test/java/net/medievalrp/spyglass/plugin/command/service/) |
| Pipeline tests | — | [`AsyncRecorderTest`](../../../plugin/src/test/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorderTest.java) covers batching, drop-on-full, flush-on-shutdown |
| Predicate tests | — | [`PredicateToBsonTest`](../../../plugin/src/test/java/net/medievalrp/spyglass/plugin/storage/PredicateToBsonTest.java) covers the full sealed hierarchy |

Coverage gates per PLAN.md Block 13a: ≥90% on `api`, ≥80% on `plugin`. Enforced via JaCoCo `jacocoTestCoverageVerification`.

---

## 10. File-by-file v2 inventory (by role)

```
plugin/
├── SpyglassPlugin.java bootstrap, DI wiring, listener registration, WE hookup
├── api/
│ └── SpyglassApiImpl.java SpyglassApi impl, Mongo-backed
├── command/
│ ├── SpyglassCommands.java Cloud command registration (root + 8 subcommands × 4 aliases)
│ ├── SpyglassSuggestions.java tab completion
│ ├── PageCache.java paginated result cache with 15min TTL
│ ├── param/ 12 param handlers
│ ├── render/ Feedback.java (chat framing), Messages.java (unused MiniMessage loader), ResultRenderer.java
│ └── service/ business logic (8 services)
├── config/
│ └── SpyglassConfig.java HOCON config loader with auto-merge
├── listener/
│ ├── RecordingListener.java Listener + events()
│ ├── RecordingSupport.java RecordContext factories
│ ├── block/ break/place/multi-place/container-drop/multi-block-break
│ ├── chat/ chat + command
│ ├── container/ drag/interact/transaction
│ ├── entity/ death/hit/mount/dismount/armor-stand
│ ├── environment/ decay/fade/form/grow/ignite/structure-grow/explosions
│ ├── item/ drop/pickup
│ ├── modern/ 1.20+/1.21+ block interactions (bookshelf/pot/brush/sculk/bundle/crafter/vault/shulker)
│ └── player/ block-use/join/quit/teleport
├── pipeline/
│ ├── Recorder.java interface
│ └── AsyncRecorder.java virtual-thread drain
├── rollback/
│ ├── RollbackEngine.java applies RollbackEffect → Block / Entity / Container changes
│ └── UndoStack.java Mongo-persisted, 24h TTL
├── storage/
│ ├── MongoRecordStore.java typed POJO codec writes + reads
│ ├── IndexManager.java 4 compound indexes
│ ├── PredicateToBson.java sealed QueryPredicate → BSON filter
│ ├── RecordFields.java field-name constants
│ └── RollbackEffectCodec.java polymorphic codec for sealed RollbackEffect
├── util/
│ ├── BlockLocations.java Bukkit Location ↔ BlockLocation
│ ├── BlockSnapshots.java BlockState ↔ BlockSnapshot
│ ├── InventoryActions.java Bukkit InventoryAction → Direction (DEPOSIT/WITHDRAW) + amount
│ └── ItemSerialization.java ItemStack ↔ StoredItem (base64 + extracted name/lore/enchants)
└── worldedit/
    ├── WorldEditSubscriber.java @Subscribe on EditSessionEvent
    ├── WorldEditSelection.java -we flag support
    ├── FaweHook.java extent-chain walker to find ExtentBatchProcessorHolder
    └── FaweBatchLogger.java IBatchProcessor impl, processes per-chunk edits
```

Working tree also contains uncommitted deletes under `plugin/migration/` (see section 1) and an untracked `api/event/BlockUseRecord.java`.
