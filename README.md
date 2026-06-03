# Spyglass

Forensic logging and rollback for Paper 1.21.x. Tracks every block, container, chat, command, combat, and movement event, lets staff search through them with a flexible query language, and can roll any of it back per-block or in bulk.

## Status

`v1.0.0` - feature-complete. Ships 40+ event types, block + container + entity-NBT rollback, vanilla and FAWE WorldEdit capture, the `/spyglass tool` inspection wand, an undo stack, a job queue with crash-resume, and a public API for third-party integration.

## Requirements

- Paper 1.21.8 (or newer 1.21.x)
- Java 21
- One of:
  - MongoDB at `mongodb://localhost:27017` (default)
  - ClickHouse (set `database.backend = "clickhouse"` in config)
- Optional: WorldEdit 7.3+ or FastAsyncWorldEdit 2.15+ for `//set` / `//paste` capture and `-we` queries

## Install

1. Drop `Spyglass-1.0.0.jar` into `plugins/`.
2. Start the server once. This generates `plugins/Spyglass/config.conf`.
3. Edit `config.conf`. At minimum:
   - Point `database.uri` (Mongo) or `database.clickhouse.*` (ClickHouse) at your DB.
   - Grant `spyglass.*` permissions to trusted roles (see below).
4. Restart.

## Permissions

All default to `op`. Grant via your permissions plugin to scope access.

| Permission | What it unlocks |
|---|---|
| `spyglass.use` | `/spyglass help`, `/spyglass events`, `/spyglass page` |
| `spyglass.search` | `/spyglass search` |
| `spyglass.rollback` | `/spyglass rollback`, `/spyglass restore`, `/spyglass undo`, `/spyglass rbqueue` |
| `spyglass.tool` | `/spyglass tool` (inspection wand) |
| `spyglass.tele` | `/spyglass tele` (jump to a search result) |
| `spyglass.worldedit` | Allows the `-we` flag (uses your WE selection as the search region) |

## Commands

Root: `/spyglass`. Short alias: `/sg`. All subcommands accept short aliases too (`/sg s` = `/sg search`).

| Command | Aliases | What it does |
|---|---|---|
| `/sg help` | `h`, `?` | Command help |
| `/sg events` | `e` | List enabled event types |
| `/sg search <query>` | `s`, `sc`, `lookup`, `l` | Search the event log |
| `/sg page <n>` | `p`, `pg` | Page through the last search result |
| `/sg rollback <query>` | `rb`, `roll` | Revert matched events |
| `/sg restore <query>` | `rs`, `rst` | Re-apply previously-rolled-back events |
| `/sg undo` | `u` | Undo your last rollback / restore |
| `/sg rbqueue [...]` | `queue`, `rbq` | List, cancel, or resume rollback jobs |
| `/sg tool` | `t`, `inspect` | Toggle the inspection wand |
| `/sg tele <world> <x> <y> <z>` | - | Teleport (used by clickable search results) |

### Query syntax

Search, rollback, and restore all take the same `key:value` query language. Combine as many keys as you want; results match all of them.

```
/sg search p:Steve b:diamond_ore t:1d r:50
```

Reads: "every diamond-ore-related event Steve caused in the last day within 50 blocks of me."

#### Query keys

| Key | Aliases | Example | Notes |
|---|---|---|---|
| `p:` | `player:` | `p:Steve` or `p:Steve,Alex` | Comma-separated for OR |
| `a:` | `action:`, `event:` | `a:break,place` | Event type. See `/sg events` for the live list |
| `b:` | `block:` | `b:diamond_ore` | Target block material |
| `i:` | `item:` | `i:netherite_sword` | Item material involved (drop, pickup, container, etc.) |
| `iname:` | `itemname:` | `iname:Excalibur` | Item display name (substring match) |
| `ilore:` | `itemlore:`, `d:` | `ilore:cursed` | Item lore line (substring) |
| `ench:` | `enchant:`, `enchantment:` | `ench:sharpness` | Item enchantment |
| `cu:` | `custom:` | `cu:my-custom-item` | Plugin custom-item id (via the API) |
| `e:` | `entity:` | `e:creeper` | Entity type involved |
| `c:` | `cause:` | `c:explosion` | Change cause (e.g. `explosion`, `burn`, `decay`) |
| `m:` | `message:` | `m:hello` | Chat / sign / book text (substring) |
| `rcp:` | `recipient:` | `rcp:Steve` | Private-message recipient |
| `r:` | `radius:` | `r:50` | Search radius around you (blocks). Default radius applies if omitted; use `-g` for global |
| `t:` | `since:` | `t:1d`, `t:30m`, `t:2w` | Time window. Default is 4h |
| `w:` | `world:` | `w:world_nether` | Restrict to one world |
| `srv:` | `server:` | `srv:survival` | Restrict to one server name (multi-server setups) |
| `trg:` | `target:` | `trg:100,64,200` | Specific block coords |
| `ip:` | - | `ip:192.168.1.10` | Source IP (requires the IP module enabled) |

#### Flags

Flags start with `-` and don't take a value (unless noted).

| Flag | Aliases | Effect |
|---|---|---|
| `-g` | `-global` | Skip the default radius. Whole-world / whole-server search |
| `-we` | `-worldedit` | Use your active WorldEdit selection as the region |
| `-ord:<asc\|desc>` | `-order:` | Sort order. Default: newest first for search/rollback, oldest first for restore |
| `-ng` | `-nogroup` | Don't merge duplicate adjacent events in the result list |
| `-nc` | `-nochat` | Don't echo the summary line to chat (action-bar only) |
| `-ex` | `-extended` | Include extra detail columns in the result list |
| `-nod:<keys>` | `-nodefault:` | Suppress defaults. Example: `-nod:r,t` runs unbounded |

#### Time formats

`30s`, `15m`, `4h`, `2d`, `1w`, `1mo`. Combine: `t:1d12h`.

### Inspection wand

```
/sg tool
```

Toggles an inspection mode. Left-click a block to see what happened to it. Right-click to see what's about to happen near it. Toggle off the same way.

### Rollback workflow

```
/sg rollback p:griefer t:6h r:100
```

Reverts everything that player did in the last 6 hours within 100 blocks. Behavior:

1. The request is queued. Only one rollback runs at a time; others wait.
2. Each page of results applies in tick-budgeted batches so the server stays at ~20 TPS.
3. When it finishes, you get a summary: blocks reverted, chunks touched, time taken.
4. The inverse is saved on your personal undo stack (capped; very large rollbacks may not be undoable).

To take it back:

```
/sg undo
```

To redo:

```
/sg restore p:griefer t:6h r:100
```

`restore` re-applies the original event from the log, in case `undo` wasn't enough or you only meant to roll back a subset.

### Managing the queue

```
/sg rbqueue              # list in-flight, pending, recent, and resumable jobs
/sg rbqueue stop         # cancel the running job (current chunk finishes, then it stops)
/sg rbqueue cancel <id>  # cancel a pending or in-flight job by short id
/sg rbqueue resume <id>  # re-run a job that was interrupted by a crash or restart
```

If the JVM crashes mid-rollback, on next startup `/sg rbqueue` shows the interrupted job as resumable. The original query gets re-run from a saved cursor, so a 2M-block job that crashed at 75% only re-applies the remaining 25%.

## Configuration

`plugins/Spyglass/config.conf` has the full annotated reference. The knobs you're most likely to touch:

| Setting | What it does |
|---|---|
| `database.backend` | `"mongo"` or `"clickhouse"` |
| `database.uri` (mongo) | Connection string |
| `database.clickhouse.host/port/...` | CH connection |
| `storage.durability` | `"ram"` (fast, last ~250 ms lost on crash) or `"wal-batched"` (fsync per batch, no loss) |
| `storage.retention` | How long to keep records. Default `4w` |
| `defaults.radius` | Default `r:` value. Set to 0 to imply `-g` |
| `defaults.time` | Default `t:` value. Default `4h` |
| `limits.max-radius` | Cap on `r:` and `-we` |
| `events.<name>.enabled` | Toggle individual event types |

## Examples

```
# Who broke this block in the last day?
/sg search b:* t:1d r:5

# Roll back every place + break a player did in the last 2 hours, globally.
/sg rollback p:Bob a:place,break t:2h -g

# Who picked up that diamond sword named Excalibur?
/sg search a:pickup iname:Excalibur t:1w -g

# Every command a specific IP ran today.
/sg search ip:1.2.3.4 a:command t:1d -g

# Restore everything inside my WorldEdit selection that was broken last hour.
//wand   (select region with WorldEdit)
/sg restore a:break t:1h -we
```

## Build

```
./gradlew :spyglass:shadowJar       # plugin jar -> spyglass/build/libs/Spyglass-1.0.0.jar
./gradlew build                     # everything: jars, tests, coverage
./gradlew deployToRpServer          # build + copy to ../RP_Server/plugins/
```

## Modules

- `spyglass-api/` - public API. Third-party plugins depend on this only.
- `spyglass-core/` - shared internals (codecs, storage glue).
- `spyglass/` - the Paper plugin.
- `spyglass-velocity/` - optional Velocity proxy companion for cross-server chat and command capture.

## Stack

- Paper 1.21.8, JDK 21
- Gradle 9 with the Shadow plugin
- MongoDB Java driver (POJO codec) or ClickHouse client v2
- Kyori Adventure for chat rendering
- Incendo Cloud 2.x for the command framework

## How it works

Event flow, end to end:

1. Bukkit listeners (`spyglass/.../listener/`, ~40 files) hook every interesting event at `EventPriority.MONITOR, ignoreCancelled=true`. Each listener builds an immutable `EventRecord` and hands it to `Recorder.record()`.
2. `AsyncRecorder` (`spyglass/.../pipeline/AsyncRecorder.java`) puts the record onto an unbounded `LinkedBlockingDeque` and returns immediately. No I/O on the main thread. A `RecordCommittedEvent` is published synchronously to the calling thread so reactive integrations can hook it without coupling to the recorder.
3. A virtual-thread drain (`spyglass-drain`) polls the queue, batches up to 10 000 records, optionally fsyncs the batch to a per-batch WAL file (`<plugindata>/wal/pending/<uuid>.wal`), then pushes the batch to the configured `RecordStore`. Save failures retry with exponential backoff; the WAL file is deleted only after the DB ack.
4. The store is either `MongoRecordStore` (single polymorphic collection, codec-dispatched on the `event` field) or `ClickHouseRecordStore` (wide flat table, ReplacingMergeTree, deep snapshots stored as ZSTD-compressed BSON blobs). Both implement keyset pagination so the rollback engine can stream million-row result sets without OOM.
5. Queries flow the other way: `QueryStringParser` parses `key:value` tokens against `QueryParamHandler`s registered on `SpyglassApiImpl`, builds a `QueryRequest`, and hands it to the store. Mongo uses `PredicateToBson`, ClickHouse uses `PredicateToSql` (values inlined with explicit string-escaping, no `LIKE %x%` user-supplied wildcards).

Rollback adds a second pipeline on top of that:

1. `RollbackService.execute()` parses the same query, builds a `RollbackJob`, persists a `<jobId>.resume` marker (key=value text file at `<plugindata>/resume/`), and submits to `RollbackJobQueue` (in-memory FIFO, one job in flight at a time).
2. `streamPagesAndApply()` runs on a virtual thread. It flushes the recorder, keyset-paginates the store one page at a time, hands each page's effects to `RollbackEngine.applyAllChunked()`, blocks on the page's apply future before fetching the next page, and updates the resume marker with the cursor after each page.
3. `RollbackEngine` groups effects by chunk, then alternates: worker thread does the bulk `LevelChunk` palette writes, hops to the main thread for tile-entity state and the chunk-update packet. The main-thread phase is tick-budgeted (default 15 ms) and yields between chunks.
4. `RollbackPhysicsBlocker` suppresses gravity/cascade ticks inside the rollback bounding box. A plugin chunk ticket is held per chunk for the duration of its write.
5. Each successful apply emits a lightweight `rolled-place` / `rolled-break` record so the wand can attribute rolled blocks. Inverses are collected (up to `limits.rollback-undo-cap`) and pushed to the per-player undo stack on completion.

Crash resume: on `onEnable`, `WalDurability.recover()` replays any pending WAL batches into the recorder before listeners come online, and `RollbackResumeStore.listPending()` surfaces interrupted rollback markers. The operator runs `/sg rbqueue resume <id>` to re-execute the original query from the saved cursor. Rollback force-restores each matched block to its logged state - it does not skip blocks that changed since the event - so re-applying the already-completed prefix is a no-op in outcome (re-writing a block to the value it already holds changes nothing), and the resumed run converges on the same result as an uninterrupted one. Every restored block is itself logged as a `rolled-place` / `rolled-break` record, so the audit trail shows exactly what the rollback touched.

## Operations / production notes

**Log volume.** A single Paper backend at typical RP load (~30 players) writes ~200-600 events/sec sustained. WorldEdit pastes and TNT bursts spike to 100k+ events/sec for short windows. At the default 4-week `storage.retention`, expect 100-300 GB on Mongo or 20-60 GB on ClickHouse (ZSTD compression on the heavy snapshot columns wins ~5x over Mongo).

**Retention.** A TTL index on `expires_at` ages records out automatically (Mongo runs it every 60 s; ClickHouse drops parts at merge time). `expires_at` is stamped at record time from `storage.retention`, so changing the retention only affects records written after the change - old records keep their original TTL. To force-shrink an existing log, lower the retention, then write a one-off update across the collection (Mongo) or run `OPTIMIZE TABLE ... FINAL` after the TTL fires (ClickHouse).

**Durability.** Default is `storage.durability = "ram"` - fast, but a hard JVM kill loses the in-flight queue (typically <250 ms of events). Set to `"wal-batched"` for any server you care about; the per-batch fsync amortizes to one fsync per 850 ms of events at our peak rate, and recovery on next startup is automatic. The WAL directory is at `<plugindata>/wal/pending/`; if files accumulate across restarts, the drain is failing - check DB reachability before bouncing the plugin.

**Backpressure.** The ingest queue is intentionally unbounded - the plugin will not drop events at intake even when the DB is unreachable. Heap pressure is your early-warning signal, surfaced via the `recorder queue depth ...` WARNING. `storage.queue-capacity` is the warn threshold, not a ceiling; size it to 10x your steady-state peak rate (100 000 is fine for MedievalRP-scale).

**Performance tuning knobs.**
- `limits.rollback-tick-budget-ms` (default 15) - per-tick budget for the main-thread phase of a rollback. Raise to 30 for faster wall-clock during a maintenance window; the off-main palette write keeps TPS healthy regardless.
- `limits.rollback-page-size` (default 20 000) - how many records a single store query pulls. Smaller pages bound heap; larger pages reduce round-trips. On ClickHouse, a larger page wins more (CH pays a fixed query-setup cost).
- `limits.rollback-batch-size` (default 4 000) - effects processed per tick within a page.
- `limits.rollback-undo-cap` (default 5 000 000) - inverses retained for `/sg undo`. Past this point the rollback still applies; undo is silently dropped.
- `defaults.radius` - set to 0 to make bare `/sg search` global by default (operator opt-in; the full-table scan is what you asked for).

**Multi-server.** Each backend stamps its `server.name` onto every record (`server` BSON field / CH column). The Velocity proxy module reads the same store and exposes `/sgv` for cross-server search; it never writes records or rolls back. Set `server.name` per backend (`survival`, `lobby`, `creative`, etc.) before pointing more than one backend at the same DB - otherwise records collide on the default name.

**Indexes (Mongo).** `IndexManager.ensureRecordIndexes` creates four compound indexes on every startup: `(source.playerId, occurred desc)`, `(event, occurred desc)`, `(world, x, z, y, occurred desc)`, and a TTL index on `expires_at`. A query that doesn't hit one of these (e.g. unfiltered `iname:` substring search) is a full collection scan - expect minutes on a multi-million-doc store.

**ClickHouse dedup window.** After a WAL replay, the same record id may appear twice in `event_records` until the next part merge (typically seconds to minutes). Plain `count()` queries can over-count; append `FINAL` for strict dedup, or run `OPTIMIZE TABLE spyglass.event_records FINAL DEDUPLICATE` as a one-off. Mongo dedups immediately via `_id`.

**Schema migrations.** ClickHouse: `ClickHouseSchema.ensure()` runs `CREATE TABLE IF NOT EXISTS` plus `ADD COLUMN IF NOT EXISTS` on every boot, and one-shot drops `undo_history` when it detects the pre-chunked layout (dropping the in-flight 24-hour undo window). Mongo: indexes are created idempotently; no destructive migrations.

**WorldEdit / FAWE.** Soft dependency. Without either, `-we` and FAWE bulk-paste capture are unavailable but every other feature works. `WorldEditLifecycleListener` wires up recording mid-session if WE is hot-loaded via `/plugman load`.

**Permissions.** Rollback is destructive; `spyglass.rollback` grants `/sg rollback`, `/sg restore`, `/sg undo`, and `/sg rbqueue` (including `resume` and `cancel`). Default is `op`. Scope it through your permissions plugin before staff grow comfortable using it.

**See also.** `docs/operations.md` for the full runbook (log lines, WAL recovery sequence, ClickHouse dedup, backpressure tuning). The `commands.md` quick-reference is concise; `API.md` covers the integration surface for third-party plugins.
