# Spyglass

Forensic logging and rollback for Paper 1.21.x. Spyglass records every block, container, chat, command, combat, and movement event on the server, exposes them through a composable `key:value` query language, and reverses any subset of them — per block, per player, per cause, or in bulk — while the server holds 20 TPS.

## Performance

Measured side-by-side with CoreProtect (ClickHouse edition) on the same server, same dataset, same run — stock Aikar G1 flags, 6 GB heap, shipped default configuration. Full methodology, GC logs, and run-to-run variance in [docs/report/](docs/report/).

| 2,000,376-block operation | Spyglass | CoreProtect (same run) |
|---|---|---|
| Rollback wall-clock | **8.0 s** (~250 K blocks/s) | 11.4 s |
| Undo / restore wall-clock | **7.9 s** | 11.9 s |
| TPS during the operation | **20.0 flat** | dips to 11–12 |
| Worst single tick | **88 ms** | 286–358 ms |
| Store growth per rollback | **+1 row** | re-logs rolled blocks |
| Disk per stored event | **5.0 bytes** | 6.4 bytes |
| Disk per 2M-event cycle (ingest + rollback + undo) | **~9.5 MiB** | ~15–16 MiB |

Speed does not degrade with history depth: keyset pagination keeps rollback reads flat at 150 M+ stored rows, and per-player search routes through a bloom-filter skip index. Behavioral parity is verified by a [106-case comparison suite](regression/use-cases.md) executed against both plugins on a live server — current score 47 automated passes, 0 failures ([results](docs/report/use-case-results-2026-06-11.md)).

## Status

`v1.0.0` — feature-complete. Ships 40+ event types, block + container + entity rollback, vanilla WorldEdit and FAWE capture, the `/spyglass tool` inspection wand, an unlimited-depth undo stack, a job queue with crash-resume, and a public API for third-party integration.

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

Defaults are production defaults. No JVM flag changes are required or assumed — all performance figures above were measured on stock Aikar flags.

## Permissions

All default to `op`. Grant via your permissions plugin to scope access.

| Permission | What it unlocks |
|---|---|
| `spyglass.use` | `/spyglass help`, `/spyglass events`, `/spyglass page` |
| `spyglass.search` | `/spyglass search` |
| `spyglass.search.ip` | See join IPs in results and use the `ip:` key, on Paper and the proxy alike. Without it IPs render as `(ip hidden)` and `ip:` errors. |
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
| `/sg undo` | `u` | Undo your most recent rollback / restore |
| `/sg rbqueue [...]` | `queue`, `rbq` | List, cancel, or resume rollback jobs |
| `/sg tool` | `t`, `inspect` | Toggle the inspection wand |
| `/sg tele <world> <x> <y> <z>` | - | Teleport (used by clickable search results) |

### Query syntax

Search, rollback, and restore all take the same `key:value` query language. Combine as many keys as you want; results match all of them. On `p:`, `a:`, `b:`, and `c:`, prefix a value with `!` to exclude it instead.

```
/sg search p:Steve b:diamond_ore t:1d r:50
/sg rollback p:griefer a:break,!place t:6h r:100
```

#### Query keys

| Key | Aliases | Example | Notes |
|---|---|---|---|
| `p:` | `player:` | `p:Steve,Alex` · `p:!Steve` | Comma-separated for OR; `!name` excludes |
| `a:` | `action:`, `event:` | `a:break,place` · `a:!place` | Event type. See `/sg events`; `!name` excludes |
| `b:` | `block:` | `b:diamond_ore` · `b:!chest` | Target block material; `!material` excludes |
| `i:` | `item:` | `i:netherite_sword` | Item material involved (drop, pickup, container, etc.) |
| `iname:` | `itemname:` | `iname:Excalibur` | Item display name (substring). Works on both backends |
| `ilore:` | `itemlore:`, `d:` | `ilore:cursed` | Item lore line (substring). Works on both backends |
| `ench:` | `enchant:`, `enchantment:` | `ench:sharpness=5` | Item enchantment. Works on both backends |
| `cu:` | `custom:` | `cu:my-custom-item` | Plugin custom-item id (via the API) |
| `e:` | `entity:` | `e:creeper` | Entity type involved |
| `c:` | `cause:` | `c:tnt,!creeper` | Change cause; `!cause` excludes |
| `m:` | `message:` | `m:hello` | Chat / sign / book text (substring) |
| `rcp:` | `recipient:` | `rcp:Steve` | Private-message recipient |
| `r:` | `radius:` | `r:50` | Search radius around you (blocks). Default applies if omitted; use `-g` for global |
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

### Explosion attribution

Player-lit TNT records the igniter as the actor: `p:<griefer>` searches and rollbacks cover the crater directly. Chained, dispensed, or redstone-primed TNT and mob explosions are entity-attributed — sweep those with `c:tnt`, `c:creeper`, etc.

### Inspection wand

```
/sg tool
```

Toggles an inspection mode. Left-click a block to see its full history — including entries for blocks a previous rollback restored. Right-click to see what's about to happen near it. Toggle off the same way.

### Rollback workflow

```
/sg rollback p:griefer t:6h r:100
```

Reverts everything that player did in the last 6 hours within 100 blocks. Behavior:

1. The request is queued. Only one rollback runs at a time; others wait.
2. Records stream from the store directly into bounded apply windows — heap stays flat regardless of operation size, and the server holds ~20 TPS throughout.
3. Each block is verified before it is written: if a *different* player has since changed that block, the cell is skipped and reported (`block changed`) instead of overwriting their work.
4. When it finishes, you get a summary — blocks reverted, skips with reasons, chunks touched, time taken — and the operation lands on your personal undo stack.

There is no size limit on rollback or undo. A 10 M-block operation streams the same way a 100-block one does.

To take it back:

```
/sg undo
```

Undo replays the operation's own record set in the opposite direction — nothing is captured per block, so undo costs the same as the rollback it reverses and works at any size. Repeated `/sg undo` unwinds your operations newest to oldest. To re-apply an undone operation, run `/sg restore` with the original query. Undo references are valid for 24 hours.

### Managing the queue

```
/sg rbqueue              # list in-flight, pending, recent, and resumable jobs
/sg rbqueue stop         # cancel the running job (current batch finishes, then it stops)
/sg rbqueue cancel <id>  # cancel a pending or in-flight job by short id
/sg rbqueue resume <id>  # re-run a job that was interrupted by a crash or restart
```

If the JVM crashes mid-rollback, on next startup `/sg rbqueue` shows the interrupted job as resumable. The original query re-runs from a saved cursor; already-applied blocks skip as already-correct, so the resumed run converges on the same result as an uninterrupted one.

## Configuration

`plugins/Spyglass/config.conf` has the full annotated reference. The knobs you're most likely to touch:

| Setting | What it does |
|---|---|
| `database.backend` | `"mongo"` or `"clickhouse"` |
| `database.uri` (mongo) | Connection string |
| `database.clickhouse.host/port/...` | CH connection |
| `storage.durability` | `"ram"` (fast, last ~250 ms lost on crash) or `"wal-batched"` (fsync per batch, no loss) |
| `storage.retention` | How long to keep records. Default `4w` |
| `storage.rolled-audit` | `"synthesized"` (default — one row per rollback operation) or `"receipts"` (legacy: one row per restored block) |
| `defaults.radius` | Default `r:` value. Set to 0 to imply `-g` |
| `defaults.time` | Default `t:` value. Default `4h` |
| `limits.max-radius` | Cap on `r:` and `-we` |
| `events.<name>.enabled` | Toggle individual event types |

The rollback engine requires no tuning. `limits.rollback-page-size` (default 500 000) is a query-efficiency dial only — since the engine streams records into bounded windows, it affects neither memory nor lag in either direction, and there is little reason to change it.

## Examples

```
# Who broke this block in the last day?
/sg search b:* t:1d r:5

# Roll back every place + break a player did in the last 2 hours, globally.
/sg rollback p:Bob a:place,break t:2h -g

# TNT crater: player-lit TNT is attributed to the igniter.
/sg rollback p:griefer t:1h r:30

# Roll back everything except containers (don't disturb chest contents).
/sg rollback p:griefer t:6h r:100 b:!chest

# Who picked up that diamond sword named Excalibur?
/sg search a:pickup iname:Excalibur t:1w -g

# Track a specific enchanted item through drops and pickups.
/sg search ench:sharpness=5 t:1d -g

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
- `spyglass-velocity/` - optional Velocity proxy companion for cross-server search. Read-only by design: it never writes records and never rolls back.

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
4. The store is either `MongoRecordStore` (single polymorphic collection, codec-dispatched on the `event` field) or `ClickHouseRecordStore` (wide flat table, ReplacingMergeTree, delta-coded sequence ids and coordinates, a bloom-filter player index, deep snapshots stored as ZSTD-compressed BSON blobs). Both implement keyset pagination so the rollback engine can stream million-row result sets without OOM.
5. Queries flow the other way: `QueryStringParser` parses `key:value` tokens against `QueryParamHandler`s registered on `SpyglassApiImpl`, builds a `QueryRequest`, and hands it to the store. Mongo uses `PredicateToBson`; ClickHouse uses `PredicateToSql` (values inlined with explicit string-escaping, no user-supplied wildcards). Predicates ClickHouse cannot push down — item name / lore / enchantment paths inside the snapshot blobs — are split off and evaluated in memory against the decoded rows, bounded to a 20 000-row scan past the pushable predicates.

Rollback adds a second pipeline on top of that:

1. `RollbackService.execute()` parses the same query, builds a `RollbackJob`, persists a `<jobId>.resume` marker (key=value text file at `<plugindata>/resume/`), and submits to `RollbackJobQueue` (in-memory FIFO, one job in flight at a time).
2. `streamPagesAndApply()` runs the streaming pipeline: a dedicated reader thread pulls records off the store wire and folds them directly into bounded apply windows (~131 K effects; repeated block snapshots are interned so a window's retained footprint stays small under generational GC). A single-slot queue hands windows to the consumer, which pre-warms chunks, applies, and checkpoints the resume cursor per window. Read and apply overlap fully; at most a few windows are ever resident, so heap does not scale with operation size.
3. `RollbackEngine` groups each window's effects by chunk and fans the bulk palette writes across a worker pool (the locked `LevelChunkSection.setBlockState` makes concurrent writes to distinct chunks safe). Before every write, the worker compares the block's current state against the state the record expects — an interned-state identity check costing one read per block. Mismatches (another player's later edit, an already-applied resume prefix) skip with a `block changed` reason instead of overwriting. Tile-entity state, the chunk packet, and lighting are applied on the main thread within a per-tick budget.
4. `RollbackPhysicsBlocker` suppresses gravity/cascade ticks inside the rollback bounding box. A plugin chunk ticket is held per chunk for the duration of its write.
5. On completion the operation writes two small artifacts: an undo ledger row (the resolved query plus a time ceiling — `/sg undo` replays that record set in the opposite direction, so nothing is captured per block) and one `rollback-op` event record. Searches synthesize the per-block `rolled-place` / `rolled-break` entries from the op record on demand, with exact filter parity to the persisted rows they replace — a 2 M-block rollback adds one row to the store instead of two million, and re-running it adds one more.

Crash resume: on `onEnable`, `WalDurability.recover()` replays any pending WAL batches into the recorder before listeners come online, and `RollbackResumeStore.listPending()` surfaces interrupted rollback markers. The operator runs `/sg rbqueue resume <id>` to re-execute the original query from the saved cursor. The already-completed prefix skips as already-correct (the expected-state check sees each block already holds its target state), so the resumed run converges on the same result as an uninterrupted one.

## Operations / production notes

**Log volume.** A single Paper backend at typical RP load (~30 players) writes ~200-600 events/sec sustained. WorldEdit pastes and TNT bursts spike to 100k+ events/sec for short windows. At the default 4-week `storage.retention`, expect 100-300 GB on Mongo or 5-15 GB on ClickHouse — the v2 schema stores delta-coded sequence ids (~0.1 B/row; the id column was 74% of v1 disk) and measured **5.0 bytes per event total against CoreProtect-ClickHouse's 6.4 on identical workloads**, with the full forensic payload intact. The synthesized rollback audit keeps rollbacks themselves from growing the store.

**Retention.** A TTL index on `expires_at` ages records out automatically (Mongo runs it every 60 s; ClickHouse drops parts at merge time). `expires_at` is stamped at record time from `storage.retention`, so changing the retention only affects records written after the change - old records keep their original TTL. To force-shrink an existing log, lower the retention, then write a one-off update across the collection (Mongo) or run `OPTIMIZE TABLE ... FINAL` after the TTL fires (ClickHouse).

**Durability.** Default is `storage.durability = "ram"` - fast, but a hard JVM kill loses the in-flight queue (typically <250 ms of events). Set to `"wal-batched"` for any server you care about; the per-batch fsync amortizes to one fsync per 850 ms of events at our peak rate, and recovery on next startup is automatic. The WAL directory is at `<plugindata>/wal/pending/`; if files accumulate across restarts, the drain is failing - check DB reachability before bouncing the plugin.

**Backpressure.** The ingest queue is intentionally unbounded - the plugin will not drop events at intake even when the DB is unreachable. Heap pressure is your early-warning signal, surfaced via the `recorder queue depth ...` WARNING. `storage.queue-capacity` is the warn threshold, not a ceiling; size it to 10x your steady-state peak rate (100 000 is fine for MedievalRP-scale).

**Rollback performance.** No tuning is required: the shipped defaults are the fastest measured configuration and the smoothest. `limits.rollback-page-size` (default 500 000) only sets how many records each keyset read requests — raising it past the default buys nothing measurable, lowering it adds round trips. `limits.rollback-batch-size` (default 4 000) budgets the main-thread share of each window (tile entities, leftovers); lower it if rollbacks heavy in containers/signs dip your TPS. Measured baselines and GC methodology live in [docs/operations.md](docs/operations.md) and [docs/report/](docs/report/).

**Undo.** Undo is replay-by-reference: completing a rollback/restore writes one ledger row, and `/sg undo` re-streams the same records through the engine in the opposite direction. Any operation size is undoable; an undo costs about what the original operation cost. References expire after 24 h (the records they point at live for the full retention). A failed or cancelled undo does not consume the reference — it stays poppable and the retry converges.

**Multi-server.** Each backend stamps its `server.name` onto every record (`server` BSON field / CH column). The Velocity proxy module reads the same store and exposes `/sgv` for cross-server search; it never writes records or rolls back. Set `server.name` per backend (`survival`, `lobby`, `creative`, etc.) before pointing more than one backend at the same DB - otherwise records collide on the default name.

**Indexes (Mongo).** `IndexManager.ensureRecordIndexes` creates four compound indexes on every startup: `(source.playerId, occurred desc)`, `(event, occurred desc)`, `(world, x, z, y, occurred desc)`, and a TTL index on `expires_at`. A query that doesn't hit one of these (e.g. unfiltered `iname:` substring search) is a full collection scan - expect minutes on a multi-million-doc store. On ClickHouse the same item-field queries post-filter in memory with a bounded scan; pair them with `p:`/`t:`/`r:` to keep the scanned window small.

**ClickHouse dedup window.** After a WAL replay, the same record id may appear twice in `event_records` until the next part merge (typically seconds to minutes). Plain `count()` queries can over-count; append `FINAL` for strict dedup, or run `OPTIMIZE TABLE spyglass.event_records FINAL DEDUPLICATE` as a one-off. Mongo dedups immediately via `_id`.

**Schema migrations.** ClickHouse: `ClickHouseSchema.ensure()` runs `CREATE TABLE IF NOT EXISTS` plus `ADD COLUMN IF NOT EXISTS` on every boot, and one-shot drops `undo_history` when it detects the pre-chunked layout (dropping the in-flight 24-hour undo window). Mongo: indexes are created idempotently; no destructive migrations. Stores written by older builds stay readable: persisted `rolled-*` receipt rows from pre-synthesis versions remain searchable alongside synthesized entries.

**WorldEdit / FAWE.** Soft dependency. Without either, `-we` and FAWE bulk-paste capture are unavailable but every other feature works. `WorldEditLifecycleListener` wires up recording mid-session if WE is hot-loaded via `/plugman load`.

**Permissions.** Rollback is destructive; `spyglass.rollback` grants `/sg rollback`, `/sg restore`, `/sg undo`, and `/sg rbqueue` (including `resume` and `cancel`). Default is `op`. Scope it through your permissions plugin before staff grow comfortable using it.

**See also.** `docs/operations.md` for the full runbook (log lines, WAL recovery sequence, ClickHouse dedup, backpressure tuning, measured rollback baselines). `commands.md` is the quick-reference; `API.md` covers the integration surface for third-party plugins; `regression/use-cases.md` is the live comparison suite.
