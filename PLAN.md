# Spyglass Phase 3 — execution plan (ship v1.0.0)

## Context

Phase 2 landed at tag `v0.9.0`. The repo at `/Volumes/External-NVME/Documents/GitHub/MedievalRP/Spyglass` has 22 event types recorded, 4 rollbackable record types, vanilla-WorldEdit capture, a passing regression harness, and 34 unit tests.

See [`docs/phase2-notes.md`](docs/phase2-notes.md) for the full commit chain and a Phase 3 backlog written at Phase 2 wrap-up. See [`docs/phase2-plan-execution.md`](docs/phase2-plan-execution.md) for the Phase 2 execution playbook (archived). See [`docs/analysis/`](docs/analysis/) for the original v1 dissection that still guides clean-room work.

This plan is the execution playbook for Phase 3. Read it end-to-end before touching code, then follow the block order.

## Goal

Ship `v1.0.0` — "production-ready replacement for the MPL v1 on the MedievalRP dev server." Concretely:

1. **Migration works.** An operator runs `/sg admin migrate-v1` on the dev server and the 384,482 records in `v1.DataEntry` end up as structurally-equivalent records in `Spyglass.EventRecords`.
2. **FAWE users are covered.** `//set` / `//paste` / `//fill` produce v2 records, including container contents, regardless of FAWE's fast-placement mode.
3. **Inspection wand works in-game.** `/sg tool` toggles a wand; right-clicking a block pages through location-scoped records.
4. **Entity rollback** is a real (experimental) feature.
5. **1.20+/1.21+ block interactions** (bookshelf/pot/brush/sculk/crafter/vault/shulker/bundle) are logged.
6. **Internals are hidden** (`@ApiStatus.Internal`) so the API module has a narrow, published surface.
7. **Coverage gates enforced** — `./gradlew build` fails if JaCoCo line coverage drops below 90% on `api` or 80% on `plugin`.
8. **Released artifact** — `net.medievalrp:spyglass-api:1.0.0` published (locally at minimum), `Spyglass-1.0.0.jar` tagged.

## Non-goals (unchanged)

- AI query assistant (dropped permanently).
- DynamoDB.
- Multi-server (`server.` parameter namespace).
- Custom NMS reflection beyond Paper API.

## Clean-room constraints (unchanged)

1. Do not open files under `../v1/the v1 core/src/` or `../v1/v1API/src/` unless you need a specific factual string the dissection docs don't give you (v1 field names for migration, event-name strings). Never copy implementation code. Paraphrase from `docs/analysis/`, rewrite fresh.
2. Package prefix stays `net.medievalrp.spyglass.*`.
3. Command stays `/sg` (aliases `/o2`, `/spyglass`). Mongo database `Spyglass`, collection `EventRecords`.
4. Architectural commitments from Phase 1 (typed records, sealed predicates, Adventure rendering, Cloud commands, virtual-thread drain, `onDisable` flush, indexes upfront) still apply.

## Regression-test discipline (unchanged)

**`./gradlew test regression` after every commit.** The harness at `regression/run.py` seeds both v1 (`DataEntry`) and v2 (`EventRecords`) in Mongo and runs a query matrix. Red means stop and fix. Commit messages state the regression count (`regression: N/N green`).

The mineflayer-driven live-event layer lands in Block 12 — before that, seeded-data regression plus unit tests cover storage/query/render regressions. Event-extractor logic is covered by unit tests.

## Execution order

Sequential:

1. Block 7 — v1 → v2 migration tool (unblocks production upgrade)
2. Block 8 — FAWE fast-placement (common on MedievalRP; pair with Block 7 for the "switch in production" moment)
3. Block 9 — Inspection wand (daily operator workflow)
4. Block 10 — 1.20+/1.21+ block interactions (feature parity)
5. Block 11 — `-we` flag, entity NBT rollback, HOTBAR/drag container clicks (bundled mid-size items)
6. Block 12 — MiniMessage templates, `@ApiStatus.Internal`, mineflayer, Testcontainers IT
7. Block 13 — Coverage gates, static analysis, v1.0.0 release

Don't skip ahead. Each block's acceptance gates land cleanly on the previous block.

---

## Block 7 — v1 → v2 migration tool (ship-blocker for 1.0)

**Why first:** MedievalRP's dev server has 384,482 v1 records. Without a migration path, v2 ships empty and the history lookup story breaks on day one.

### Scope

- New subpackage: `plugin/src/main/java/net/medievalrp/spyglass/plugin/migration/`.
- `V1DocumentReader` — iterates `v1.DataEntry` in batches (`batchSize = 1000` default), respects a cursor so the migration can be resumed.
- `V1ToV2Translator` — pure function from a v1 BSON `Document` to an `Optional<EventRecord>`. Handles each event name we support:
  - `break` → `BlockBreakRecord` (read `OriginalBlock.MaterialType` + `BlockData`; map `Player` UUID to `Source.PlayerSource`; `Cause` → `Source.EnvironmentSource` or `Source.EntitySource`)
  - `place` → `BlockPlaceRecord`
  - `decay` / `form` / `grow` / `ignite` → same shape, env source
  - `say` → `ChatRecord` (read `Message`; `Recipient` comma-split into `List<UUID>`)
  - `command` → `CommandRecord`
  - `join` → `JoinRecord` (read `IpAddress`)
  - `quit` → `QuitRecord`
  - `deposit` / `withdraw` → `ContainerDepositRecord` / `ContainerWithdrawRecord` (read `Inventory` sub-doc; convert v1's `ConfigurationSerialization` items to `StoredItem` via `ItemSerialization.encode(ItemStack)` — requires Bukkit `ConfigurationSerialization.deserializeObject`)
  - `drop` / `pickup` → `ItemDropRecord` / `ItemPickupRecord`
  - `teleport` → `TeleportRecord`
  - `death` / `hit` / `shot` / `mount` / `dismount` → entity records
  - Skip with a logged warning: anything else (e.g. the 1.20+ events v1 added that v2 hasn't yet — `bookshelf-*`, `pot-*`, `brush`, `sculk`, `crafter`, `vault`, `shulker-*`, `bundle-*`, `entity-deposit`, `entity-withdraw`, `useSign`, `named`, `craft`, `clone`, `close`, `open`, `use`). These get skipped with `log.info("migration: event X deferred, N skipped")`. They can be migrated once Block 10 lands; until then, don't fail on them.
  - Handle the `components=null` bug: v1 docs may have items whose `components` field is null. Log-and-skip the single item, don't fail the entire record.
- `MigrationService` — orchestrates:
  - Progress reporting every 10k docs via `sender.sendActionBar` + `plugin.getLogger().info`
  - Error handling: single-document failures log + increment a skip counter; bulk failures abort cleanly
  - Resume: persist the last processed `_id` in a Mongo collection `MigrationProgress`. On re-run, skip docs already processed.
- `/sg admin migrate-v1 [--dry-run] [--batch-size N] [--resume]` — console-only command (gated on `spyglass.admin`), via the existing Cloud command registration.
- `MigrationTest` (unit): for each v1 event shape, assert the translator produces the right v2 record.
- `MigrationIT` (Testcontainers): seed a fake v1 DB with sample docs, run the migration, verify v2 counts.

### Acceptance

- `./gradlew test regression` green.
- In-server test: rename `v1.jar` → `.disabled`, boot server, RCON `sg admin migrate-v1 --dry-run` reports the right counts; `sg admin migrate-v1` runs without error; post-migration `sg search a:break t:30d -g` returns v1's historical data.
- Commit: `Phase 3 block 7: v1 → v2 migration tool`.

---

## Block 8 — FAWE fast-placement capture

**Why next:** MedievalRP uses FAWE. Without this block, `//set` over a chest logs as air (fast-placement skips the vanilla WE extent chain).

### Scope

- Add FAWE as a `compileOnly` Maven dep:
  ```kotlin
  compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:2.15.1")
  ```
  Repo: IntellectualSites (`https://mvn.intellectualsites.com/content/groups/public/`).
- `FaweHook.tryInstall(EditSessionEvent, Player, World)` — walks the extent chain up to 16 deep. When it finds an `ExtentBatchProcessorHolder`, calls `.addProcessor(new FaweBatchLogger(player, world))`. Returns true if installed.
- `FaweBatchLogger implements IBatchProcessor`:
  - `processSet(IChunk, IChunkGet, IChunkSet)` runs per-chunk on FAWE worker threads.
  - Walks each section's 4096-slot char array.
  - For each set block: compare pre-commit state from `IChunkGet.getBlock(lx,y,lz)` against post-commit state from `blocks[i]`. Skip unchanged.
  - Capture tile NBT via `get.getTile(lx, y, lz)` for containers.
  - Emit `BlockBreakRecord` + `BlockPlaceRecord` with `Origin.fawe()`.
- `FaweTileCapture` — converts FAWE `CompoundTag` container inventory to Bukkit `ItemStack` via `BaseItemStack(itemType, compoundTag, count)` + `BukkitAdapter.adapt(BaseItemStack)`. Produces `StoredItem` that slots into the existing `BlockSnapshot.containerItems`.
- `WorldEditSubscriber.onEditSession`: when FAWE is present, try `FaweHook.tryInstall` first; if it fails, fall through to `LoggingExtent` (which FAWE will reject unless its `extent.allowed-plugins` whitelist is updated — document the whitelist entry for admins).
- Unit tests: synthetic `CompoundTag` → `StoredItem` round-trip via a Mock FAWE adapter (or skip this path; pattern already proven in v1).

### Acceptance

- In-server test: `//set stone` over a placed chest → `/sg search a:break t:1m -g -ng` shows the chest break with its inventory preserved.
- `/sg rollback a:break t:5m -g` restores the chest with contents.
- Commit: `Phase 3 block 8: FAWE fast-placement logging + tile NBT capture`.

---

## Block 9 — Inspection wand (`/sg tool`)

### Scope

- `ToolService` implementation (replace the Phase 2 placeholder):
  - Mongo-persisted per-player state: new collection `Tools` in `Spyglass` DB, schema `{ _id: playerId, enabledAt: Instant }`.
  - `toggle(Player)` flips the state; gives/takes the configured wand material.
  - `isActive(Player)` checks the collection (cached in-memory, invalidated on toggle).
- `WandInteractListener implements Listener`:
  - `PlayerInteractEvent` → if active wand && right-click block → run location-scoped `/sg search a:* r:0 t:7d -g -ng` at the clicked location, render inline.
  - `BlockBreakEvent` / `BlockPlaceEvent` → if active wand → cancel event, fire the same search instead. Bonus: skip emitting the break/place record when wand is active (use a per-player thread-local or `PlayerMetadata` flag read by the extractors).
- Registered in `SpyglassPlugin.onEnable` alongside `PageCache`.
- `ToolServiceTest` (unit): mock Mongo collection, verify toggle persists.

### Acceptance

- In-game test: log in, `/sg tool` → wand given; right-click a recently-broken block → see who broke it + when. Break an unrelated block with wand active → nothing logged.
- Commit: `Phase 3 block 9: inspection wand`.

---

## Block 10 — 1.20+/1.21+ block interactions

Most of these reuse the `ContainerTransactionExtractor` shape; the extractor's filter needs to accept any `InventoryHolder`, not just `Container`.

### New event names

- `bookshelf-insert`, `bookshelf-remove` (chiseled bookshelves)
- `pot-insert`, `pot-remove` (decorated pots)
- `brush` (suspicious sand/gravel — needs delayed-verification helper)
- `sculk` (sensor/shrieker triggers)
- `crafter` (CrafterCraftEvent)
- `vault` (trial key consumption; delayed check)
- `shulker-open`, `shulker-close`, `shulker-deposit`, `shulker-withdraw`
- `bundle-insert`, `bundle-extract`

### Scope

- Refactor `ContainerTransactionExtractor` to accept arbitrary `InventoryHolder` shapes. Dispatch to sub-handlers per holder class (bookshelf, pot, shulker, crafter, bundle).
- Add `BrushExtractor`, `SculkExtractor`, `VaultExtractor` for the non-inventory events.
- `DelayedInteractionTracker` helper shared by `BrushExtractor` and `VaultExtractor` (both need a short `runTaskLater` to verify the block state changed).
- Config + event-name map entries for each new event.
- Unit tests for the `InventoryHolder` sub-handlers.

### Acceptance

- Each event appears in `sg events` output.
- A minimal bookshelf-insert / pot-insert / shulker-open scenario produces the right records (live in-game test required; automated regression deferred to mineflayer in Block 12).
- Commit: `Phase 3 block 10: 1.20+/1.21+ block interactions`.

---

## Block 11 — `-we` flag + entity NBT rollback + HOTBAR/drag clicks

Bundled mid-sized items.

### 11a — `-we` flag

- New flag handler: when `-we` is in the query and the sender is a `Player` with an active WorldEdit selection, replace the default radius predicate with a cuboid predicate built from the selection's bounding box.
- `FlagHandler` registry (promote `QueryStringParser`'s inline flag handling to the registry pattern matching `QueryParamHandler`).
- `-we` gated on `spyglass.worldedit` permission (new).

### 11b — Entity NBT rollback

- `EntityDeathRecord` implements `Rollbackable` via `@ApiStatus.Experimental`.
- Capture path: `Entity.saveAsTag()` (Paper 1.21.x API) → `String` of NBT-in-SNBT → store on the record.
- `RollbackEngine.applyEntitySpawn`:
  - Spawn new entity at `BlockLocation` via `world.spawnEntity(location, EntityType, SpawnReason.COMMAND)`
  - Restore NBT via Paper's entity tag APIs
  - Inverse effect: `RollbackEffect.EntityRemove(location, entityType, entityId)`
- Document the "NBT cross-version brittleness" caveat in code + docs.

### 11c — HOTBAR / drag clicks

Port v1's `InventoryUtil.identifyTransactions` clean-room. Spec in `docs/analysis/09-utilities-and-gaps.md`.

Covers:
- `HOTBAR_SWAP`
- `HOTBAR_MOVE_AND_READD`
- `COLLECT_TO_CURSOR` (multi-slot diffing — walk the container inventory, report slots that lost stacks)
- `InventoryDragEvent` (multi-slot drop)

### Acceptance

- `-we` narrows searches correctly (in-game test).
- `/sg rollback a:death t:5m` resurrects killed mobs with their attributes (in-game test).
- Container shift-clicks with all action types log correctly.
- Commit: `Phase 3 block 11: -we flag + entity NBT rollback + HOTBAR/drag`.

---

## Block 12 — Polish

### 12a — MiniMessage template extraction

- Port every `Component.text(...)` in `ResultRenderer`, `HelpService`, `EventsService`, `ServiceSupport` error/info/warn helpers to MiniMessage templates loaded from `messages.conf`.
- Use `TagResolver.resolver(Placeholder.unparsed(...))` for dynamic values.
- Server ops can edit `messages.conf` in-place to customize copy without rebuilding.

### 12b — `@ApiStatus.Internal`

Every class in these packages that's currently `public` only to satisfy cross-package wiring gets `@ApiStatus.Internal`:

- `plugin/api/SpyglassApiImpl`
- `plugin/pipeline/*` (Recorder, AsyncRecorder, ExtractorRegistry)
- `plugin/storage/*` (MongoRecordStore, IndexManager, RecordStore, PredicateToBson)
- `plugin/rollback/*` (RollbackEngine, UndoStack)
- `plugin/command/service/*` (all eight services, ServiceSupport)
- `plugin/command/SpyglassSuggestions`, `SpyglassCommands`, `PageCache`
- `plugin/command/render/Messages`, `ResultRenderer`
- `plugin/migration/*`
- `plugin/worldedit/*`

Don't touch `api/` classes — those are the public surface.

### 12c — Mineflayer-driven regression

- `regression/bot/` gets the `package.json`, `scenario-basic.js`, `scenario-rollback.js`, `scenario-worldedit.js` described in Phase 2 PLAN.md Block 2.
- `regression/run.py` gains an `--include-live` flag that:
  - Starts the server if not running
  - Runs each scenario
  - Waits for events to drain
  - Continues with the existing query matrix
- CI config updated to run unit tests only (not live regression — too heavy for GH Actions).

### 12d — `MongoRecordStoreIT`

Testcontainers-based:
- Spin `mongo:7.0` container
- Instantiate `MongoRecordStore` with the container's connection string
- Save one of each record type
- Query back
- Assert round-trip equality
- Verify all 4 indexes + the TTL exist

### Acceptance

- `./gradlew test` green including `MongoRecordStoreIT`.
- `messages.conf` has templates for every rendered line; in-game output looks identical to before the port.
- `jdeps` or similar shows no external references to plugin-internal classes.
- `./gradlew regression --include-live` runs the mineflayer scenarios to completion.
- Commit: `Phase 3 block 12: polish (MiniMessage + @ApiStatus.Internal + mineflayer + IT)`.

---

## Block 13 — Hardening + v1.0.0 release

### 13a — Coverage gates

Add to root `build.gradle.kts`:

```kotlin
subprojects {
    tasks.withType<JacocoCoverageVerification>().configureEach {
        violationRules {
            rule {
                element = "BUNDLE"
                limit {
                    counter = "LINE"
                    minimum = when (project.name) {
                        "api" -> 0.90.toBigDecimal()
                        "plugin" -> 0.80.toBigDecimal()
                        else -> 0.00.toBigDecimal()
                    }
                }
            }
        }
    }
    tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
}
```

Run `./gradlew build` and fix tests until coverage meets the gates.

### 13b — Static analysis

- Add SpotBugs (via `com.github.spotbugs.spotbugs-gradle-plugin`) OR Error Prone.
- Default to `ERROR` severity; fail the build on any finding.
- `@SuppressWarnings` allowed only with a comment explaining why.

### 13c — Codebase hygiene

- Grep the codebase: no `TODO`, `FIXME`, `XXX`, `HACK` under `plugin/` or `api/`. Every such marker either gets fixed or tracked to an issue.
- No commented-out code.
- No `public` widening that's now redundant after Block 12's `@ApiStatus.Internal` pass.
- Verify the `deployToRpServer` task still works end-to-end.

### 13d — Maven publication

- Enable `maven-publish` for the `api` subproject.
- Publish to a local Maven at minimum:
  ```kotlin
  publishing {
      publications {
          create<MavenPublication>("api") {
              from(components["java"])
              groupId = "net.medievalrp"
              artifactId = "spyglass-api"
              version = "1.0.0"
          }
      }
      repositories {
          maven {
              name = "local"
              url = uri(layout.buildDirectory.dir("repo"))
          }
      }
  }
  ```
- `./gradlew :api:publishApiPublicationToLocalRepository` produces the artifact. Document the GitHub Packages path for future external use.

### 13e — Version bump + release

- `gradle.properties`: `version=1.0.0`.
- `./gradlew clean build` produces `Spyglass-1.0.0.jar`.
- `git tag -a v1.0.0 -m "Feature-complete release"`.
- Update `README.md` with end-user install instructions (minimum Paper version, Mongo required, upgrade notes for operators coming from v1).

### Acceptance

- `./gradlew build` green with coverage gates and static analysis passing.
- Zero TODOs / FIXMEs in source.
- `Spyglass-1.0.0.jar` + `spyglass-api-1.0.0.jar` produced.
- README has an "Upgrading from v1" section pointing at `/sg admin migrate-v1`.
- Tag `v1.0.0` pushed.
- Commit: `Phase 3 block 13: v1.0.0 — feature-complete release`.

---

## When you finish

- Commit range from the Phase 2 HEAD (`c3a1223`) onward.
- Tag `v1.0.0`.
- Coverage report summary (% per module).
- Summary of verification:
  - Migration tested against MedievalRP dev's actual v1 data
  - FAWE logging tested with `//set` over a chest
  - Wand tested in-game
  - Regression matrix count
- Hand back for production switchover: rename `v1.jar` → `.disabled` on the real MedievalRP server, deploy v2, run migration, verify queries. Then pull the plug on v1.

## Notes from Phase 2 for whoever picks this up

- The regression harness sleeps 0.8s between an async search command and the `page 1` follow-up. If you add commands that take longer (Migration: slow queries during bulk insert), bump the delay or make the harness poll for completion.
- The config auto-merge (Phase 2 block 3.1) only merges the `events` section. If you add new top-level keys in future configs, extend the merger.
- `ServiceSupport` is an interface with `bukkit(plugin)` and `synchronous()` factories. Tests use `synchronous`. Don't regress this.
- `RollbackEngine.applyBlockReplace` does a strict `matches(expectedCurrent, actual)` check that'll skip any block that's been touched since the log. Document this clearly if the migration's restore path re-triggers it.
- The `events-lists-decay` regression case doubles as a smoke test for config auto-merge. If it fails after a config change, the auto-merger didn't pick up the new event.

## Concluding note

Clean-room discipline is primary. When in doubt, favor small correct pieces over big ambitious ones. If you hit a hard blocker (e.g. Paper's `Entity.saveAsTag()` doesn't round-trip cleanly, FAWE's API changed), document the blocker in `docs/phase3-blockers.md` and move on to the next block — do not over-engineer around it. The senior dev will direct the next step.
