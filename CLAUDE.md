# Spyglass

Forensic logging and rollback plugin for Paper 1.21.x (Java 21). Records 40+ event types to MongoDB or ClickHouse, exposes a `key:value` query language over them, and rolls any of it back per-block or in bulk. Four Gradle modules with a strict inward dependency direction.

## Modules

| Module | Role | Depends on (internal) |
|--------|------|-----------|
| `spyglass-api/` | Public contract — sealed `EventRecord` / `RollbackEffect`, query DSL, extension interfaces. Third-party plugins depend on this **only**. | nothing |
| `spyglass-core/` | Shared internals — storage codecs, `RecordStore` backends (Mongo + ClickHouse). | `spyglass-api` |
| `spyglass/` | The Paper plugin — listeners, ingest pipeline, rollback engine, commands. | `spyglass-api`, `spyglass-core` |
| `spyglass-velocity/` | Optional **read-only** Velocity proxy companion (cross-server search; never writes records, never rolls back). | `spyglass-api`, `spyglass-core` |

Dependency arrows point inward. Nothing in `spyglass-api` may import a Bukkit implementation, Mongo, or ClickHouse type — keep the public surface clean and headless-testable.

## Branch model

- `main` — integration branch; releases are cut from here.
- `feat/<issue-#>-<slug>` — feature branches off `main`; merge back via PR once self-reviewed and verified.

**NEVER force-push `main` or any shared branch. NEVER rewrite published history.** Small doc/config touch-ups may land on `main` directly; anything carrying logic or tests goes through a `feat/` branch + PR.

## Issue-driven workflow

Work is issue-driven. The **`taking-issues`** skill (`.claude/skills/taking-issues/`) is the source of truth for the issue → branch → implement+test → self-review → verify → merge loop. Read it before starting any issue.

GitHub Issues live on `github.com/medievalrp-net/Spyglass`, driven through the `gh` CLI.

## Codebase orientation

First-touch reads when starting in a new area:

- [`spyglass-api/.../event/EventRecord.java`](spyglass-api/src/main/java/net/medievalrp/spyglass/api/event/EventRecord.java) — sealed record hierarchy (the data model)
- [`spyglass-api/.../event/EventCatalog.java`](spyglass-api/src/main/java/net/medievalrp/spyglass/api/event/EventCatalog.java) — authoritative event-name → record-class map
- [`spyglass/.../SpyglassPlugin.java`](spyglass/src/main/java/net/medievalrp/spyglass/plugin/SpyglassPlugin.java) — bootstrap / composition root (all wiring lives here)
- [`spyglass/.../pipeline/AsyncRecorder.java`](spyglass/src/main/java/net/medievalrp/spyglass/plugin/pipeline/AsyncRecorder.java) — async ingest pipeline
- [`spyglass-core/.../storage/RecordStore.java`](spyglass-core/src/main/java/net/medievalrp/spyglass/plugin/storage/RecordStore.java) — storage abstraction + both backends
- [`spyglass/.../rollback/RollbackEngine.java`](spyglass/src/main/java/net/medievalrp/spyglass/plugin/rollback/RollbackEngine.java) — rollback apply engine
- [`spyglass/src/main/resources/config.conf`](spyglass/src/main/resources/config.conf) — annotated config reference

Existing prose: `README.md` (architecture + ops), `API.md` (third-party integration surface), `commands.md` (command quick-reference).

## Hard rules

- **Event-type parity:** adding or changing an event type means updating, in the same change: (1) the sealed `EventRecord` permits + record class, (2) `EventCatalog`, (3) the emitting listener, (4) **both** storage paths — the Mongo codec (`EventRecordCodec`) and ClickHouse (`ClickHouseSchema` columns + `ClickHouseFieldMapper`), and (5) the default under `events.<name>` in `config.conf`. Missing any one ships a half-wired event that records on one backend and vanishes on the other.
- **Tests with code:** non-trivial changes ship a JUnit 5 test in the same commit. `./gradlew check` enforces per-module jacoco line floors (api 0.15, core 0.20, plugin 0.20). A change that drops a module below its floor fails the build — add the missing test, don't lower the floor.
- **Listeners:** record at `EventPriority.MONITOR, ignoreCancelled = true`, build an immutable record, hand it to `Recorder.record()`. Never do I/O or block on the main thread.
- **Structured logging only:** use the plugin `Logger`. No `printStackTrace` / `System.out` / `System.err`.
- **Don't break the API:** `spyglass-api` is the stable extension surface. Add capabilities; don't remove or repurpose them within a major version. Prefer `default` methods on extension interfaces.

## Build & tooling

- macOS host, `zsh`, Java 21. Gradle 9 + Shadow plugin.
- `./gradlew :spyglass:shadowJar` — plugin jar → `spyglass/build/libs/Spyglass-<version>.jar`
- `./gradlew build` — all modules: jars, tests, jacoco
- `./gradlew deployToRpServer` — build + copy to `../RP_Server/plugins/Spyglass.jar`
- `./gradlew regression` — regression harness (requires `../RP_Server` running)
- `gh` CLI for all GitHub work. The repo is **private** under `medievalrp-net`, visible only to an authed account with access — the `itdontmata` account works (`gh auth switch -u itdontmata`). With any other account active, both `gh` and `git` return "repository not found".
