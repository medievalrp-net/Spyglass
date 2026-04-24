# Spyglass

A Paper 1.21.8 forensic logging + rollback plugin for MedievalRP, and the API library plugins integrate against.

Successor to the MPL-licensed [v1](../v1). Built clean-room from the architectural dissection in [`docs/analysis/`](docs/analysis/00-overview.md). MIT licensed.

## Status

`v1.0.0` — feature-complete release. Ships 34 event types, block + container + entity-NBT rollback (experimental), vanilla and FAWE WorldEdit capture, the `/sg tool` inspection wand, the v1 → v2 migration command, and an automated regression harness against both v1 and v2.

## Requirements

- Paper 1.21.8 (or newer within the 1.21.x line)
- Java 21
- MongoDB reachable from the server (default `mongodb://localhost:27017`)
- Optional: WorldEdit 7.3+ or FastAsyncWorldEdit 2.15+ for `//set` / `//paste` logging

## Install (fresh)

1. Drop `Spyglass-1.0.0.jar` into `plugins/`.
2. Start the server once to generate `plugins/Spyglass/config.conf`.
3. Edit `config.conf` to point `database.uri` at your Mongo. Restart.
4. Grant `spyglass.use`, `spyglass.search`, `spyglass.rollback` to trusted roles. `spyglass.admin` is needed for the migration command.

## Upgrading from v1

Operators coming from the original MPL v1 should:

1. **Stop** the server (or at least unload the v1 plugin).
2. Rename `v1.jar` → `v1.jar.disabled` in `plugins/`.
3. Drop `Spyglass-1.0.0.jar` into `plugins/`.
4. Start the server. Spyglass boots with its own database (`Spyglass.EventRecords` by default). v1 data is untouched.
5. From the console, run `sg admin migrate-v1 --dry-run` to preview the translation counts.
6. Run `sg admin migrate-v1` for the real migration. On the MedievalRP dev corpus (384k documents) this takes about 40 seconds. Progress prints every 10k docs.
7. Verify via `sg search a:break t:30d -g` that historical data is queryable.
8. Once confident, delete `v1.jar.disabled` and `v1` from Mongo at your leisure. v1 retention TTL will cleanse it anyway.

The migration reuses the target Spyglass connection and resumes cleanly after interruption via `--resume`. Events deferred to future versions (bundle, some 1.20+ sub-types you may not have used) skip with a counted log line — they do not fail the migration.

## Build

```
./gradlew build # api + plugin jars, tests, coverage gates
./gradlew :plugin:shadowJar # just the shaded plugin jar
./gradlew deployToRpServer # jar + copy to ../RP_Server/plugins/
./gradlew regression # live regression against ../RP_Server
./gradlew :api:publishApiPublicationToLocalRepository # publish spyglass-api:1.0.0
```

## Running the regression harness

Requires Python 3.9+, `pip install --user mcrcon pymongo`, Mongo at `localhost:27017`, and `../RP_Server` with Paper + both `v1.jar` (v1) and `Spyglass.jar` (v2) in `plugins/`. See [`regression/README.md`](regression/README.md).

## Modules

- **`api/`** — public contract: typed event records, query predicates, rollback interfaces, extension points. Published separately so external plugins depend only on this (`net.medievalrp:spyglass-api:1.0.0`).
- **`plugin/`** — the Paper plugin: storage, listeners, commands, rendering, WorldEdit/FAWE integration. All internals marked `@ApiStatus.Internal`.

## Stack

- Paper 1.21.8, JDK 21
- Gradle 9 (Shadow plugin for the plugin jar)
- MongoDB via the official Java driver with POJO codec
- Kyori Adventure for all chat rendering
- Incendo Cloud 2.x for command framework

## Clean-room discipline

This project re-implements the v1 feature set without copying MPL-licensed code. The dissection docs in `docs/analysis/` describe shapes, flows, and bugs — they are the spec. Do not copy implementations from the original source tree; paraphrase from the docs and write fresh code. Rename classes and restructure packages liberally.

## Wire-format compatibility

The Mongo document shape is intentionally *similar* to v1 so the migration tool can read v1 collections and write v2 records. It is not identical — v2 adds a schema version, a typed `source` subdocument, and drops the tag-less raw-map pattern that caused the `components=null` bug.
