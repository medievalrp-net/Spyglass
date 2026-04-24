# Spyglass Phase 2 — execution plan (production hardening)

## Context

Phase 1 shipped as commit `9a0f4a1`. The repo at `/Volumes/External-NVME/Documents/GitHub/MedievalRP/Spyglass` already contains:

- Working skeleton on Paper 1.21.8 / JDK 21 / Gradle 9, Mongo POJO-codec storage, Cloud 2.x commands, Adventure rendering.
- 8 event types (break, place, deposit, withdraw, say, command, join, quit) recorded, queried, rendered. Two of them (break, place) rollbackable.
- Seeded-data verification passed via RCON against the MedievalRP dev server (`../RP_Server`) while running alongside the original MPL-licensed v1. Both plugins coexist without conflict.
- Phase 1 details: [`docs/phase1-plan.md`](docs/phase1-plan.md), [`docs/phase1-notes.md`](docs/phase1-notes.md), [`docs/phase2-plan.md`](docs/phase2-plan.md) (backlog inventory).

This plan is the execution playbook for Phase 2. Read it end-to-end before touching code, then follow the block order. **No AI query assistant** — explicitly dropped from scope.

## Goal

Take Spyglass from "working skeleton + vertical slice" to **production-ready replacement** of the MPL-licensed v1 on the MedievalRP server. That means:

- Every event v1 logs, v2 also logs (minus AI).
- Every query v1 answers, v2 answers (minus AI).
- Every rollback v1 performs, v2 performs.
- All the Phase 1 rough edges smoothed: tab completion, rollback reply delivery, MiniMessage templates, tool wand, migrations, hidden internals.
- Regression tests run **every commit**, not just at the end.
- `./gradlew build` produces a publishable jar, ready to replace `v1.jar` in production.

## Clean-room constraints (unchanged)

1. Do not open files under `../v1/the v1 core/src/` or `../v1/v1API/src/` unless you need a specific factual string the dissection docs don't give you (event-name strings, config keys, Mongo field names). Never copy implementation code. Paraphrase from `docs/analysis/`; write fresh code.
2. Package prefix stays `net.medievalrp.spyglass.*`.
3. Command stays `/sg` (aliases `/o2`, `/spyglass`). Mongo database `Spyglass`, collection `EventRecords`.
4. Architectural commitments in Phase 1's [PLAN](docs/phase1-plan.md) § "Architectural commitments" still apply.

## Non-goals (explicit)

- **AI query assistant** is dropped. No `/sg ai`, no Vertex AI dependency, no `ai-prompt.txt`. If it's mentioned in the dissection docs, ignore those sections.
- DynamoDB backend.
- Multi-server (`server.` parameter namespace).
- Custom NMS reflection beyond Paper API (evaluate Paper first; only go there for entity NBT rollback and only with `@ApiStatus.Experimental`).

## The regression-test discipline

**This is non-negotiable. Run after every commit, not every feature.**

Regression is structured in two tiers:

### Tier 1 — unit + integration (`./gradlew test`)

Every public class under `plugin/src/main/java` has either unit or integration coverage. Targets to write as you add features:

- `DurationTest` (exists)
- `PredicateToBsonTest` (exists)
- `AsyncRecorderTest` (exists)
- `MongoRecordStoreIT` (Testcontainers) — seed every record type, round-trip through Mongo, verify indexes
- `*ExtractorTest` — one per extractor, synthetic Bukkit event in, record stream out (use MockBukkit where it helps, direct event construction otherwise)
- `RollbackEngineTest` — each `RollbackEffect` variant against a mocked world
- `QueryStringParserTest` — full grammar, default-radius suppression, flag parsing, error cases
- `ResultRendererTest` — record + aggregation + flag combinations produce expected `Component`
- `SearchServiceTest`, `RollbackServiceTest`, `UndoServiceTest` — once the command refactor (Block 1) lands
- `MigrationTest` — read sample v1 documents, produce v2 records

Gate: `./gradlew test` green. Target line coverage with JaCoCo: ≥ 80% on the plugin module, ≥ 90% on the api module.

### Tier 2 — live regression (`./gradlew regression`)

A fresh Gradle task that:

1. Kills any running Paper at `../RP_Server`.
2. Starts Paper fresh with **both** `v1.jar` (v1) and `Spyglass.jar` (v2) loaded.
3. Waits for the "Done" startup banner.
4. Runs a deterministic event generator (see below) to produce identical events that flow into both v1 and v2.
5. Executes a parallel query matrix over RCON:
   - Every v1 `/sg search ...` has a matching v2 `/sg search ...` (same parameters).
   - For queries with `-ng` (ungrouped): compare record counts + per-record (source, event, target).
   - For grouped queries: compare count-per-bucket.
   - For rollback/restore: apply to v1 AND v2 on structurally-identical test worlds or sandboxed regions; compare resulting block states.
6. Stops the server cleanly.
7. Writes a JUnit-style report at `plugin/build/reports/regression/index.html` and fails the build on any mismatch.

**Event generator options** (pick one, commit to it for the whole phase):

- **Option A — `mineflayer` bot** (recommended). Node-based MC client library, supports 1.21.x. Script a connect → action sequence → disconnect per test run. Requires `node` + `npm`.
- **Option B — Paper's Bukkit event-fire API**. A tiny harness plugin that exposes a `/regress fire <event>` command; the Gradle task dispatches via RCON. Harder to simulate player identity convincingly.

Prefer Option A. Put the bot script at `regression/bot/`. Commit the scripts.

### The iron rule

**After every commit that could affect v1/v2 equivalence**, run `./gradlew test regression`. Red means stop and fix. Don't batch.

Commit messages for feature commits must include a one-line regression status, e.g. `regression: 14/14 cases green`.

## Repo reorganization (Block 0, do first)

Before any feature work, set up the polish work cleanly.

- Move Phase 1's `/Volumes/External-NVME/Documents/GitHub/MedievalRP/Spyglass/PLAN.md` → `docs/phase1-plan.md` (already done).
- Make `PLAN.md` (this file) the current execution plan.
- Add `JaCoCo` to gradle. `./gradlew jacocoTestReport` produces the coverage report.
- Add `.github/workflows/build.yml` that runs `./gradlew test` on push + PR. Regression doesn't run in CI (needs the dev server + mineflayer); it's a local gate.
- Add a `deployToRpServer` Gradle task:
  ```kotlin
  tasks.register("deployToRpServer") {
      dependsOn(":plugin:shadowJar")
      doLast {
          val src = project(":plugin").buildDir.resolve("libs/Spyglass-$version.jar")
          val dst = file("../RP_Server/plugins/Spyglass.jar")
          src.copyTo(dst, overwrite = true)
          println("Deployed $dst")
      }
  }
  ```
- Add `.gitattributes` to normalize line endings; add `.editorconfig` matching the indentation in the codebase (4-space, LF).

## Block 1 — Command layer refactor (consolidate + outsource)

**Problem to solve:** `plugin/src/main/java/.../command/SpyglassCommands.java` is the only place commands live, but it mixes Cloud registration with business logic. Production pattern: registration in one place, each command's flow in a named service.

**Target shape:**

```
command/
├── SpyglassCommands.java — ONE place, registration only; each handler is a one-liner delegate.
├── sg.java — (optional) flags + suggestions providers, shared across commands.
├── service/
│ ├── SearchService.java — parse → query → render → cache → reply
│ ├── RollbackService.java — parse → query → apply → reply → persist undo
│ ├── UndoService.java — pop → apply → reply
│ ├── HelpService.java — render /help
│ ├── EventsService.java — render /events
│ ├── PageService.java — cache-backed paged display (absorbs PageCache + page command)
│ └── ToolService.java — wand toggle
├── param/ (existing) — untouched; handlers implement suggestions()
├── render/ (existing)
└── RollbackRunner.java — delete (logic absorbed into RollbackService)
```

**Each service:**

- Public final class.
- Constructor injection — takes only the collaborators it needs (e.g. `SearchService(api, parser, renderer, pageCache, logger)`).
- Single entry point method named `execute(...)`. No `handleResults` helpers at command level.
- No references to Cloud types. Services are Cloud-agnostic.

**SpyglassCommands after refactor:**

```java
public final class SpyglassCommands {
    // dependencies held for wiring only
    private final JavaPlugin plugin;
    private final HelpService helpService;
    private final EventsService eventsService;
    private final SearchService searchService;
    private final RollbackService rollbackService;
    private final UndoService undoService;
    private final PageService pageService;
    private final ToolService toolService;
    private final SpyglassSuggestions suggestions;

    public CommandManager<CommandSender> register() {
        var manager = LegacyPaperCommandManager.createNative(plugin, ExecutionCoordinator.simpleCoordinator());
        for (var root : List.of("sg", "o2", "spyglass")) {
            manager.command(manager.commandBuilder(root)
                    .permission("spyglass.use")
                    .handler(ctx -> helpService.send(ctx.sender())));
            manager.command(manager.commandBuilder(root).literal("help")
                    .permission("spyglass.use")
                    .handler(ctx -> helpService.send(ctx.sender())));
            manager.command(manager.commandBuilder(root).literal("events")
                    .permission("spyglass.use")
                    .handler(ctx -> eventsService.send(ctx.sender())));
            manager.command(manager.commandBuilder(root).literal("search")
                    .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                    .permission("spyglass.search")
                    .handler(ctx -> searchService.execute(ctx.sender(), ctx.get("params"))));
            manager.command(manager.commandBuilder(root).literal("rollback")
                    .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                    .permission("spyglass.rollback")
                    .handler(ctx -> rollbackService.execute(ctx.sender(), ctx.get("params"), RollbackMode.ROLLBACK)));
            manager.command(manager.commandBuilder(root).literal("restore")
                    .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                    .permission("spyglass.rollback")
                    .handler(ctx -> rollbackService.execute(ctx.sender(), ctx.get("params"), RollbackMode.RESTORE)));
            manager.command(manager.commandBuilder(root).literal("undo")
                    .permission("spyglass.rollback")
                    .handler(ctx -> undoService.execute(ctx.sender())));
            manager.command(manager.commandBuilder(root).literal("page")
                    .required("number", IntegerParser.integerParser(1))
                    .permission("spyglass.use")
                    .handler(ctx -> pageService.show(ctx.sender(), ctx.get("number"))));
            manager.command(manager.commandBuilder(root).literal("tool")
                    .permission("spyglass.tool")
                    .handler(ctx -> toolService.toggle(ctx.sender())));
        }
        return manager;
    }
}
```

Total ~50 lines. Every handler a one-liner. Every behaviour in a named, injectable, testable service.

**Fix while you're in there:**

- Remove the diagnostic `plugin.getLogger().info("Spyglass: ... handler fired")` left in Phase 1.
- The "Searching..." / "ROLLBACK running..." status lines move into the services.
- `RollbackRunner`'s main-thread hop via `Bukkit.getScheduler().callSyncMethod(...).get()` didn't deliver summary lines cleanly. Replace with `plugin.getServer().getScheduler().runTask(plugin, () -> { ... apply ... reply ... })` inside the async query's `whenComplete`. Report the summary via `sender.sendMessage` on the main thread.

**Acceptance for Block 1:**

- `SpyglassCommands.java` ≤ 80 lines.
- Every service has a `*Test` with ≥ 5 test cases.
- `./gradlew test regression` green. The regression matrix must include: search happy path, search with every param, rollback for block break, undo, search with no results, search with paging (30+ results → verify `page 2` works).
- Commit message: `Phase 2 block 1: command layer refactor + service outsourcing`.

## Block 2 — Regression harness

Before writing any more feature code, **commit the regression harness**. Not after. The harness is what lets you ship the rest of Phase 2 safely.

### Task order

1. **Install `mineflayer`** at `regression/bot/`:
   ```
   regression/
   ├── bot/
   │ ├── package.json
   │ ├── scenario-basic.js — connect → break 5 blocks → place 5 → chat "hello" → disconnect
   │ ├── scenario-rollback.js — connect → break a chest with contents → disconnect (triggers break event; used to test rollback)
   │ ├── scenario-entity.js — spawn and kill an entity (for Block 3's entity work)
   │ └── scenario-worldedit.js — run //set via the bot (for Block 4)
   └── gradle/
       └── regression.gradle.kts — the `regression` task wiring
   ```
2. **Write the parallel query matrix** at `regression/queries.json`:
   ```json
   [
     {"id": "break-all", "v1": "sg search a:break t:5m -ng", "v2": "sg search a:break t:5m -ng", "compare": "count"},
     {"id": "place-all", "v1": "sg search a:place t:5m -ng", "v2": "sg search a:place t:5m -ng", "compare": "count"},
     {"id": "say", "v1": "sg search a:say t:5m -ng", "v2": "sg search a:say t:5m -ng", "compare": "message-text"},
     ...
   ]
   ```
3. **Write the regression runner** — a Python or Java script that:
   - Starts Paper with both plugins.
   - Waits for Done.
   - Connects mineflayer bot, runs `scenario-basic.js`, disconnects.
   - Waits 2 seconds for queues to drain on both plugins.
   - For each query in `queries.json`, executes v1 and v2 via RCON, normalizes output (strip colors, extract record lines), compares.
   - Reports mismatches (v1 count vs v2 count, or per-record diffs).
   - Kills Paper.
4. **Gradle task:**
   ```kotlin
   tasks.register<Exec>("regression") {
       dependsOn(":plugin:shadowJar", "deployToRpServer")
       workingDir = rootProject.projectDir
       commandLine = listOf("python3", "regression/run.py")
   }
   ```
5. **Document** the harness in `regression/README.md`.

### Expected behavior

When v2 is correct, every query produces ≥ v1's count (v2 may capture slightly more — e.g. it doesn't have v1's `EventName` typo bug). The comparator should flag "v2 < v1" as an error, and "v2 ≥ v1" as pass.

For exact-equality comparisons (e.g. individual message text), strict match required.

### Acceptance

- `./gradlew regression` runs clean against Block 1's code.
- Matrix includes every query shape used in `docs/phase1-notes.md` (search variations) — at least 12 test cases.
- Regression docs describe how to add a new case.
- CI workflow doesn't run regression (too heavy); document as a local gate.

## Block 3 — Feature parity (events)

All remaining v1 events. Work in this order — later ones build on earlier ones.

### 3.1 Environment block events

Records: no new records needed — reuse `BlockBreakRecord` / `BlockPlaceRecord` with `Source.environment(description)` + `Origin.environment(description)`.

Extractors:
- `LeavesDecayExtractor` (event `decay`) → BlockBreakRecord, source `environment("leaves-decay")`.
- `BlockFadeExtractor` (event `decay`) → BlockBreakRecord.
- `BlockFormExtractor` (event `form`) → BlockBreakRecord (represents the before) or BlockPlaceRecord (represents the after) — pick one consistent direction.
- `BlockGrowExtractor` (event `grow`) → BlockPlaceRecord with env source.
- `StructureGrowExtractor` (event `grow`) → BlockPlaceRecord with env source; may need to emit multiple records (structure covers N blocks).
- `BlockIgniteExtractor` (event `ignite`) → BlockPlaceRecord (FIRE block appearing).

Update `config.conf` with the new events + past-tense strings: `decayed`, `formed`, `grew`, `ignited`.

### 3.2 Explosion events

`BlockExplodeEvent` and `EntityExplodeEvent` cascade into many blocks. Extractors return a stream.

- `BlockExplodeExtractor` → one `BlockBreakRecord` per affected block.
- `EntityExplodeExtractor` → same, but source is the entity that exploded (e.g. `Source.entity(creeperId, "CREEPER")`).

### 3.3 `BlockMultiPlaceEvent`

Beds and doors place two blocks. Extractor emits two `BlockPlaceRecord`s.

### 3.4 Drop / pickup / teleport

New records:
- `ItemDropRecord(id, schemaVersion, event, occurred, expiresAt, origin, source, location, target, StoredItem item)` — event name `drop`. Sources: `PlayerDropItemEvent` (player), `EntityDropItemEvent` (entity), `BlockDispenseEvent` (block source).
- `ItemPickupRecord(...)` — event `pickup`. Source: `EntityPickupItemEvent`.
- `TeleportRecord(id, schemaVersion, ..., BlockLocation from, BlockLocation to, String cause)` — event `teleport`.

None of these are rollbackable in Phase 2 (drops might be, as a Tier-C follow-up; deferred).

### 3.5 Entity events + entity rollback

Records:
- `EntityDeathRecord(..., String entityType, String serializedNbt, BlockLocation where, Source killer)` — event `death`, rollbackable.
- `EntityHitRecord(..., String attacker, String victim, double damage)` — event `hit`.
- `EntityShotRecord(..., String attacker, String projectile, String victim)` — event `shot`.
- `EntityMountRecord(..., String rider, String mount)` — event `mount`.
- `EntityDismountRecord(...)` — event `dismount`.

Implementation:
- Extractors for `EntityDeathEvent`, `EntityDamageByEntityEvent` (split into hit/shot based on damager instance), `EntityMountEvent`, `EntityDismountEvent`.
- Entity NBT capture: use Paper's `Entity.saveAsTag()` and `Entity.fromTag()` if available (Paper 1.21+ exposes these). Serialize the `CompoundTag` to Base64. Fall back to `@ApiStatus.Experimental` reflective NMS if the Paper API isn't complete.
- Rollback: `EntityDeathRecord.rollbackEffect()` returns `RollbackEffect.EntitySpawn(location, entityType, serializedNbt)`. `RollbackEngine.applyEntitySpawn(...)` — spawn at location, restore NBT.
- `EntityRemove` effect (used for undoing a spawn) — already in `RollbackEffect` sealed type.

Mark the entity rollback path `@ApiStatus.Experimental` — NBT across MC versions is known-brittle.

### 3.6 Item frame / armor stand

`PlayerArmorStandManipulateEvent`, `PlayerInteractEntityEvent` on item frames.

Records:
- `EntityDepositRecord`, `EntityWithdrawRecord` — similar shape to `ContainerDepositRecord` but with an `entityType` field and no slot (item frames have a single slot; armor stands have 4 equipment slots + a hand = make the slot field an enum).

Rollbackable: yes — rollback restores the item frame/armor stand's item.

### 3.7 1.20+/1.21+ block interactions

Bookshelves, decorated pots, brushes, sculk, crafters, vaults, shulker interactions, bundles.

These are variations on container deposit/withdraw with a specific inventory shape. Refactor `ContainerTransactionExtractor` to accept any `InventoryHolder` subclass, not just `Container`.

Events: `bookshelf-insert`, `bookshelf-remove`, `pot-insert`, `pot-remove`, `brush`, `sculk`, `crafter`, `vault`, `shulker-open`, `shulker-close`, `shulker-deposit`, `shulker-withdraw`, `bundle-insert`, `bundle-extract`.

These share one new record:
- `InteractRecord(..., String interactionType, ...)` — or specific records per shape. Pick based on whether queries want to filter.

Reuse `ContainerDepositRecord` / `ContainerWithdrawRecord` where possible with a new `kind` field or similar. Don't over-proliferate record types.

### 3.8 Tighten `ContainerTransactionExtractor`

Current extractor handles only PLACE_* and PICKUP_* click actions. Complete set:

| InventoryAction | Record | Notes |
|---|---|---|
| PLACE_ALL / PLACE_ONE / PLACE_SOME | deposit | (done) |
| PICKUP_ALL / PICKUP_HALF / PICKUP_ONE / PICKUP_SOME | withdraw | (done) |
| SWAP_WITH_CURSOR | deposit + withdraw (pair) | Before/after both non-null |
| MOVE_TO_OTHER_INVENTORY | diff-based | Multi-slot — use a slot-diff helper |
| HOTBAR_MOVE_AND_READD | deposit + withdraw pair | Hotbar swap |
| HOTBAR_SWAP | deposit + withdraw pair | |
| COLLECT_TO_CURSOR | multi-slot diff | Gather same-type items |
| DROP_ALL_CURSOR / DROP_ONE_CURSOR | drop (extends 3.4) | |
| DROP_ALL_SLOT / DROP_ONE_SLOT | drop (extends 3.4) | |
| NOTHING / UNKNOWN | — | Skip |
| CLONE_STACK | clone | New event kind |

Also: `InventoryDragEvent` handling (multi-slot drop). Extract `InventoryUtil.identifyTransactions`-equivalent logic — see `docs/analysis/09-utilities-and-gaps.md` § InventoryUtil for the spec, rewrite clean-room.

### 3.9 Container rollback

Make `ContainerDepositRecord` and `ContainerWithdrawRecord` implement `Rollbackable`. `RollbackEffect.ContainerSlotWrite` already exists and `RollbackEngine.applyContainerSlotWrite` already implements it. Wire the factory methods.

### Block 3 acceptance

- Every event name listed by v1's `/sg events` command (minus AI-related) is registered and enabled in v2's `/sg events` output.
- Every break/place/container/entity record is rollbackable.
- `regression/scenario-basic.js` expands to cover every event type.
- `queries.json` gets 40+ test cases.
- `./gradlew test regression` green.
- Commit message: `Phase 2 block 3: event parity — regression 40/40 green`.

## Block 4 — WorldEdit / FastAsyncWorldEdit integration

### 4.1 FAWE dependency

Drop the local-jar approach from v1. In `plugin/build.gradle.kts`:

```kotlin
repositories {
    // ... existing
    maven("https://maven.enginehub.org/repo/")
    maven("https://mvn.intellectualsites.com/content/groups/public/")
}
dependencies {
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.15.1")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:2.15.1")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.15")
}
```

Verify resolvability with `./gradlew :plugin:dependencies | grep -i worldedit`.

### 4.2 Vanilla WorldEdit `LoggingExtent`

Subscribe to `EditSessionEvent` at `Stage.BEFORE_CHANGE`. Wrap `event.getExtent()` in a `LoggingExtent` that captures pre-change `BlockState` before `super.setBlock(...)`, then calls `Recorder.record(...)` with `Origin.worldEdit()` and the player as source.

Capture tile entity state: read the live Bukkit `BlockState` and feed it to `BlockSnapshots.capture` — the same logic as `BlockBreakExtractor`.

### 4.3 FAWE `IBatchProcessor`

Walk the extent chain on the `EditSessionEvent`. If any extent in the chain is an `ExtentBatchProcessorHolder`, call `.addProcessor(new FaweBatchLogger(player, world))`. This is the sanctioned FAWE extension point and survives fast-placement mode.

FAWE path:
- Iterate set sections per chunk.
- For each set block, read the pre-commit state from `IChunkGet`.
- Capture tile entity NBT via `get.getTile(lx, y, lz)`.
- Convert the FAWE NBT `CompoundTag` to a Bukkit `ItemStack` via `BaseItemStack` + `BukkitAdapter.adapt(...)` — the same dance v1 does.
- Emit `BlockBreakRecord` + `BlockPlaceRecord` with `Origin.fawe()`.

### 4.4 `-we` query flag

Add a `WeSelectionFlag` to the flag parser. When set:
- Read the invoking player's WorldEdit `LocalSession`.
- If the selection is a `CuboidRegion`, add a `QueryPredicate.And` with `location.worldId` + `location.x/y/z` range predicates matching the selection's bounding box.
- Suppress the default-radius predicate (the flag implies a spatial filter already).
- Error cleanly if the selection is non-cuboid or missing.

### 4.5 (Optional, defer if tight on time) Batched FAWE rollback

For rollbacks affecting ≥ 500 blocks, open a single FAWE `EditSession` per world and call `setBlock(...)` in bulk. FAWE commits async, order-of-magnitude faster than per-block Bukkit path.

### Block 4 acceptance

- A WE `//set stone` over a chest records N `BlockBreakRecord`s (one per affected block) with container contents preserved and `Origin.fawe()` or `Origin.worldEdit()` correctly set.
- `/sg rollback a:place -we` reverses the `//set`.
- `regression/scenario-worldedit.js` covers the happy path.
- `./gradlew test regression` green.
- Commit: `Phase 2 block 4: WE/FAWE integration`.

## Block 5 — Migration + tool wand + final polish

### 5.1 v1 → v2 migration tool

Collection: `v1.DataEntry` → `Spyglass.EventRecords`.

Implementation:
- New subproject `migration/` or standalone `plugin/src/main/java/net/medievalrp/spyglass/plugin/migration/V1MigrationTool.java`.
- Exposed as a console-only command: `/sg admin migrate-v1 [--dry-run] [--batch-size=1000]`.
- For each v1 document:
  - Read `Event`, `Player` (UUID), `Cause`, `Target`, `Location.{X,Y,Z,World}`, `Created`, `Expires`, event-specific fields.
  - Map to the appropriate v2 record type via a `switch` on event name.
  - Insert into `EventRecords`.
- Progress reporting via `sender.sendActionBar` every 10k docs.
- Handles the v1 `components=null` bug: if an item's `components` field is null, skip it cleanly rather than failing.
- Tests: `MigrationTest` with sample v1 documents for every event type.

Dry-run mode: count + validate, don't write.

### 5.2 Inspection wand

`/sg tool` toggles a per-player boolean in `ToolService`. Active players get the configured material (`config.tool.material`, default `REDSTONE_LAMP`). State lives in a Mongo collection `Tools` with a `(playerId, enabledAt)` schema — survives restarts.

Right-click behavior:
- `PlayerInteractEvent` handler, gated on `toolService.isActive(player)`.
- For a block click: run a location-scoped `/sg search a:* r:0 t:1d -g -ng` at the clicked block's coords. Render inline.
- For air click: no-op.

Break/place while wand is active: the break/place extractor skips emitting (the tool is inspection-only, don't log its use). Implement via a flag on the event via Paper's metadata API or a per-player thread-local in the extractor.

### 5.3 Remaining flags

- `-drain` — when rolling back, also empty surviving containers at the target location. Implement as an additional `RollbackEffect.ContainerClear` applied after the main `BlockReplace`.
- `-pg=<n>` — initial page on search. Services accept it and call `PageService.show(sender, n)` instead of `1`.

### 5.4 Tab completion wiring

Every parameter's `suggestions(...)` method is already implemented. Wire them into Cloud:

```java
BlockingSuggestionProvider<CommandSender> paramsProvider() {
    return (ctx, input) -> {
        String remaining = input.remainingInput();
        String lastToken = lastToken(remaining);
        return suggestionsFor(ctx.sender(), lastToken).stream()
                .map(Suggestion::suggestion)
                .collect(Collectors.toList());
    };
}
```

Where `suggestionsFor` walks the registered `QueryParamHandler`s and dispatches to the matching one based on the `alias:` prefix. Handle flags (`-...`) and param aliases (`p:`, `a:`, etc.) separately.

Verify with an in-game client that tab completion actually works (RCON can't test this).

### 5.5 MiniMessage templates

Port `ResultRenderer` from inline `Component.text()` to MiniMessage templates loaded from `messages.conf`. Add templates for:
- `search.entry-single`
- `search.entry-aggregation`
- `search.hover-line`
- `rollback.summary`
- `error.*`
- `help.*`

Use `TagResolver.resolver(Placeholder.unparsed(...))` for dynamic values.

### 5.6 Hide internals

Replace the `public` widening from Phase 1. Every class under:
- `plugin/src/main/java/.../api/SpyglassApiImpl.java`
- `plugin/src/main/java/.../pipeline/*`
- `plugin/src/main/java/.../storage/*`
- `plugin/src/main/java/.../rollback/*`
- `plugin/src/main/java/.../command/service/*`

...gets `@ApiStatus.Internal`. The plugin class `SpyglassPlugin` wires them via constructor — it imports them but external plugins shouldn't.

Add the JetBrains annotations dependency:
```kotlin
compileOnly("org.jetbrains:annotations:26.0.2")
```

### 5.7 Documentation

- Rewrite `README.md` for production users: "How to install", "Configuration", "Commands", "Migration from v1".
- Archive this `PLAN.md` to `docs/phase2-plan.md` (or similar) once complete.
- Delete or consolidate any `phase1-notes.md` / `phase2-plan.md` that are now historical.

### Block 5 acceptance

- Migration tool: running against a seeded v1 fixture produces v2 records whose counts + sample contents match.
- Tool wand: on-click search works in-game. Break/place with wand doesn't pollute logs.
- `-drain` and `-pg=` work. Tab completion works in-game.
- `./gradlew test regression` green.
- Commit: `Phase 2 block 5: migration + tool + finishing`.

## Block 6 — Production hardening

Once the feature set is complete:

### 6.1 Coverage gates

- `./gradlew jacocoTestReport` ≥ 80% line coverage on `plugin`, ≥ 90% on `api`.
- Fail the build below the gates.

### 6.2 Static analysis

- Add SpotBugs or Error Prone via Gradle plugin. Fail on `ERROR`.
- Ensure no `@SuppressWarnings` without a justification comment.

### 6.3 Codebase hygiene

- Grep the codebase for `TODO`, `FIXME`, `XXX`, `HACK` — must be zero across `plugin/` and `api/`.
- No commented-out code. No dead classes (grep each public class name — must have ≥ 1 non-test caller).
- No `@Deprecated` without a replacement path documented.

### 6.4 Version + release artifact

- Bump version to `1.0.0` in `gradle.properties`.
- Generate release artifact: `Spyglass-1.0.0.jar`. Include source jar and javadoc jar via Gradle `withSourcesJar()`, `withJavadocJar()`.
- Publish to a local Maven if needed for WhisperNet etc. to depend on `spyglass-api:1.0.0`.

### 6.5 Deployment test

- Cycle v1 out of `../RP_Server/plugins/` (rename to `.disabled`).
- Cycle v2 in alone.
- Restart; verify all v1 user workflows work via RCON (logged-in player flows).
- Run `./gradlew regression` (which still includes v1 for comparison — v1 logs should be empty since it's disabled, but the test harness handles that).
- Rollback to v1+v2 coexist; `./gradlew regression` green again.

### Block 6 acceptance

- JaCoCo report on commit; coverage gates green.
- No TODOs / FIXMEs / commented-out code.
- `1.0.0` artifact built and manually verified on dev server.
- Commit: `Phase 2 block 6: production hardening — v1.0.0 ready`.

## Execution order — sequence

Do the blocks in this order. They build on each other.

1. Block 0 (repo reorg + deployToRpServer).
2. Block 1 (command refactor). Regression harness still uses RCON sequences; once Block 2 lands it gets richer.
3. Block 2 (regression harness). **Do not skip.** The remaining blocks rely on automated regression.
4. Block 3 (events) — interleave with regression: each event group gets `regression green` before moving on.
5. Block 4 (WE/FAWE).
6. Block 5 (migration + tool + polish).
7. Block 6 (hardening).

Target cadence: ~1 commit per sub-step (3.1, 3.2, ... 5.6). Each commit runs the full regression suite. Commit messages state regression status explicitly.

## When you finish

- Commit range links to `main` — either push or tag `v1.0.0`.
- Summary back to the user: block-by-block status, coverage %, any deferred items.
- `README.md` reflects the shipped product.

## Final note on judgment

The PLAN.md for Phase 1 said: "When in doubt, favor small correct pieces over big ambitious ones." That still holds. If you hit a hard dependency (e.g. FAWE's API changed, mineflayer can't simulate a specific event), **stop, document the blocker in `docs/phase2-blockers.md`, and move on**. Don't over-engineer around an obstacle. The senior dev will direct the next step.

The clean-room constraint is still primary. Never copy from v1; paraphrase from `docs/analysis/` and rewrite.
