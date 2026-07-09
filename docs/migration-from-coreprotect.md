# Migrating from CoreProtect to Spyglass

End-to-end runbook for an operator who has a CoreProtect-on-Paper
server and wants to switch to Spyglass, **keeping all historical
forensic data**.

The migration is a one-shot, scriptable process: import history, audit
parity, then swap the plugin jar. Both plugins can co-exist during the
import so there's no logging blackout window.

## The in-plugin path (start here)

Since 1.0.8 the importer is built into the plugin - for most servers
this replaces everything below except the parity `validate` step:

1. Install Spyglass alongside CoreProtect and pick your backend in
   `config.conf`. Check `storage.retention`: records older than it are
   aged out after import, so set it `"never"` (or long enough to cover
   your history) BEFORE importing. The import warns you up front if
   part of the source predates the cutoff.
2. Copy CoreProtect's `database.db` into `plugins/Spyglass/import/`
   and run `/sg import database.db` - or, for a MySQL-backed
   CoreProtect, define the source in `plugins/Spyglass/import.conf`
   and run `/sg import mysql <source>`.
3. Watch the progress in chat or console. Re-running the same source
   is refused unless you pass `--confirm`; with it, re-imports dedup
   rather than duplicate (MongoDB is the exception - re-import there
   is blocked).
4. Done - search and rollback work on the imported history, including
   `p:<name>` for players who never joined this server. Remove
   CoreProtect whenever you're comfortable.

Sizing the window: on the same hardware a ~29M-event history imported
into ClickHouse in minutes and into SQLite in hours - the import runs
async and does not lag the main thread either way, but plan the SQLite
case as an overnight job. Every world the source references must exist
on the server (`<world>/uid.dat`); a missing one fails the import
cleanly before anything is written.

Switching storage backends later (say SQLite to ClickHouse once you
outgrow the file): fill in the target's block in `config.conf`, run
`/sg migrate <backend>`, flip `database.backend`, restart.

The CLI below remains for offline imports (no server running) and for
the row-parity `validate` audit, which has no in-plugin equivalent yet.

## Before you start

You'll need:

- A CoreProtect installation (any version 20+) backed by SQLite
  (`plugins/CoreProtect/database.db`) or a MySQL DB the plugin uses.
- A ClickHouse instance reachable from the box that'll run the
  importer. Production-shaped is fine — the same instance the live
  Spyglass plugin will write to. Spyglass auto-creates its schema on
  first connect.
- Access to the world folders so the importer can resolve world UUIDs
  via each world's `uid.dat`.
- ~30 minutes for a million-row history; bigger histories scale
  roughly linearly.

The migration does **not** require taking the server offline. You can
run the importer while CoreProtect is still logging — the importer
reads its DB read-only.

## Step 1 — Take a backup

For SQLite:

```sh
# Stop the server briefly to ensure a consistent snapshot, OR
# take a hot copy (CoreProtect commits in batches, so a hot copy
# is consistent within a tick).
cp plugins/CoreProtect/database.db plugins/CoreProtect/database.db.pre-migration
```

For MySQL:

```sh
mysqldump --single-transaction --quick coreprotect > coreprotect.pre-migration.sql
```

If anything goes sideways, the rollback path is "stop the server, drop
the Spyglass jar, put CoreProtect back, restore this backup."

## Step 2 — Resolve world UUIDs

Spyglass keys on Bukkit world UUIDs (the ones in each world folder's
`uid.dat`); CoreProtect stored only world *names*. Make sure the
importer can see every world your CoreProtect data references.

The fastest way: copy the world folders the importer needs onto the
machine running the import:

```sh
mkdir worlds-for-import
cp -r /path/to/server/world          worlds-for-import/
cp -r /path/to/server/world_nether   worlds-for-import/
cp -r /path/to/server/world_the_end  worlds-for-import/
# ... any other worlds
```

Each subfolder must contain a `uid.dat`. The importer scans the
directory at startup and **fails fast with a list of missing worlds**
if it can't resolve them all — so you can iterate without writing
anything to ClickHouse.

## Step 3 — Build and test the importer

```sh
git clone https://github.com/medievalrp-net/Spyglass.git
cd Spyglass
./gradlew :spyglass-importer:shadowJar
```

This produces `spyglass-importer/build/libs/spyglass-importer-*.jar`.

Run a dry-run first to validate the source schema and world resolution:

```sh
java -jar spyglass-importer/build/libs/spyglass-importer-*.jar import \
    --source ./database.db \
    --worlds-dir ./worlds-for-import \
    --server-name my-server \
    --clickhouse-host localhost \
    --dry-run
```

Expected output: a per-table summary and a per-event count breakdown.
If you see `MISSING_PLAYER_UUID` skips above your tolerance threshold,
that points at very old pre-2014 CoreProtect data. Decide whether to
proceed (skip those rows) or invest in backfilling UUIDs from
Mojang's API.

Common dry-run errors and fixes:

- `Cannot resolve world UUIDs from ...` — `--worlds-dir` is missing
  one or more `uid.dat` files. Symlink in the missing world folders
  and retry.
- `CoreProtect database is missing co_blockdata_map` — your source DB
  is older than CoreProtect 18.0. Upgrade CoreProtect on the source
  side first, let it migrate the schema, then re-export.
- `Failed to open SQLite source ... database is locked` — the
  CoreProtect plugin currently has a write transaction open. Wait for
  the next tick or stop the server.

## Step 4 — Run the real import

When the dry-run is clean:

```sh
java -jar spyglass-importer/build/libs/spyglass-importer-*.jar import \
    --source ./database.db \
    --worlds-dir ./worlds-for-import \
    --server-name my-server \
    --clickhouse-host clickhouse.example.com \
    --clickhouse-port 8123 \
    --clickhouse-database spyglass \
    --retention 365d
```

A few notes on the flags:

- `--server-name` is stamped onto every imported record's `server`
  field. Use the same value Spyglass's live plugin will set in
  `config.conf` so `srv:my-server` queries return both imported and
  live rows.
- `--retention` sets the TTL on imported rows **relative to import
  time**, not event time. Pick something long enough to span the
  forensic window you care about (default 30d is fine for short
  lookback; 365d is reasonable for long histories).
- The importer is idempotent — every row gets a deterministic UUID
  derived from `(coreprotect_table, rowid)`, so re-running the
  importer collapses duplicates via ClickHouse's
  `ReplacingMergeTree`. Safe to retry on any failure.

A 1.3 M-row CoreProtect database imports in about 25 seconds.

## Step 5 — Validate row parity

```sh
java -jar spyglass-importer/build/libs/spyglass-importer-*.jar validate \
    --coreprotect-sqlite ./database.db \
    --clickhouse-host clickhouse.example.com \
    --server-name my-server
```

Expected output:

```
[OK  ] blocks_break_place_use            CP=864,639  Spy=864,639  delta=+0
[OK  ] blocks_kills                      CP=305      Spy=305      delta=+0
[OK  ] sessions_join_quit                CP=1,780    Spy=1,780    delta=+0
[OK  ] chat_messages                     CP=6,298    Spy=6,298    delta=+0
[OK  ] commands                          CP=10,824   Spy=10,824   delta=+0
[OK  ] containers_deposit_withdraw       CP=14,084   Spy=14,084   delta=+0
[OK  ] items_drop_pickup                 CP=395,105  Spy=395,105  delta=+0
[WARN] items_other_skipped               CP=N        Spy=0        delta=+N
       These actions … have no Spyglass record type. The delta IS the
       count of skipped rows — not a problem.

Validation PASSED. All counts within tolerance.
```

The last [WARN] is expected and documented — see
[`importer.md#whats-not-imported-known-gaps`](importer.md#whats-not-imported-known-gaps).

If any check shows `[FAIL]`, **stop and investigate before continuing**.
The most common cause is mid-import write traffic from the still-running
CoreProtect plugin; re-running `import` (which is idempotent) usually
resolves it.

## Step 6 — Optional: confirm you'll get a query speedup

```sh
java -jar spyglass-importer/build/libs/spyglass-importer-*.jar bench \
    --coreprotect-sqlite ./database.db \
    --clickhouse-host clickhouse.example.com \
    --trials 20
```

For datasets above ~100 k rows in `co_block`, expect Spyglass query
latencies 70 ×–900 × lower than CoreProtect for typical operator
queries. See [`coreprotect-vs-spyglass-bench.md`](report/coreprotect-vs-spyglass-bench.md)
for the methodology and a worked example.

If your dataset is small (under ~50 k events) you may not see a
meaningful speedup; CoreProtect handles small datasets fine.

## Step 7 — Switch the plugin

Pick a maintenance window short enough that a few minutes of logging
gap is acceptable, or do this during a planned restart.

```sh
# 1. Stop the server.
# 2. Pull CoreProtect.jar out of plugins/.
mv plugins/CoreProtect.jar plugins-disabled/

# 3. Drop Spyglass-1.0.0.jar in.
cp Spyglass-1.0.0.jar plugins/

# 4. Edit plugins/Spyglass/config.conf to point at the same ClickHouse
#    instance and use the same server-name as the import. The plugin
#    auto-creates its config the first time, so the easiest path is:
#      a) start the server once with default config,
#      b) shut down,
#      c) edit storage.host / storage.database / server.name,
#      d) start again.
# 5. Restart.
```

Spyglass picks up writing new rows into the same `event_records` table
the importer wrote historical rows into, all stamped with the same
`server-name`. Operators querying `/spyglass search` see imported and
live data in one stream.

If you want to keep CoreProtect installed as a belt-and-suspenders
during the cutover week, that's fine — the two plugins don't conflict.
Spyglass writes only to ClickHouse; CoreProtect writes only to its
SQLite/MySQL. You'll just have double the disk usage temporarily.

## Step 8 — Smoke-test

```
/spyglass search a:break time:7d -g
/spyglass search p:<your-name> time:1d
/spyglass tool          # toggle the inspection wand, click a known block
```

For each, confirm you see imported (`origin=plugin`/`coreprotect-import`)
rows mixed with new rows (`origin=player`). The wand should attribute
correctly even on blocks that were broken months before the migration.

## Rollback plan

Spyglass and CoreProtect don't share state, so rollback is trivial:

```sh
# 1. Stop the server.
mv plugins/Spyglass.jar plugins-disabled/
mv plugins-disabled/CoreProtect.jar plugins/

# 2. Restore the SQLite backup if needed.
cp plugins/CoreProtect/database.db.pre-migration plugins/CoreProtect/database.db

# 3. Restart.
```

You'll lose any forensic data Spyglass logged after the cutover —
that data is in ClickHouse but won't be visible to CoreProtect — but
nothing CoreProtect logged before the cutover is affected.

If you want to keep the post-cutover Spyglass data and merge it back
into CoreProtect later, that's a custom operation we don't ship
tooling for. The reverse importer (Spyglass → CoreProtect) is not on
the roadmap.

## What you don't get from imported data

Documented in [`importer.md`](importer.md), summary here:

- **No IP attribution on imported `join` rows.** CoreProtect doesn't
  store IPs anywhere. Live Spyglass `join` rows do; imported ones
  don't. `i:<ip>` queries against historical data will return empty.
- **No sign-edit history.** CoreProtect's `co_sign` table is not
  imported because Spyglass has no sealed `SignEditRecord` type yet.
  Signs that were *broken* (placed-then-broken) ARE captured via
  `co_block`.
- **No item-lifecycle events beyond drop/pickup.** Durability breaks,
  lava destroys, crafting creates, ender-chest swaps, etc. are
  skipped. Drop and pickup do round-trip.

For most servers, none of these limit the day-to-day forensic UX.

## Need help?

Run any of the importer commands with `--help`:

```sh
java -jar spyglass-importer-*.jar --help
java -jar spyglass-importer-*.jar import --help
java -jar spyglass-importer-*.jar validate --help
java -jar spyglass-importer-*.jar bench --help
```

For the full Docker rig (ClickHouse + importer + validate + bench in
one stack), see [`docker/README.md`](../docker/README.md).
