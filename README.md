# Spyglass

A Paper 1.21.8 forensic logging + rollback plugin for MedievalRP, and the API library plugins integrate against.

Successor to the MPL-licensed [v1](../v1). Built clean-room from the architectural dissection in [`docs/analysis/`](docs/analysis/00-overview.md). MIT licensed.

## Status

`v0.9.0` — feature-parity release candidate. Ships 22 event types, full block + container rollback, vanilla WorldEdit capture, and an automated regression harness against both v1 and v2. See [`docs/phase2-notes.md`](docs/phase2-notes.md) for what shipped and what's deferred to Phase 3 (notably: FAWE fast-placement, v1→v2 migration tool, entity NBT rollback).

## Build

```
./gradlew build                  # api + plugin jars, tests, coverage
./gradlew :plugin:shadowJar      # just the shaded plugin jar
./gradlew deployToRpServer       # jar + copy to ../RP_Server/plugins/
./gradlew regression             # live regression against ../RP_Server
```

## Running the regression harness

Requires Python 3.9+, `pip install --user mcrcon pymongo`, Mongo at `localhost:27017`, and `../RP_Server` with Paper + both `v1.jar` (v1) and `Spyglass.jar` (v2) in `plugins/`. See [`regression/README.md`](regression/README.md).

## Modules

- **`api/`** — public contract: typed event records, query predicates, rollback interfaces, extension points. Published separately so external plugins depend only on this.
- **`plugin/`** — the Paper plugin: storage, listeners, commands, rendering, WorldEdit/FAWE integration.

## Stack

- Paper 1.21.8, JDK 21
- Gradle 9 (Shadow plugin for the plugin jar)
- MongoDB via the official Java driver with POJO codec
- Kyori Adventure for all chat rendering
- Incendo Cloud 2.x for command framework

## Clean-room discipline

This project re-implements the v1 feature set without copying MPL-licensed code. The dissection docs in `docs/analysis/` describe shapes, flows, and bugs — they are the spec. Do not copy implementations from the original source tree; paraphrase from the docs and write fresh code. Rename classes and restructure packages liberally. Where a question isn't answered by the docs, factual references (config schema, Mongo field names, event-name strings) may be checked against the original source but not transcribed.

## Wire-format compatibility

The Mongo document shape is intentionally *similar* to v1 so a migration tool can read v1 collections and write v2 records. It is not identical — v2 adds a `_v` schema version, a typed `source` subdocument, and drops the tag-less raw-map pattern that caused the `components=null` bug.
