# Phase 2 — what landed, what didn't

Tagged as `v0.9.0`. Production-ready for most of the v1 feature set; see the "Deferred to Phase 3" section for the gaps.

## Commit chain

```
Phase 2 block 0        — tooling (JaCoCo, CI, deployToRpServer, editorconfig)
Phase 2 block 1        — command layer refactor (services + thin OmniCommands)
Phase 2 block 2        — regression harness (mongo seed + RCON matrix)
Phase 2 block 3.1      — environment events (decay/form/grow/ignite)
Phase 2 blocks 3.2+3.3 — explosions + multi-place + container Rollbackable
Phase 2 block 3.4      — drop / pickup / teleport
Phase 2 block 3.5      — entity events (death/hit/shot/mount/dismount, no NBT rollback)
Phase 2 blocks 3.6+3.8 — armor-stand manipulate + richer container clicks
Phase 2 block 4        — vanilla WorldEdit integration (FAWE deferred)
Phase 2 block 5 partial — tab completion via Cloud BlockingSuggestionProvider
```

## What's shipped

### 11 event record types
`BlockBreakRecord`, `BlockPlaceRecord`, `ChatRecord`, `CommandRecord`, `JoinRecord`, `QuitRecord`, `ContainerDepositRecord` (Rollbackable), `ContainerWithdrawRecord` (Rollbackable), `ItemDropRecord`, `ItemPickupRecord`, `TeleportRecord`, `EntityDeathRecord`, `EntityHitRecord`, `EntityMountRecord`.

### 22 event names
`break`, `place`, `say`, `command`, `join`, `quit`, `deposit`, `withdraw`, `decay`, `form`, `grow`, `ignite`, `drop`, `pickup`, `teleport`, `death`, `hit`, `shot`, `mount`, `dismount`, `entity-deposit`, `entity-withdraw`.

### 20 extractors covering
- Player block break + place + multi-place
- Environment decay (leaves + fade), form, grow + structure-grow, ignite
- TNT / entity explosion cascade
- Container deposit / withdraw with PLACE_*, PICKUP_*, SWAP_WITH_CURSOR, MOVE_TO_OTHER_INVENTORY
- Armor-stand manipulation (reuses container records with `containerType=ARMOR_STAND`)
- Item drop / pickup (player + non-player pickup like allays)
- Teleport (filters cause=UNKNOWN)
- Entity death (player + environment killers)
- Entity damage (splits hit vs shot by projectile instance; resolves projectile shooter)
- Entity mount + dismount
- Player join / quit, chat (AsyncChatEvent), command preprocess
- Vanilla WorldEdit via EditSessionEvent + LoggingExtent subscriber

### Commands
`/omniv2 help`, `/omniv2 events`, `/omniv2 search <params>`, `/omniv2 rollback <params>`, `/omniv2 restore <params>`, `/omniv2 undo`, `/omniv2 page <n>`, `/omniv2 tool` (placeholder), aliases `/o2` and `/spyglass`.

Command registration consolidated to `OmniCommands.register()` — a single method where every handler is a one-liner delegate. Business logic lives in named services under `command/service/`.

Tab completion wired via Cloud's `BlockingSuggestionProvider`, consulting each registered `QueryParamHandler.suggestions(...)`.

### Rollback
`BlockBreakRecord`, `BlockPlaceRecord`, `ContainerDepositRecord`, `ContainerWithdrawRecord` all implement `Rollbackable`. `RollbackService` parses params, forces `NO_GROUP`, queries async, then applies effects on the main thread via `Bukkit.getScheduler().runTask`. Summary messages deliver correctly to in-game players. Undo persisted per-player in Mongo with 24h TTL.

### Storage
Mongo POJO codec + per-record-type narrowing on queries so overlapping-schema types don't double-decode. Indexes created up front:
- `{ source.playerId: 1, occurred: -1 }`
- `{ event: 1, occurred: -1 }`
- `{ location.worldId: 1, location.x: 1, location.z: 1, location.y: 1, occurred: -1 }`
- `{ expiresAt: 1 }` (TTL, `expireAfterSeconds=0`)

### Config
Auto-merge: new event defaults bundled in the jar's `config.conf` are auto-added to existing on-disk configs on upgrade.

### Regression harness
`./gradlew regression` seeds both `v1.DataEntry` (v1) and `Spyglass.EventRecords` (v2) via pymongo with deterministic test data, then runs the query matrix in `regression/cases.json` through RCON. Current matrix: 21 cases, all green. Report lands at `plugin/build/reports/regression/report.json`.

### Unit tests
30 plugin-side test methods (service layer + predicate + recorder) + 4 API-side (Duration). All green.

## Deferred to Phase 3 (in priority order)

1. **FAWE fast-placement capture.** Vanilla WorldEdit works; FAWE edits that bypass the extent chain need an `IBatchProcessor` installed on `ExtentBatchProcessorHolder`. Same approach as v1's `FaweHook` but written clean-room.
2. **Entity NBT rollback.** `EntityDeathRecord` captures enough data for rollback but doesn't implement `Rollbackable`. Needs `Entity.saveAsTag()` + Paper's entity spawn restore path, marked `@ApiStatus.Experimental` because NBT schema drift across MC versions is known-brittle.
3. **v1 → v2 migration tool.** `/omniv2 admin migrate-v1` console command reading `v1.DataEntry` + translating to v2 records. Essential for server operators upgrading from v1.
4. **Inspection wand** (`/omniv2 tool` proper implementation). Toggle persists in Mongo; right-click a block runs a location-scoped search.
5. **1.20+/1.21+ block interactions:** bookshelf, pot, brush, sculk, crafter, vault, shulker, bundle. Most fit the container-transaction shape; refactor `ContainerTransactionExtractor` to accept broader `InventoryHolder` types.
6. **`-we` query flag.** Turn the caller's WorldEdit selection into a location predicate.
7. **Tighten container extractor:** HOTBAR_SWAP, HOTBAR_MOVE_AND_READD, COLLECT_TO_CURSOR, InventoryDragEvent (multi-slot diffing).
8. **MiniMessage template extraction.** `ResultRenderer` still builds Components inline; `Messages` MiniMessage loader is loaded but unused.
9. **`@ApiStatus.Internal`** on plugin internals to tighten the public API surface (Phase 1 widened several classes to `public` for cross-package wiring).
10. **Coverage gates** in `./gradlew build`: JaCoCo thresholds (≥80% plugin, ≥90% api) + static analysis (SpotBugs/Error Prone).
11. **Mineflayer-driven live-event regression.** Adds coverage for event extractors, not just storage/query/render.
12. **MongoRecordStoreIT** (Testcontainers-based integration test).

## Known limitations

- RCON synchronous-reply model can't deliver async query results inline. Search via RCON shows "Searching..."; results land in the page cache and are retrieved via `/omniv2 page 1`. In-game chat is fine (messages deliver as they arrive).
- v1's DataEntry collection has a historic typo (`EventName` vs `Event`) in one index; v1 regression counts may lag reality. Documented in `docs/analysis/02-data-and-storage.md`.
- Environment extractors for ignite/form/grow/decay set source to `EnvironmentSource` with a description string. Search by source for env events uses the description, not a player UUID.

## Verification story

Every Phase 2 block ran the regression suite after committing. Commit messages state counts (`regression: N/N green`). The harness comfortably caught the per-type query dedup bug introduced between Block 3.1 and 3.3 during development (fixed in-commit).
