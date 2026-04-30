# CoreProtect → Spyglass importer

Standalone CLI tool that reads a CoreProtect 20+ database and writes its
events into Spyglass's ClickHouse store. Single-shot migration tool —
runs offline against a CoreProtect SQLite file or a live MySQL CoreProtect
DB; ClickHouse is written via the same path the live Spyglass plugin uses.

Source lives in [`spyglass-importer/`](../spyglass-importer/). Built into a
fat jar via `./gradlew :spyglass-importer:shadowJar`.

## Quick start

```sh
# Build the jar.
./gradlew :spyglass-importer:shadowJar

# Bring up ClickHouse and run the import via Docker.
docker compose -f docker/docker-compose.yml up -d clickhouse
docker compose -f docker/docker-compose.yml --profile import run --rm importer
```

The compose file ships defaults aimed at a fixture under
`./coreprotect-test/` — a SQLite DB plus a worlds directory with
`uid.dat`. Edit `docker-compose.yml` to point at a real CoreProtect DB.

For a host-side run (no Docker):

```sh
java -jar spyglass-importer/build/libs/spyglass-importer-*.jar import \
    --source ./database.db \
    --worlds-dir ./worlds \
    --server-name my-server \
    --clickhouse-host localhost
```

## CLI surface

The jar exposes three subcommands:

```
spyglass-importer import   ...    # load a CoreProtect DB into Spyglass
spyglass-importer validate ...    # audit row counts after an import
spyglass-importer bench    ...    # compare query latency between backends
```

### import

```
spyglass-importer import [options]

Required:
  --source <path-or-url>      CoreProtect SQLite file path, or a MySQL URL
                              of the form mysql://user:pass@host:port/db.
  --worlds-dir <path>         Directory containing per-world subfolders
                              with uid.dat. Importer scans <dir>/<world>/uid.dat
                              for every world referenced by the source.
  --server-name <name>        Stamped onto every imported record's `server`
                              field so srv:<name> queries partition cleanly.

ClickHouse target:
  --clickhouse-host           default localhost
  --clickhouse-port           default 8123
  --clickhouse-database       default spyglass
  --clickhouse-table          default event_records
  --clickhouse-user           default default
  --clickhouse-password       default empty
  --clickhouse-ssl            default false

Tuning:
  --retention <n><h|d|w>      How long imported rows stay queryable from
                              import time (default 30d). NOTE: relative to
                              import time, NOT event time — historical
                              CoreProtect data would otherwise TTL-evict
                              instantly.
  --batch-size <n>            Rows per ClickHouse insert batch (default 10000)
  --progress-interval <n>     Print a progress line every N rows (default 50000)
  --dry-run                   Stream + map every row but skip ClickHouse;
                              useful for validating a source DB before a
                              real import.
```

### validate

After an import, audit per-table row counts to confirm the migration
landed cleanly:

```sh
spyglass-importer validate \
    --coreprotect-sqlite ./database.db \
    --clickhouse-host localhost \
    --server-name my-server
```

Or via Docker:

```sh
docker compose -f docker/docker-compose.yml --profile validate run --rm validate
```

For each known event category, the validator runs equivalent count
queries against both backends and reports the delta. Output looks like:

```
[OK  ] blocks_break_place_use            CP=864,639  Spy=864,639  delta=+0
[OK  ] blocks_kills                      CP=305      Spy=305      delta=+0
[OK  ] sessions_join_quit                CP=1,780    Spy=1,780    delta=+0
[OK  ] chat_messages                     CP=6,298    Spy=6,298    delta=+0
[WARN] items_other_skipped               CP=5,655    Spy=0        delta=+5,655
       These actions (durability break, lava destroy, …) have no
       Spyglass record type. The delta IS the count of skipped rows
       — not a problem.

Validation PASSED. All counts within tolerance.
```

Exit code is 0 when every check is `OK` or `WARN`, 1 if any check is
`FAIL` (delta exceeded its tolerated bound). Run as the post-import
gate in a deployment script.

### bench

See [`coreprotect-vs-spyglass-bench.md`](report/coreprotect-vs-spyglass-bench.md)
for the head-to-head query latency comparison.

## What gets imported

| CoreProtect table | Spyglass record(s) |
|---|---|
| `co_block` action 0 | `BlockBreakRecord` (event=`break`) |
| `co_block` action 1 | `BlockPlaceRecord` (event=`place`) |
| `co_block` action 2 | `BlockUseRecord` (event=`use`) |
| `co_block` action 3 | `EntityDeathRecord` (event=`death`) — kills overload `co_block` in CoreProtect |
| `co_session` action 0 | `QuitRecord` (event=`quit`) |
| `co_session` action 1 | `JoinRecord` (event=`join`) — IP is null (CoreProtect doesn't store IPs) |
| `co_chat` | `ChatRecord` (event=`say`) |
| `co_command` | `CommandRecord` (event=`command`) |
| `co_container` action 0 | `ContainerWithdrawRecord` (event=`withdraw`) |
| `co_container` action 1 | `ContainerDepositRecord` (event=`deposit`) |
| `co_item` action 2 | `ItemDropRecord` (event=`drop`) |
| `co_item` action 3 | `ItemPickupRecord` (event=`pickup`) |

## What's NOT imported (known gaps)

Each gap below has a tracked issue in the task list and a comment in
the code at the relevant skip point.

### `co_sign` — sign edits in-place

CoreProtect records right-click sign edits in a separate `co_sign`
table. Spyglass has no sealed `SignEditRecord` type — sign content can
only ride on `BlockBreakRecord` / `BlockPlaceRecord` snapshots.
Importing `co_sign` cleanly needs a new event type added to
`spyglass-api`'s sealed `EventRecord` permits.

Workaround: signs that were broken/placed (not edited in place) ARE
captured via `co_block` and their content rides on the break snapshot.

### 11 of 13 `co_item` action codes

CoreProtect's `co_item.action` has 13 values (`ITEM_REMOVE`,
`ITEM_THROW`, `ITEM_BREAK`, `ITEM_DESTROY`, `ITEM_CREATE`,
`ITEM_SELL`/`BUY`, ender-chest variants, etc.). Spyglass's
`ItemDropRecord` / `ItemPickupRecord` only cover the drop and pickup
shapes. The other 11 codes get logged as `UNKNOWN_ACTION` skips with a
per-code histogram in the import summary. None map cleanly to existing
Spyglass record types.

In a typical dataset these are 0.5%–1% of `co_item` rows.

### IP addresses on join events

CoreProtect simply does not store IP addresses anywhere — confirmed
from a full grep of the upstream `database/` and `consumer/` source
trees. Imported `JoinRecord.address` is therefore always `null`, and
Spyglass's `i:<ip>` cross-event correlation can't be reconstructed from
imported data.

If this matters, capture IPs from a separate source (Velocity proxy
log, network appliance) and merge them in post-import. We don't ship
tooling for that.

### `co_block.rolled_back` flag

CoreProtect tracks a `rolled_back` bitfield (was-rolled-back / was-rolled-
back-and-restored). The importer reads it but doesn't yet act on it —
all rows go in regardless of CoreProtect's rollback state. Spyglass's
own rollback subsystem is an independent system; imported rows are
forensic record only and aren't replayable through Spyglass's rollback
engine.

## Re-running an import safely

Re-running the importer against the same dataset is **idempotent**.
Every row is keyed by a deterministic UUID v3 derived from
`(coreprotect_table, rowid)`, and the ClickHouse target is a
`ReplacingMergeTree`. So duplicate inserts collapse on the next part
merge.

For "force a re-import from scratch":

```sh
docker exec spyglass-clickhouse clickhouse-client \
    --database=spyglass \
    --query='TRUNCATE TABLE event_records;'
```

## Validating before importing

```sh
docker compose -f docker/docker-compose.yml --profile import \
    run --rm importer --dry-run
```

Streams every row through the mapper without writing to ClickHouse.
Reports per-table read/written/skipped, per-event counts, and the
action-code histogram for any UNKNOWN_ACTION skips. Confirms world
UUIDs all resolve and the schema is CoreProtect 20+.

## MySQL source

`--source mysql://user:password@host:port/database` opens a live
CoreProtect MySQL/MariaDB schema. The reader uses `useCursorFetch=true`
so a multi-million-row table cursors instead of buffering client-side.
Connect-time charset negotiation is left to Connector/J's defaults
(passing `characterEncoding` here was a past regression — Connector/J
expects Java charset names there, not MySQL collation names).

A self-contained MariaDB fixture under
[`docker/mysql-init/`](../docker/mysql-init/) provides a synthetic
CoreProtect schema + ~14 sample rows for end-to-end smoke testing of
the MySQL code path:

```sh
./gradlew :spyglass-importer:shadowJar
docker compose -f docker/docker-compose.yml --profile mysql-import \
    run --rm importer-mysql
```

Brings up MariaDB with the fixture, then runs the importer with
`--source mysql://...` against it. Every event type — including
action-3 kills — round-trips correctly.

## Performance

On a real 115 MB / 1.3 M-row CoreProtect SQLite:
- **dry-run** (no writes): ~6 seconds (~220 k rows/sec)
- **real import** (RowBinary writes to ClickHouse via HTTP): ~26 seconds
  (~50 k rows/sec, dominated by ClickHouse insert acks and BSON
  encoding for nested item snapshots).

For comparison the legacy v1 → v2 migration path was fundamentally
non-existent (v2 used a different schema and we wrote no migrator).
This importer is the first time historical CoreProtect data can land
in Spyglass without re-recording from a live server.

## Related docs

- [`docker/README.md`](../docker/README.md) — Docker rig usage
- [`coreprotect-vs-spyglass-bench.md`](report/coreprotect-vs-spyglass-bench.md) — query latency comparison on the imported dataset
- [`ingest-bench-results.md`](report/ingest-bench-results.md) — Spyglass ingest pipeline bench (separate from the importer)
