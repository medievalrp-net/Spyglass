# Phase 2 — plan

Priority-ordered list of work deferred from Phase 1. Each item is self-contained; pick any.

## Tier A — feature parity with v1

### A1. Entity events + entity rollback
- Records: `EntityDeathRecord`, `EntityHitRecord`, `EntityShotRecord`, `EntityMountRecord`, `EntityDismountRecord`, `EntityTameRecord`.
- Extractors for `EntityDeathEvent`, `EntityDamageByEntityEvent`, `EntityMountEvent`, etc.
- Rollback: re-spawn killed entities from serialized NBT. Use Paper's `World.spawnEntity(Location, EntityType, EntitySpawnCallback)` or reflective NBT load. Mark unsafe via `@ApiStatus.Experimental` — NBT schema drift across MC versions is known-broken territory.

### A2. Environment block events
- `decay` (`LeavesDecayEvent`, `BlockFadeEvent`), `form` (`BlockFormEvent`), `grow` (`BlockGrowEvent`, `StructureGrowEvent`), `ignite` (`BlockIgniteEvent`).
- Source is `EnvironmentSource`, not `PlayerSource` — the `Source` sealed type already permits this.
- Rollback: reuse `BlockBreakRecord`/`BlockPlaceRecord` machinery; effects are identical shape.

### A3. Explosion events
- `BlockExplodeEvent` and `EntityExplodeEvent` cascade into many `BlockBreakRecord`s. Need a cascade extractor that returns a stream of records, one per affected block. The `EventExtractor<E, R>` contract already supports this.
- Attribute the source: ignition owner (TNT placer, creeper target) if trackable via existing `ignite` record.

### A4. `BlockMultiPlaceEvent` (beds, doors)
- Return two `BlockPlaceRecord`s, one per affected block.

### A5. Item-frame / armor-stand deposit/withdraw
- Records: `ItemFrameDepositRecord`, `ItemFrameWithdrawRecord`, `ArmorStandDepositRecord`, `ArmorStandWithdrawRecord` (or a single `EntityItemDepositRecord`/`Withdraw` with an `entityType` field).
- Sources: `PlayerArmorStandManipulateEvent`, `PlayerInteractEntityEvent`.

### A6. Drop + pickup + teleport
- `ItemDropRecord`, `ItemPickupRecord`, `TeleportRecord`.
- Sources: `PlayerDropItemEvent`/`EntityDropItemEvent`/`BlockDispenseEvent`, `EntityPickupItemEvent`, `PlayerTeleportEvent`.

### A7. 1.20/1.21+ block interactions
- `bookshelf-insert`/`bookshelf-remove` (chiseled bookshelves)
- `pot-insert`/`pot-remove` (decorated pots)
- `brush` (suspicious sand/gravel, delayed verification)
- `sculk` (sculk sensor/shrieker triggers)
- `crafter` (crafter block crafted)
- `vault` (vault unlock w/ trial key)
- `shulker-*` (shulker inventory open/close/deposit/withdraw)
- `bundle-insert`/`bundle-extract`
- Most share the `ContainerTransactionExtractor` shape — refactor to accept a broader set of inventory holders.

### A8. Tighten `ContainerTransactionExtractor`
- Handle `SWAP_WITH_CURSOR`, `MOVE_TO_OTHER_INVENTORY`, `HOTBAR_*`, `COLLECT_TO_CURSOR`.
- Handle `InventoryDragEvent` (multi-slot drop).
- The v1 implementation's `InventoryUtil.identifyTransactions` switch is 437 lines and load-bearing. Port it clean-room: read `docs/analysis/09-utilities-and-gaps.md` for the pattern, write new code from scratch.
- Make `ContainerDepositRecord`/`ContainerWithdrawRecord` implement `Rollbackable` (produces `RollbackEffect.ContainerSlotWrite`).

## Tier B — infrastructure

### B1. WorldEdit / FastAsyncWorldEdit integration
- Add FAWE jar as a Maven dep (see `docs/analysis/07-worldedit-integration.md` § modernization #5).
- Vanilla WE: subscribe to `EditSessionEvent.BEFORE_CHANGE`, install `LoggingExtent`.
- FAWE: walk the extent chain for `ExtentBatchProcessorHolder`, register `IBatchProcessor`.
- Set `Origin.WorldEdit` / `Origin.Fawe` on emitted records (already modeled on API).
- `-we` query flag: build a `QueryPredicate` group from the invoker's WE selection.
- Batched rollback path: `FaweBatchRollback` that opens one `EditSession` per world for rollbacks above a threshold (~500 effects). Writes via FAWE are order-of-magnitude faster than per-block Bukkit.

### B2. AI query assistant
- Port the Vertex AI integration from v1 (`docs/analysis/05-commands-and-display.md` § AiHandler).
- Virtual thread for the HTTP call (no `BukkitRunnable` nesting).
- System prompt from `plugin/src/main/resources/ai-prompt.txt`.
- `/omniv2 ai <question>` command; extract `/omniv2 ...` suggestions from the response and render them as clickable chips with `ClickEvent.suggestCommand(...)`.

### B3. Inspection wand (`/omniv2 tool`)
- Toggle a per-player flag + give the configured material (default `REDSTONE_LAMP`).
- Right-click a block → run a location-scoped search and render results.
- Break/place with the wand should NOT produce a record (filter in the break/place extractors when the player's in wand mode).

### B4. MiniMessage templates for all rendered lines
- Move every `Component.text(...)` in `ResultRenderer` to a template in `messages.conf`.
- Placeholder resolution via `TagResolver`.
- `ResultRenderer` takes a `Messages` dep and renders via `messages.component("search.entry", TagResolver.resolver(...))`.

### B5. Testcontainers-based `MongoRecordStoreIT`
- Spin a `mongo:7.0` container, instantiate `MongoRecordStore`, save one of each record type, query back, assert round-trip equality.
- Verify indexes exist after startup.

### B6. Cloud tab completion
- Register `BlockingSuggestionProvider`s on the greedy `params` argument.
- Parse the partial token, dispatch to the relevant `QueryParamHandler.suggestions(...)`.
- For flag completion, suggest `-ng`, `-g`, `-nc`, etc. when the partial starts with `-`.

### B7. Schema versioning
- Every record has `_v: 1`. When a field changes, bump to `_v: 2` and write a reader branch.
- For reference, see `docs/analysis/02-data-and-storage.md` § modernization #8.

## Tier C — polish

### C1. Adventure-native command results
- The default `CommandSender.sendMessage(Component)` works but leaks legacy conversion in some Paper edge cases. Ensure all rendered output routes through `Audience.sendMessage` for consistent behavior.

### C2. Param-handler-driven flag parsing
- Current flag parsing is inline in `QueryStringParser`. Lift to a `FlagHandler` registry that mirrors `QueryParamHandler` so external plugins can add their own flags.

### C3. Missing flags
- `-drain` (when rollback, empty surviving container contents at the target)
- `-pg=<n>` (initial page on search)

### C4. `QueryPredicate.translate` optimization
- When a query filters on `event` with `Eq` or `In`, only hit the relevant discriminator. When it doesn't, hit the `EventRecord.class` typed collection (not N separate typed collections). Needs POJO codec discriminator dispatch — confirm it works with sealed-interface parent.

### C5. Migration tool
- Read v1's `DataEntry` collection from `Omniscience` database, translate each document to a v2 record, write to `EventRecords` in `Omniscience2` database.
- Implement as a standalone gradle task or a `/omniv2 migrate-v1` admin command (gated behind a confirmation prompt).
- See `docs/analysis/02-data-and-storage.md` for the v1 schema.

### C6. Kill the now-public internals
- Replace the `public` widening in storage/pipeline/rollback/api-impl with one of:
  - `@ApiStatus.Internal` annotations and ServiceLoader bootstrap, or
  - Public facade classes in each package that expose narrow factories.

### C7. Dev server build-and-deploy task
- Gradle task `deployToRpServer` that does `shadowJar` + copies the jar to `../RP_Server/plugins/` + optionally bounces the server via RCON.

## Non-goals (not planned)

- DynamoDB backend. v1's Dynamo was non-functional; v2 ships Mongo-only.
- Custom NMS reflection beyond what Paper exposes. Entity NBT rollback is the only place NMS peeking is tempting — evaluate Paper API first (`Entity.persistentDataContainer`, `Entity.saveAsTag()` if it lands).
- Multi-server (cross-server) support. The `server.` parameter space is non-functional in v1 and isn't a MedievalRP requirement.
