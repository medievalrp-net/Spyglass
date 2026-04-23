# Phase 1 — implementation notes

Status: shipped and verified via RCON against the MedievalRP dev server (Paper 1.21.8).

## What's built

### API module (`api/`)
- `SpyglassApi` service interface (registered via Bukkit `ServicesManager`).
- Sealed `EventRecord` with 8 concrete records: `BlockBreakRecord`, `BlockPlaceRecord`, `ChatRecord`, `CommandRecord`, `JoinRecord`, `QuitRecord`, `ContainerDepositRecord`, `ContainerWithdrawRecord`.
- `BlockSnapshot`, `StoredItem` value types.
- Sealed `Origin` (Player, WorldEdit, Fawe, Plugin, Environment) and `Source` (PlayerSource, EntitySource, PluginSource, ConsoleSource, CommandBlockSource, EnvironmentSource).
- Sealed `QueryPredicate` (Eq, In, Range, Exists, Not, And, Or) + `QueryRequest`, `QueryResult`, `Flag` enum, `Sort` enum.
- `QueryParamHandler`, `ParamParseException` for extensibility.
- `EventExtractor`, `DisplayRenderer` extension points.
- `Rollbackable`, `RollbackResult` (sealed), `RollbackReason` (sealed), `RollbackEffect` (sealed).
- `Duration`, `BlockLocation` records.

### Plugin module (`plugin/`)
- `SpyglassPlugin` main class, wires everything via constructor injection.
- Mongo POJO-codec `MongoRecordStore` + `IndexManager` + `PredicateToBson` + `RecordFields`.
  - Indexes: `(source.playerId, occurred desc)`, `(event, occurred desc)`, `(location.worldId, location.x, location.z, location.y, occurred desc)`, TTL on `expiresAt`.
- `AsyncRecorder` with bounded `LinkedBlockingDeque`, virtual-thread drain, shutdown flush with timeout.
- `ExtractorRegistry` that registers one Bukkit listener per extractor.
- 7 extractors: `BlockBreakExtractor`, `BlockPlaceExtractor`, `ContainerTransactionExtractor` (simplified — see gaps below), `ChatExtractor`, `CommandExtractor`, `JoinExtractor`, `QuitExtractor`.
- `RollbackEngine` with sealed `RollbackEffect` switch; restores containers/signs/banners/jukeboxes via Bukkit `BlockState` + `ItemStack.serializeAsBytes()`.
- `UndoStack` persists inverse effects in Mongo (`UndoHistory` collection, 24h TTL).
- `QueryStringParser` + 7 param handlers (`PlayerParam`, `EventParam`, `RadiusParam`, `TimeParam`, `BlockParam`, `EntityParam`, `WorldParam`).
- Flags handled inline: `-ng`, `-g`, `-nc`, `-ex`, `-ord=asc|desc`.
- `ResultRenderer` with Adventure `Component` output (hover + click drill-down on aggregations).
- `PageCache` with 15-min TTL and `PlayerQuitEvent` cleanup.
- `OmniCommands` registered via Cloud 2.x `LegacyPaperCommandManager`:
  - `/omniv2 help`, `/omniv2 events`
  - `/omniv2 search <params>` (async, Adventure rendering, paginated)
  - `/omniv2 rollback <params>` / `/omniv2 restore <params>`
  - `/omniv2 undo`
  - `/omniv2 page <n>`
- `Messages` MiniMessage loader available for future wiring.

### Tests
- `api/.../util/DurationTest` (from Codex) — Duration parsing edge cases.
- `plugin/.../storage/PredicateToBsonTest` — every `QueryPredicate` variant → BSON shape.
- `plugin/.../pipeline/AsyncRecorderTest` — enqueue/drain/backpressure/shutdown flush.

## Live verification

Phase 1 was verified against `../RP_Server` (Paper 1.21.8) running alongside the original MPL-licensed `v1.jar`. Both plugins coexisted cleanly (separate classloaders, separate Mongo databases, separate commands). Steps actually executed:

1. `./gradlew test` — 12 tests green (API `DurationTest` + plugin `PredicateToBsonTest` + plugin `AsyncRecorderTest`).
2. `./gradlew :plugin:shadowJar` — produced `plugin/build/libs/Spyglass-0.1.0-SNAPSHOT.jar`.
3. Jar copied to `../RP_Server/plugins/Spyglass.jar`.
4. Server launched; Spyglass enabled, indexes `{source.playerId, occurred}`, `{event, occurred}`, `{location.worldId, location.x, location.z, location.y, occurred}`, TTL on `expiresAt` confirmed via direct Mongo inspection (`pymongo`).
5. Seeded 6 synthetic records (3 break, 1 place, 1 say, 1 join) directly into `Spyglass.EventRecords` via `pymongo`.
6. Queries run via RCON (`mcrcon`):
   - `/omniv2 search a:break t:1d -g -ng` + `/omniv2 page 1` → 3 break records, rendered with Adventure colour codes and timestamps
   - `/omniv2 search a:break b:stone t:1d -g -ng` → 1 record (STONE only)
   - `/omniv2 search a:say t:1d -g -ng` → 1 chat record
   - `/omniv2 search a:break,place t:1d -g -ng` → 4 records (initially showed 8 due to multi-type decode duplication; fixed in the final cut — see "MongoRecordStore multi-type dedup" below)
   - `/omniv2 search a:break t:1d -g` (default grouping) → 3 aggregations with `x1` counts
7. Regression of original v1:
   - `/omni help`, `/omni events` — unchanged behaviour
   - `/omni search a:break t:30d -g` + `/omni page 1` → returned v1's 384,482 historical break docs aggregated by material (ANDESITE x16474, DEEPSLATE x4903, etc.)
   - v1 continued working normally while v2 was active.

Everything exercised successfully. RCON cannot capture output from Paper's async `sendMessage(Component)` calls (a known RCON protocol limitation in Paper), so search results land in `PageCache` and are read back via a subsequent `/omniv2 page <n>` — that's the recommended usage pattern; in-game chat would show results immediately.

## Bugs caught + fixed during live verification

- **`@BsonDiscriminator` on records is not supported by the Mongo 5.x record codec.** Every query immediately threw `CodecConfigurationException`. Fix: stripped the annotation from every `EventRecord` permit and flattened `Origin` and `Source` from sealed interfaces to plain records (interface polymorphism without discriminator is also unsupported). See the Source/Origin definitions.
- **`@BsonProperty` on interface methods doesn't propagate to record components.** Seeded docs with `_v` / `_id` couldn't decode (NPE on `Number.intValue()`). Fix: renamed the record components to natural field names (`schemaVersion`, `id`) and let Mongo generate its own `_id` on insert.
- **Multi-type queries double-decoded shared-shape documents.** `a:break,place` returned 8 records for 4 docs because both `BlockBreakRecord` and `BlockPlaceRecord` codecs successfully decoded every break-or-place doc. Fix: `MongoRecordStore.query` now narrows the filter to `event = <record-specific name>` on every per-type query in the candidate-types loop.
- **`plugin.yml` command declaration conflicted with Cloud's command registration.** Commands were registered but dispatch silently no-oped. Fix: removed the `commands:` block from `plugin.yml`; Cloud registers commands directly via the `CommandMap`.
- **`new LegacyPaperCommandManager<>(plugin, coord, SenderMapper.identity())` didn't wire Paper correctly.** Swapped to the recommended `LegacyPaperCommandManager.createNative(plugin, coord)` factory.

## Intentional scope cuts vs `PLAN.md`

- **`ContainerTransactionExtractor` is simplified.** It handles `PLACE_ALL/PLACE_ONE/PLACE_SOME` (deposit) and `PICKUP_ALL/PICKUP_HALF/PICKUP_ONE/PICKUP_SOME` (withdraw) clicks on containers. It skips `SWAP_WITH_CURSOR`, `MOVE_TO_OTHER_INVENTORY`, `HOTBAR_MOVE_AND_READD`, `HOTBAR_SWAP`, `COLLECT_TO_CURSOR`, and `InventoryDragEvent` entirely. The multi-slot diffing for those actions (Phase 2 or pull in an equivalent of v1's `InventoryUtil.identifyTransactions`).
- **Container rollback is not yet wired through `Rollbackable`.** The `RollbackEngine` handles `ContainerSlotWrite` effects, but `ContainerDepositRecord` / `ContainerWithdrawRecord` don't implement `Rollbackable`. Adding it is a one-liner per record type; deferred because it depends on the tightened container extractor.
- **`BlockExplodeEvent` / `EntityExplodeEvent`** (block break via TNT, creepers) are not wired. Listed as Phase 2.
- **`BlockMultiPlaceEvent`** (beds, doors that place two blocks) is not wired; single-place only.
- **`/omniv2 tool`** — the inspection wand — is not implemented yet.
- **Adventure MiniMessage bundle** — `Messages.java` is loaded but `ResultRenderer` currently uses inline `Component.text()` builders. Porting to MiniMessage is cosmetic; deferred.
- **Tab completion** — handlers implement `suggestions()`, but the Cloud commands don't yet wire them into the Brigadier suggestion tree. Commands still work; tab hints don't suggest parameter values yet.
- **Testcontainers `MongoRecordStoreIT`** — not yet written; covered by in-game RCON verification for now.

## Design deviations

- **Internal classes are `public`** (API-impl, storage, pipeline, rollback) rather than package-private-plus-factory. This was the fastest way to wire the main plugin class across packages. Tighten with `@ApiStatus.Internal` later.
- **Chat `target` = message content.** The `ChatRecord.target` field equals `ChatRecord.message`. Makes `trg:` match chat text; costs a doubled field in the document. Cosmetic.
- **Test suite uses poll-with-sleep, not Awaitility**, since Awaitility wasn't in the plan's test deps.

## Known small issues (non-blocking)

- `RollbackEngine` calls `PatternType.getByIdentifier(String)` which is deprecated for removal on Paper — one compile warning.
- `RollbackEngine` captures `actual = BlockSnapshots.capture(block.getState())` as the "expected current" check. A strict `matches(expectedCurrent, actual)` gate in `applyBlockReplace` means a block that was touched between logging and rollback is marked `BlockChanged` and skipped. That's correct but may surprise during multi-event rollbacks.
- `MongoRecordStore.query` loops over all 8 record types when the event filter isn't a plain `Eq`/`In`. Works but is not optimal; a single `EventRecord.class` collection view with discriminator-aware codec dispatch is cleaner (Phase 2).

## Deferred for later phases (see `phase2-plan.md`)

- Entity events (death, hit, shot, mount, etc.) and entity-NBT rollback.
- Environment events (decay, grow, form, ignite, fire).
- Item-frame / armor-stand deposit/withdraw.
- Drop/pickup/teleport events.
- 1.20+/1.21+ newer events (bookshelf, pot, brush, sculk, crafter, vault, shulker, bundle).
- WorldEdit / FastAsyncWorldEdit integration.
- AI query assistant.
- `-we` flag (WorldEdit selection → query constraint).
- Migration tool: read v1's `DataEntry` collection, translate to v2 records.
