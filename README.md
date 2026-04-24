# Spyglass

A Paper 1.21.8 forensic logging + rollback plugin for MedievalRP, and the API library plugins integrate against.

Successor to the MPL-licensed [v1](../v1). Built clean-room from the architectural dissection in [`docs/analysis/`](docs/analysis/00-overview.md). MIT licensed.

## Status

`v1.0.0` — feature-complete release. Ships 38 event types, block + container + entity-NBT rollback (experimental), vanilla and FAWE WorldEdit capture, the `/omni2 tool` inspection wand, and an automated regression harness.

## Requirements

- Paper 1.21.8 (or newer within the 1.21.x line)
- Java 21
- MongoDB reachable from the server (default `mongodb://localhost:27017`)
- Optional: WorldEdit 7.3+ or FastAsyncWorldEdit 2.15+ for `//set` / `//paste` logging

## Install (fresh)

1. Drop `Spyglass-1.0.0.jar` into `plugins/`.
2. Start the server once to generate `plugins/Spyglass/config.conf`.
3. Edit `config.conf` to point `database.uri` at your Mongo. Restart.
4. Grant `spyglass.use`, `spyglass.search`, `spyglass.rollback`, `spyglass.tool` to trusted roles.

## Relationship to the legacy MPL v1

This plugin is a fresh install with no migration path from the legacy MPL v1. See the sibling `v1/` repo for historical reference only — v1's `DataEntry` collection in Mongo is untouched and can stay around for archival, but v2 writes to its own `EventRecords` collection and does not read v1 data.

## Build

```
./gradlew build                  # api + plugin jars, tests, coverage gates
./gradlew :plugin:shadowJar      # just the shaded plugin jar
./gradlew deployToRpServer       # jar + copy to ../RP_Server/plugins/
./gradlew regression             # live regression against ../RP_Server
./gradlew :api:publishApiPublicationToLocalRepository  # publish spyglass-api:1.0.0
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

