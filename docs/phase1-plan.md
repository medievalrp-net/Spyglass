# Spyglass — Codex handoff plan (Phase 1)

## Context

Spyglass is a clean-room rewrite of the MPL-licensed v1 plugin for Paper 1.21.8. It's a forensic logging + rollback plugin for a Minecraft server, paired with a public API module that other in-house plugins (WhisperNet, Cauldron, etc.) integrate against.

The repo at `/Volumes/External-NVME/Documents/GitHub/MedievalRP/Spyglass` contains:
- `docs/analysis/` — 11 markdown files dissecting the original v1 plugin. These are your **primary spec**. Start with `00-overview.md` and `10-modernization-hotspots.md`.
- `LICENSE` (MIT), `README.md`, `.gitignore`, empty git repo on `main`.

The original source is at `../v1/`. **Do not open its source files** unless the dissection docs don't answer a factual question (e.g. the full list of event-name strings, the Mongo field names). Never copy implementation code. Always paraphrase, rename, and rewrite from the docs.

## Clean-room rules (non-negotiable)

1. All code is new. No copy-paste from `../v1/`. Write from the dissection docs.
2. Rename everything: `DataEntry → EventRecord`, `OEntry → Recorder` + typed record constructors, `DataWrapper → gone` (Mongo POJO codec replaces it), `OmniCore → SpyglassPlugin`, `OmniApi → SpyglassApi`, etc.
3. Package prefix: `net.medievalrp.spyglass.*` (never `.v1.`).
4. Command: `/omniv2` (aliases `/o2`, `/spyglass`). Never `/omni`.
5. Mongo collection: `EventRecords`. Do not read/write `DataEntry` (v1's collection).
6. Plugin name in `plugin.yml`: `Spyglass`. Separate `plugins/` entry from v1.
7. If the dissection docs don't cover a fact you need, it's fine to grep `../v1/` for specific strings (event names, config keys, Mongo field names) — but not to read implementation code.

## Phase 1 scope

Build a working foundation + one complete vertical slice: block break/place events flowing through Mongo, searchable and rollback-able. Plus enough supporting events (chat, join, quit, command, container deposit/withdraw) that the plugin is immediately useful.

### Out of scope for Phase 1 (mark as TODO, do not implement)

- Entity events (death, hit, shot, mount, etc.) and entity rollback via NBT reflection
- WorldEdit / FastAsyncWorldEdit integration
- AI query assistant
- Uncommon events: decay, grow, form, ignite, bookshelf, pot, brush, sculk, crafter, vault, shulker, bundle, drop, pickup, teleport
- Uncommon parameters: `ip`, `cu`, `trg`, `n`, `d`, `srv`, `rcp`, `m`
- Display handlers beyond the default renderer

These all get later phases. Make the event pipeline, parameter registry, and display-handler interface extensible enough that adding them is "write a record + extractor + register" rather than touching core code.

## Stack

- **JDK 21 toolchain** via Gradle toolchain spec
- **Gradle 9** multi-module (root + `api` + `plugin`)
- **Paper 1.21.8**: `io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT` (repo: `https://repo.papermc.io/repository/maven-public/`)
- **Mongo Java driver 5.x** with sync client + POJO codec (`org.mongodb:mongodb-driver-sync`, `org.mongodb:bson-record-codec`)
- **Kyori Adventure** (bundled in Paper; no separate dep needed for components)
- **Incendo Cloud 2.x** Paper integration: `org.incendo:cloud-paper` + `org.incendo:cloud-minecraft-extras` (repo: `https://repo.papermc.io/repository/maven-public/` or central)
- **Configurate 4.x** (HOCON) for config: `org.spongepowered:configurate-hocon` — matches the MedievalRP house style
- **JUnit Jupiter 5** for tests, AssertJ for assertions, Testcontainers for Mongo integration tests: `org.testcontainers:mongodb`
- **Shadow plugin** for the plugin jar: `com.gradleup.shadow` v8.x

FAWE jar should be added *later* (Phase 2). Phase 1 has no WE/FAWE compile dep.

## Repo structure

```
Spyglass/
├── settings.gradle.kts              ← rootProject.name = 'Spyglass', include 'api', 'plugin'
├── build.gradle.kts                 ← allprojects config: group, version, JDK 21, repos
├── gradle.properties                ← version = 0.1.0-SNAPSHOT
├── api/
│   ├── build.gradle.kts             ← java-library
│   └── src/main/java/net/medievalrp/spyglass/api/
│       ├── SpyglassApi.java     ← service interface, ServicesManager-registered
│       ├── event/
│       │   ├── EventRecord.java     ← sealed
│       │   ├── BlockBreakRecord.java
│       │   ├── BlockPlaceRecord.java
│       │   ├── ChatRecord.java
│       │   ├── CommandRecord.java
│       │   ├── JoinRecord.java
│       │   ├── QuitRecord.java
│       │   ├── ContainerDepositRecord.java
│       │   ├── ContainerWithdrawRecord.java
│       │   ├── Origin.java          ← sealed: Player, WorldEdit, Fawe, Plugin, Environment
│       │   └── Source.java          ← sealed: PlayerSource, EntitySource, PluginSource, ConsoleSource, CommandBlockSource, EnvironmentSource
│       ├── query/
│       │   ├── QueryPredicate.java  ← sealed: Eq, In, Range, Exists, Not, And, Or
│       │   ├── QueryRequest.java    ← record(predicates, sort, limit, flags, grouping)
│       │   ├── QueryResult.java     ← record(records, aggregations)
│       │   ├── Flag.java            ← enum: NO_GROUP, GLOBAL, NO_CHAT, EXTENDED, DRAIN
│       │   └── Sort.java            ← enum
│       ├── param/
│       │   ├── QueryParamHandler.java   ← contract for /omniv2 search a:break p:x parsing
│       │   └── ParamParseException.java
│       ├── rollback/
│       │   ├── Rollbackable.java    ← interface
│       │   ├── RollbackResult.java  ← sealed: Applied | Skipped
│       │   ├── RollbackReason.java  ← sealed: InvalidLocation, BlockChanged, MissingData, etc.
│       │   └── RollbackEffect.java  ← sealed: BlockReplace, ContainerSlotWrite, EntitySpawn, EntityRemove
│       ├── extension/
│       │   ├── EventExtractor.java  ← Extractor<E extends Event, R extends EventRecord>
│       │   └── DisplayRenderer.java ← interface for per-record display overrides
│       └── util/
│           ├── Duration.java        ← record(seconds), with parse("4w3d") helper
│           └── BlockLocation.java   ← record(worldId, worldName, x, y, z)
├── plugin/
│   ├── build.gradle.kts             ← applies shadow, depends on :api
│   └── src/
│       ├── main/
│       │   ├── java/net/medievalrp/spyglass/plugin/
│       │   │   ├── SpyglassPlugin.java    ← JavaPlugin
│       │   │   ├── config/
│       │   │   │   └── SpyglassConfig.java   ← record tree, loaded via Configurate
│       │   │   ├── storage/
│       │   │   │   ├── RecordStore.java       ← interface: save(List<EventRecord>), query(QueryRequest)
│       │   │   │   ├── MongoRecordStore.java  ← POJO codec impl
│       │   │   │   ├── IndexManager.java      ← creates compound indexes at startup
│       │   │   │   └── PredicateToBson.java   ← QueryPredicate → org.bson.conversions.Bson
│       │   │   ├── pipeline/
│       │   │   │   ├── Recorder.java          ← interface (the clean successor to OEntry)
│       │   │   │   ├── AsyncRecorder.java     ← unbounded queue (no intake drops) + virtual-thread drain + warn threshold
│       │   │   │   └── ExtractorRegistry.java
│       │   │   ├── listener/
│       │   │   │   ├── block/
│       │   │   │   │   ├── BlockBreakExtractor.java
│       │   │   │   │   └── BlockPlaceExtractor.java
│       │   │   │   ├── container/
│       │   │   │   │   └── ContainerTransactionExtractor.java  ← InventoryClickEvent → deposit/withdraw
│       │   │   │   ├── chat/
│       │   │   │   │   ├── ChatExtractor.java
│       │   │   │   │   └── CommandExtractor.java
│       │   │   │   └── player/
│       │   │   │       ├── JoinExtractor.java
│       │   │   │       └── QuitExtractor.java
│       │   │   ├── command/
│       │   │   │   ├── OmniCommand.java       ← Cloud annotation-based
│       │   │   │   ├── render/
│       │   │   │   │   ├── ResultRenderer.java  ← Adventure Component output
│       │   │   │   │   └── Messages.java        ← MiniMessage templates from messages.conf
│       │   │   │   ├── param/
│       │   │   │   │   ├── PlayerParam.java, EventParam.java, RadiusParam.java,
│       │   │   │   │   ├── TimeParam.java, BlockParam.java, EntityParam.java, WorldParam.java
│       │   │   │   │   └── FlagParam.java       ← -ng / -g / -nc parser
│       │   │   │   └── PageCache.java         ← bounded, TTL'd; listens to PlayerQuitEvent
│       │   │   ├── rollback/
│       │   │   │   ├── RollbackEngine.java    ← sealed EntryEffect dispatch
│       │   │   │   └── UndoStack.java         ← persisted per-player (Mongo), replaces v1's in-memory map
│       │   │   └── api/
│       │   │       └── SpyglassApiImpl.java   ← registered via Bukkit ServicesManager
│       │   └── resources/
│       │       ├── plugin.yml                 ← name: Spyglass, main: ..., depend: [], softdepend: []
│       │       ├── config.conf                ← default Configurate HOCON
│       │       └── messages.conf              ← MiniMessage templates
│       └── test/java/net/medievalrp/spyglass/plugin/
│           ├── util/DurationTest.java
│           ├── storage/PredicateToBsonTest.java
│           ├── storage/MongoRecordStoreIT.java   ← Testcontainers integration
│           ├── pipeline/AsyncRecorderTest.java
│           └── listener/*ExtractorTest.java
└── docs/
    ├── analysis/          ← existing dissection (spec)
    └── (future)
```

## Architectural commitments

Match these exactly. They are the difference from v1.

1. **Typed event records.** Each event is a Java `record` implementing a sealed `EventRecord` interface. Fields are typed (`UUID`, `Instant`, `BlockLocation`, `Material`, `String`, nested records). No `DataWrapper`, no stringly-typed maps.
2. **Mongo POJO codec.** Use `PojoCodecProvider` + `@BsonDiscriminator` on the sealed parent to auto-serialize records. Add a `_v` schema version field per document. Do not hand-walk documents.
3. **Sealed `QueryPredicate`.** Exhaustive `switch` in the Mongo translation layer.
4. **Adventure everywhere.** Every rendered line is a `net.kyori.adventure.text.Component`. No `net.md_5.bungee.*`, no `org.bukkit.ChatColor`. Use `MiniMessage` for templates.
5. **Service-based API surface.** `SpyglassApi` is a plain interface registered via `Bukkit.getServicesManager().register(...)`. No static singletons. External plugins do `Bukkit.getServicesManager().load(SpyglassApi.class)`.
6. **Constructor injection.** Every listener/command/handler takes its dependencies via constructor. No global state.
7. **Config as a record tree** loaded via Configurate. No `FileConfiguration` reads scattered across the codebase.
8. **Virtual threads for the async queue drain.** `Thread.ofVirtual().name("omni2-drain").start(...)`. No Bukkit async scheduler for the drain thread. Keep listener `save()` calls O(1) via `LinkedBlockingDeque.offer` on an unbounded queue — `record()` never rejects, same no-drop contract as v1. `queue-capacity` is a **warn threshold**: crossing it logs a warning and doubling intervals thereafter, so a backlog is surfaced before heap pressure becomes severe.
9. **`onDisable` flushes the queue** with a timeout. No silent data loss.
10. **Indexes upfront.** `IndexManager.ensureIndexes(collection)` on startup:
    - `{ source.playerId: 1, occurred: -1 }`
    - `{ event: 1, occurred: -1 }`
    - `{ "location.worldId": 1, "location.x": 1, "location.z": 1, "location.y": 1, occurred: -1 }`
    - `{ expiresAt: 1 }` (TTL, `expireAfterSeconds: 0`)

## Events in Phase 1

Map original event names to new records. Keep the wire-format `event` string stable for future migration tooling.

| v1 event name | New record | Bukkit source |
|---|---|---|
| `break` | `BlockBreakRecord` | `BlockBreakEvent`, `BlockExplodeEvent`, `EntityExplodeEvent` |
| `place` | `BlockPlaceRecord` | `BlockPlaceEvent`, `BlockMultiPlaceEvent` |
| `deposit` | `ContainerDepositRecord` | `InventoryClickEvent`, `InventoryDragEvent` (fix v1's missing `.save()` bug) |
| `withdraw` | `ContainerWithdrawRecord` | same |
| `say` | `ChatRecord` | `AsyncChatEvent` |
| `command` | `CommandRecord` | `PlayerCommandPreprocessEvent`, `ServerCommandEvent` |
| `join` | `JoinRecord` | `PlayerJoinEvent` |
| `quit` | `QuitRecord` | `PlayerQuitEvent` |

Capture full tile-entity state for break events (container inventory, sign text, banner patterns, jukebox disc) by reading the live `BlockState` *before* the block is replaced — same trick as the original (see `docs/analysis/03-events-and-entries.md` and `07-worldedit-integration.md`). Serialize Bukkit `ItemStack` via `ItemStack.serializeAsBytes()` → Base64 string (Paper 1.20.5+ API, component-safe). Do NOT use `ConfigurationSerialization` paths; `serializeAsBytes` avoids the entire `components=null` bug class.

## Commands

Cloud annotation-based. Subcommands on `/omniv2` (aliases `/o2`, `/spyglass`):

- `help` — list commands
- `search <params...>` — run a lookup, render pages. Pagination via `/omniv2 page <n>`.
- `rollback <params...>` — reverse matching block events. Force `NO_GROUP`.
- `restore <params...>` — re-apply matching block events forward.
- `undo` — reverse the invoker's last rollback/restore, via persisted `UndoStack`.
- `page <n>` — show page of last search.
- `tool` — toggle the inspection wand.
- `events` — list enabled events.

Parameters in Phase 1: `p:` `a:` `r:` `t:` `b:` `e:` `w:`.
Flags in Phase 1: `-ng` `-g` `-nc`.

Param parsing goes through Cloud's `ArgumentParser` system so Brigadier tab-completion is first-class. Don't re-implement a custom tokenizer.

## Rollback in Phase 1

Only `BlockBreakRecord` and `BlockPlaceRecord` implement `Rollbackable` this pass. `ContainerDepositRecord`/`ContainerWithdrawRecord` can wait for Phase 2.

Rollback dispatch is a sealed-type switch in `RollbackEngine`:

```java
RollbackResult apply(RollbackEffect effect, World world) {
    return switch (effect) {
        case BlockReplace br -> applyBlockReplace(br, world);
        case ContainerSlotWrite w -> applySlotWrite(w, world);
        // ...
    };
}
```

Every rollback operation runs on the main thread via `Bukkit.getScheduler().runTask(plugin, ...)`. No async world writes. Progress reported via `Player.sendActionBar(...)` every 500 entries.

`UndoStack` persists the last N operations per-player in Mongo (collection `UndoHistory`) with a 24h TTL. Survives restart. See `docs/analysis/06-rollback.md` pain points #7 and #8 for the problem being solved.

## Config (HOCON)

```hocon
database {
  uri = "mongodb://localhost:27017"
  name = "Spyglass"
  collection = "EventRecords"
}
storage {
  retention = "4w"         # records expire after this (Mongo TTL)
  queue-capacity = 100000  # WARN threshold for the ingest queue (unbounded, never drops at intake — same contract as v1)
  flush-timeout = "5s"
}
defaults {
  enabled = true
  radius = 5
  time = "3d"
}
limits {
  max-radius = 250
  search-result = 1000
  rollback-result = 10000
  chat-dump = 50           # cap skip-reason dumps per command
}
events {
  break = { enabled = true, past-tense = "broke" }
  place = { enabled = true, past-tense = "placed" }
  deposit = { enabled = true, past-tense = "deposited" }
  # ...
}
```

Config is loaded once into a record tree at enable time. Reload via `/omniv2 reload` (optional; fine to punt to Phase 2).

## Testing (the bar)

Every concrete class under `plugin/src/main/java` needs either unit tests or integration tests. JUnit 5, AssertJ, Mockito where useful, MockBukkit or a Paper mock harness for listener tests, Testcontainers for Mongo integration.

Required tests (minimum):

1. `DurationTest` — parse every unit (`s/m/h/d/w`), combinations (`4w3d`), invalid input, overflow.
2. `PredicateToBsonTest` — every `QueryPredicate` variant produces the expected BSON.
3. `MongoRecordStoreIT` (Testcontainers) — round-trip each record type through Mongo, verify indexes exist.
4. `AsyncRecorderTest` — enqueue, drain, backpressure (queue full), flush on shutdown.
5. `BlockBreakExtractorTest` — synthetic `BlockBreakEvent` produces the correct record with tile entity NBT preserved.
6. `RollbackEngineTest` — each `RollbackEffect` variant applies correctly against a mocked world.
7. `OmniCommandTest` — each command parses params + flags correctly, handles permission denials.
8. `ResultRendererTest` — rendering a record produces the expected `Component`, including hover and click events.

Run `./gradlew test` — all green. No commented-out tests.

## Live verification

After `./gradlew shadowJar` produces `plugin/build/libs/Spyglass-<version>.jar`:

1. Rename the existing `/Volumes/External-NVME/Documents/GitHub/MedievalRP/RP_Server/plugins/v1.jar` → `v1.jar.disabled` so it doesn't conflict.
2. Copy the new jar to `RP_Server/plugins/Spyglass.jar`.
3. Start the server: `cd RP_Server && ./start-testable.sh` (uses a FIFO at `/tmp/rpserver-cmd`).
4. RCON is enabled on port 25576, password `test123`. Use `mcrcon` (Python: `pip install mcrcon` or Go binary) to send test commands.
5. Run the test matrix:
   - `/op <admin>` — grant yourself ops once
   - `/omniv2 help` — command registers
   - Place/break a block in-world → `/omniv2 search a:place r:5 t:10s` — entry shows up
   - Break a chest with items → `/omniv2 rollback a:break p:<you> t:1m` — block back with contents
   - `/omniv2 undo` — block gone again
   - Send a chat message → `/omniv2 search a:say p:<you> -ng` — message appears in-line
   - Stop the server with pending entries — verify log shows flush count
6. On every test failure, fix and re-deploy. Document any deviations in `docs/phase1-notes.md`.

## Deliverables

1. All Phase 1 code committed to `main` in small, topic-scoped commits (one per subsystem ideally).
2. `./gradlew build` green (including tests).
3. `Spyglass.jar` built and manually verified via RCON.
4. `docs/phase1-notes.md` recording any deviations, decisions, or discovered bugs.
5. `docs/phase2-plan.md` listing what's deferred (entity events, WE/FAWE, AI, remaining parameters/flags), in priority order.
6. README updated if any public contract choices changed.

## Concluding note to Codex

When in doubt, favor small correct pieces over big ambitious ones. It's better to ship 8 solid events than 20 half-wired ones. The dissection docs identify specific anti-patterns in v1 — most of them involve reaching for an escape hatch when the typed path didn't fit. In v2, if the typed path doesn't fit, the typed path is wrong. Fix it. Don't add a `.with(DataKey, Object)` escape hatch.

When you finish Phase 1 and hand back, the senior dev (me) will verify in the game and plan Phase 2 with you.
