# v1 Dissection — 00: Overview

## Module layout

Two Gradle subprojects:

- **`v1API`** — the public face. Declares interfaces, data types, builders, and helper utilities that external plugins (and the v1 core itself) consume. Everything under `net.medievalrp.v1.api.*`. Ends up bundled inside the shaded core jar; published separately would be cleaner.
- **`the v1 core`** — the plugin. Contains the Paper plugin entry point, config loader, storage adapters, event listeners, commands, WorldEdit/FAWE integration. Everything under `net.medievalrp.v1.*` and the nested `net.medievalrp.v1.api.*` (yes — Core puts parameter/flag *implementations* under the `api` package root even though it's Core-side code; this is a historical mess that muddies the API/impl boundary).

## File census

Total: ~120 Java source files (Core + API), ~11,400 lines.

| Area | Files | Notes |
|---|---|---|
| Plugin bootstrap | `v1.java`, `OmniCore.java`, `OmniEventRegistrar.java`, `OmniConfig.java` | Entry + config |
| Data & keys | API: `DataKey`, `DataKeys`, `DataWrapper`, `LocationTransaction`, `InventoryTransaction`, `Transaction` | Core data plumbing |
| Entry types | API: `DataEntry`, `DataEntryComplete`, `DataAggregateEntry`, `OEntry`, `EntryQueue`, `ActionResult`, `Actionable`, `ActionableException`, `SkipReason`; Core: `BlockEntry`, `ContainerEntry`, `EntityEntry`, `EntryQueueRunner` | Event record model |
| Storage | `StorageHandler`, `RecordHandler` + `mongo/` + `dynamo/` impls | Two backends, Mongo primary |
| Listeners | `listener/block/` (12), `listener/item/` (7), `listener/entity/` (4), `listener/chat/` (2), `listener/player/` (3), misc (5) | ~33 listener classes |
| Query DSL | API: `FieldCondition`, `MatchRule`, `Query`, `QueryBuilder`, `QuerySession`, `SearchCondition`, `SearchConditionGroup` | Query engine |
| Parameters | API base + 4; Core: 12 parameter impls | `p:`, `r:`, `a:`, `t:`, `trg:`, `c:`, `b:`, `e:`, `i:`, `ii:`, `id:`, `ci:`, `w:`, `rcp:`, `srv:`, `m:` |
| Flags | API base + `Flag`; Core: 8 flag impls | `-g`, `-ng`, `-e`, `-d`, `-nc`, `-order`, `-id`, `-we` |
| Commands | `OmniCommands`, `AiHandler`, `PageStore`, async + result helpers | Cloud-based (post-migration) |
| Display | API: `DisplayHandler` + 5 impls | Pluggable result rendering |
| WorldEdit | `WorldEditLogger`, `FAWERollbackHandler`, `fawe/FaweBatchLogger`, `fawe/FaweHook`, `fawe/FaweTileCapture`, API: `FlagWorldEditSel` | Dual-path logging |
| Utilities | API: `DataHelper`, `DateUtil`, `Formatter`, `InventoryUtil`, `PastTenseWithEnabled`, `TypeUtil`, `reflection/ReflectionHandler` | Grab-bag helpers |
| External APIs | API: `OmniApi`, `Iv1`, `WorldEditHandler` | Public surface for other plugins |

## Data flow (one event, end to end)

```
  Bukkit event fires (e.g. BlockBreakEvent)
        │
        ▼
  OmniListener subclass (e.g. EventBreakListener.onBreak)
        │
        ▼
  OEntry.create().source(player).brokeBlock(transaction).save()
        │                                                  │
        │   builder eagerly populates a DataWrapper        │
        │   (ORIGINAL_BLOCK, LOCATION, TARGET, tile data…)│
        │                                                  ▼
        │                                    EntryQueue.submit(wrapper)
        │                                                  │
        │                        (thread-safe queue, drained async)
        │                                                  ▼
        │                                    EntryQueueRunner (every 20 ticks)
        │                                                  │
        │                                                  ▼
        │                                    StorageHandler.records().store(wrapper)
        │                                                  │
        │                                          ┌───────┴────────┐
        │                                          ▼                 ▼
        │                               MongoRecordHandler    DynamoRecordHandler
        │                                          │                 │
        │                                          ▼                 ▼
        │                                      Mongo doc         Dynamo item
        │
        ▼
  (later) /omni search triggers:
    SearchParameterHelper parses the raw string
      → ParameterHandlers each addCondition() onto a Query
      → FlagHandlers modify the session state
      → RecordHandler.query(session) runs the search
      → DataEntry[] returned, display handlers format them
      → SearchCallback emits Bungee Components to the player
```

## Storage schema (Mongo)

Collection: `DataEntry` inside database `v1` (configurable). Each document is a flat representation of a `DataWrapper`:

- `Event` — string event name (`"break"`, `"place"`, `"say"`, ...)
- `Created` — Date of the event
- `Expires` — TTL timestamp for Mongo's native TTL index (config: `storage.expireRecords`)
- `Player` — UUID string for player-sourced events (written as `PLAYER_ID` at build time)
- `Cause` — string for non-player events; mutated at read time to be the player *name* for Player entries
- `Target` — the display target (material name, message, etc.)
- `Location` — subdocument `{X, Y, Z, World}`
- `OriginalBlock`, `NewBlock` — subdocuments with `MaterialType`, `BlockData`, optional `Inventory`, `SignText`, `BannerPatterns`, `Record`
- `Inventory` — `{slot → ItemStackSerialized}` nested on block states for containers
- `Origin` — recent addition: `"worldedit"` / `"fawe"` tag
- `Recipient` — comma-separated UUID list for chat events
- plus event-specific keys (`DamageCause`, `EntityType`, `Message`, `IpAddress`, etc.)

One index: TTL on `Expires`. (Query performance across common predicates is *not* indexed beyond this — queries are full-collection scans with filters.)

## Build system

- Gradle 9.4.1, toolchain JDK 21
- Root: allprojects groupId `net.medievalrp`, version `V0.1-Alpha` (post–this-session)
- Shadowing: `shadow 9.0.0-beta12`; includes `:v1API` + `commons-lang3` into the final Core jar
- plugin.yml token expansion via `processResources` + `${version}` substitution
- compileOnly deps: mongo driver, paper-api, worldedit-bukkit, cloud-paper, local FAWE jar (see `the v1 core/libs/`, gitignored)

## External integrations

- **MongoDB** — primary storage. Connection string from config, one shared client pool.
- **DynamoDB** — secondary storage, AWS. Present but less maintained; consumers should pick one.
- **WorldEdit** — `@Subscribe` to `EditSessionEvent` at `BEFORE_CHANGE` stage; wrap the extent with `LoggingExtent`. Captures inventories eagerly before `super.setBlock()` to preserve tile entity data.
- **FastAsyncWorldEdit** — `IBatchProcessor` registered per-EditSession via `ExtentBatchProcessorHolder.addProcessor`. Runs on FAWE worker threads. Survives `fast-placement: true`. Requires whitelist entry in FAWE's `extent.allowed-plugins`.
- **CraftBook** — optional sign interaction hooks via `CraftBookSignListener`.
- **Cloud (Incendo)** — modern command framework, Brigadier-native tab completion. Post–this-session migration.
- **Vertex AI** — optional natural-language query assistant via `AiHandler`, config-gated.

## Architectural contradictions worth noting up front

1. **API/impl boundary leak.** The API module declares `DisplayHandler`, `FlagHandler`, `ParameterHandler` *interfaces*, but the v1 core puts concrete `Parameter*` and `Flag*` classes under `net.medievalrp.v1.api.parameter.*` / `.flag.*` — same package root as the API. A consumer plugin that imports v1API gets the interfaces only, but the package layout pretends otherwise.
2. **DataWrapper vs Mongo Document.** Every event is re-serialized through a custom tree (`DataWrapper`) that is then flattened into a BSON `Document`. Two serialization layers where one would do; also the source of the `components=null` bug we hit earlier.
3. **EventBuilder's `wrapper` is protected.** Builders like `brokeBlock()` write into `this.wrapper`, which means all customization has to happen through `.with()` added this session. The old model couldn't extend entry types without modifying `OEntry`.
4. **Dual NBT worlds.** FAWE uses both jnbt `CompoundTag` (old) and `FaweCompoundTag`/LinBus (new). Core uses Bukkit ConfigurationSerialization. WE adapter bridges between them. Three flavors of NBT in one plugin.
5. **`net.md_5.bungee` chat Components throughout.** Legacy API. Paper is Adventure-native. Everything in `SearchCallback`, `Formatter`, and display handlers uses the deprecated Spigot path.
6. **Two rollback engines.** `BlockEntry.rollback()` replays per-block via Bukkit. `FAWERollbackHandler.batchRollback()` exists but is uncalled dead code. Deployment has one path alive.
7. **`OmniCore.onEnable`** has dead branches — two empty `if` blocks for `fastAsyncWorldEdit`/`worldEdit` integration that were meant to do something and don't.

## File map for subsequent docs

- `01-core-lifecycle.md` — `v1.java`, `OmniCore.java`, `OmniEventRegistrar.java`, `OmniConfig.java`
- `02-data-and-storage.md` — API: `data/*`; Core: `io/*`
- `03-events-and-entries.md` — API: `entry/*`; Core: `listener/**/*`, `EntryQueueRunner`
- `04-query-dsl.md` — API: `query/*`, `parameter/*`, `flag/*`; Core: `parameter/*`, `flag/*`, `command/util/SearchParameterHelper`
- `05-commands-and-display.md` — Core: `command/*`, `AiHandler`; API: `display/*`
- `06-rollback.md` — API: `Actionable`, `ActionResult`, `SkipReason`; Core: `BlockEntry.rollback/restore`, rollback glue in `OmniCommands`
- `07-worldedit-integration.md` — Core: `worldedit/*`, `worldedit/fawe/*`; API: `FlagWorldEditSel`, `interfaces/WorldEditHandler`
- `08-api-surface.md` — API: `OmniApi`, `interfaces/Iv1`; the effective consumer contract
- `09-utilities-and-gaps.md` — API: `util/*`; dead code, TODOs, test coverage state
- `10-modernization-hotspots.md` — rollup: ranked list of patterns that most deserve redesign in a v2 rewrite
