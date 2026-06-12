# Operator runbook

Quick reference for running Spyglass in production. Pairs with
the per-class Javadoc; this file covers the **what to look for** and
**what to do** that doesn't belong in source.

## Log lines that matter

Search the server log for `Spyglass`. Lines that warrant a look:

| Severity | Substring                         | Meaning |
|----------|-----------------------------------|---------|
| WARNING  | `recorder queue depth … (warn threshold N)` | Drain is falling behind. Records still queued (no drops). Check DB reachability and disk I/O. Warnings double in cadence as the depth doubles. |
| WARNING  | `recorder save failed (Nx, retry in …ms)` | DB write failed; AsyncRecorder is retrying with exponential backoff. Single occurrences are usually network blips. Sustained 10x+ means the DB is down. |
| WARNING  | `WAL write failed (…); proceeding with DB save anyway` | Disk-side WAL write threw. Rare. Records still durable iff the DB save succeeds; otherwise lost on crash. Investigate filesystem health. |
| INFO     | `WAL recovery: replayed N records from K pending file(s)` | Normal after a hard restart. Confirms crash-recovery path landed records back in the store. |
| WARNING  | `Skipping corrupt WAL file …`     | A WAL file failed to decode. Either disk corruption or an interrupted partial write. The file is deleted so recovery doesn't stall; records inside are lost. |
| SEVERE   | `Recorder shutdown flush gave up within deadline; N records left on the WAL …` | DB was unreachable through the full `storage.flush-timeout` window. With WAL enabled, records replay on next startup. |
| SEVERE   | `Recorder shutdown flush gave up within deadline; N records could not be persisted and are lost` | Same scenario as above but with WAL disabled — **records lost**. Set `storage.durability = "wal-batched"` to make this recoverable. |

## Storage durability modes

Set in `config.conf` under `storage.durability`:

- `ram` — RAM-only queue. Fastest. Hard JVM crash loses anything not yet flushed.
- `wal-batched` (recommended) — every drain batch is fsynced to `<plugindata>/wal/pending/<uuid>.wal` before the DB push and deleted on success. Crash recovery on next startup replays leftover files. Adds one fsync per batch (~250 ms cadence at default settings).

## Crash recovery — what to expect

1. Server starts, plugin enables.
2. `WalDurability.recover()` scans `<plugindata>/wal/pending/`, reads each `.wal` in mtime order, decodes the BSON batch, and feeds the records back into the recorder.
3. INFO line `WAL recovery: replayed N records from K pending file(s).` confirms it ran.
4. The recorder writes them to the DB on the next drain. With ClickHouse, replays may briefly produce duplicate rows — see below.
5. WAL files are deleted as soon as they're decoded (whether or not they round-trip cleanly), so a second boot is silent.

If you see WAL files piling up across restarts, the recorder is failing to drain them too — check DB connectivity.

## ClickHouse dedup window

The `event_records` table is a `ReplacingMergeTree` sorted by `(event, occurred, id)`. Two inserts of the same record (same UUID `id`) collapse to one row on the **next part merge** — not synchronously.

Implications:
- Normal forensic queries see the duplicate for seconds-to-minutes after a WAL replay.
- Counts (`SELECT count() FROM event_records WHERE …`) can over-count during this window.
- For a strict-dedup query, append `FINAL`: `SELECT count() FROM event_records FINAL WHERE …`. Slow on large ranges; use sparingly.
- To force dedup synchronously after a known replay event:
  ```sql
  OPTIMIZE TABLE spyglass.event_records FINAL DEDUPLICATE;
  ```
  Acceptable as a one-off; expensive on big tables.

Mongo dedup is immediate — `_id` is the record UUID, and `insertMany(unordered)` swallows duplicate-key errors.

## Backpressure tuning

`storage.warn-threshold` in `config.conf` sets the queue depth at which the warning starts firing. The queue is **unbounded** — this is a signal, not a ceiling.

Recommendation: 10x your steady-state peak event rate × seconds of acceptable backlog. MedievalRP's ~600 ev/s peak with 60 s slack → 36 000; we ship 100 000 (~160 s slack) for headroom.

If you're seeing first-crossing warnings during normal play, raise the threshold. If you're seeing them after a Mongo restart and they double quickly, the drain is genuinely losing — check Mongo CPU, replica election, network.

## Large rollbacks: heap, GC, and what the TPS metric hides

A multi-million-block rollback is read- and GC-bound — the apply runs off-main, so the main thread stays ~90% parked and MSPT stays flat regardless. Since [#19](https://github.com/medievalrp-net/Spyglass/issues/19) the engine never materializes a page of records: rows stream off the wire into small bounded effect windows (repeated block snapshots interned, at most a few windows in flight), so the old speed-vs-smoothness trade-off is gone and `rollback-page-size` is only a query-efficiency knob. [#17](https://github.com/medievalrp-net/Spyglass/issues/17) makes `/spyglass undo` ride the identical pipeline. Measured 2026-06-10 — 2M blocks, stock Aikar flags, 6 GB heap, local ClickHouse, same-run CoreProtect for reference:

| config | 2M rollback / undo | in-op GC log (worst pause, old-gen delta) |
|---|---|---|
| **default (500K page, 4K batch)** | **7.7 s / 7.8 s** | 110–122 ms pauses; old +~220 MB transient, reclaimed within seconds |
| 1,000,000 page | 8.3 s / 7.4 s | 200–265 ms pauses; old transient ~1 GB — bigger read bursts, no speed gain |
| pre-#19 default (20K page) | ~20 s / ~20 s | <80 ms — smooth but 2.6× slower |
| CoreProtect, same runs | 9.4–11.6 s | 280–434 ms worst ticks, TPS 12–18 for the duration |

**Storage v2 (#44, measured 2026-06-11):** ids are delta-coded sequences (id column 15.2 → 0.1 B/row; it was 74% of v1 disk) and per-player search uses a bloom-filter skip index instead of the by_player projection (which cost more than the base table). Fresh-store result on identical 2M-event bench cycles: Spyglass 5.0 B/row, ~9.5 MiB/cycle vs CoreProtect-ClickHouse 6.4 B/row, ~15-16 MiB/cycle. A v1-layout table is refused at startup with a migration recipe (rename, restart, INSERT SELECT backfill). Deep-history (150M-row) per-player search latency on the bloom path has not yet been soak-tested — re-bench before quoting search numbers at that depth.

Keep the shipped default: it is the fastest measured configuration *and* the smooth one — raising the page size past 500K only enlarges read bursts (bigger transient promotion under Aikar's `MaxTenuringThreshold=1`) without finishing sooner. Lowering it just adds keyset round trips. The apply-window size is internal and not configurable; per-window scheduling is tick-aligned, which is why the engine uses few large windows rather than many small ones.

- **Undo is replay-by-reference** ([#17](https://github.com/medievalrp-net/Spyglass/issues/17)): completing a rollback/restore writes one small ledger row — the resolved query plus a time ceiling — and `/spyglass undo` re-streams the same records through the engine in the opposite direction. There is no per-effect capture, so undo adds ~zero cost to the rollback and any operation size is undoable; an undo costs about the same as the rollback it reverses. `rollback-undo-cap` is parsed but ignored.
- **Don't trust `/mspt` averages for freeze claims.** Off-main work means a GC pause lands *between* measured ticks: the bench showed "flat 20 TPS" while the GC log recorded an 849 ms stop-the-world pause. Judge a config with the `/mspt` **max** column and the JVM GC log (`grep 'Pause Young' logs/gc*.log`) during a trial rollback — `regression/bot/compare.js` prints a WORST SINGLE TICK row for exactly this reason.
- A failed or cancelled undo replay does **not** consume the operation — it stays poppable, and the force-overwrite apply makes the retry converge. A clean undo consumes its reference and pushes nothing, so repeated `/spyglass undo` unwinds the operator's operations newest-to-oldest; to redo an undone operation, run `/spyglass restore` with the original query. Undo references survive as long as the ledger row (24h TTL); the records they point at live for the full event retention, so the reference is the binding constraint.
- **The per-block rollback audit trail is synthesized** ([#22](https://github.com/medievalrp-net/Spyglass/issues/22), `storage.rolled-audit = "synthesized"` by default): a completed operation writes one `rollback-op` record, and searches compute the `rolled-place`/`rolled-break` entries from it on demand — a 2M-block rollback adds **one row instead of two million**, and re-runs add one more. Wand timelines, `a:rolled-place` filters, and time windows behave identically to the old persisted receipts (parity is integration-tested). Receipt rows written by older builds stay searchable as ordinary records. Set `"receipts"` only if an external consumer reads `rolled-*` rows straight from the database.

## Disabling events

`config.conf` has per-event toggles under `events.<name>`. Disabling an event:
- Skips listener registration entirely (no overhead).
- Hides the event from `/spyglass search` results (it's not in the store).
- Does **not** retroactively delete already-recorded rows; lower `storage.retention` if you want them to age out.

## Security

The store holds the network's most sensitive data: full chat history, command
lines, and player IPs. The config template's authless-localhost defaults are
fine only while the database and the server share one host. The moment the
store crosses hosts — which is the whole premise of the Velocity companion —
treat it like any internet-adjacent database:

- **MongoDB**: enable auth (`security.authorization: enabled`) and connect with
  a least-privilege user scoped to the Spyglass database, credentials in the
  `database.uri` (`mongodb://spyglass:<pw>@host/...`). Never expose the port
  beyond the hosts that need it — bind to the private interface and firewall
  the rest. Use TLS (`?tls=true`) or a private network/VPN between hosts.
- **ClickHouse**: create a dedicated user with a password instead of riding
  `default`, set `database.clickhouse.ssl = true` when the connection leaves
  the host, and firewall ports 8123/9000 the same way. Don't enable the
  library bridge — Spyglass doesn't use it.
- **Patch floors** (both advisories are server-side; the bundled client
  libraries are unaffected):
  - MongoDB "MongoBleed" **CVE-2025-14847** — unauthenticated memory read,
    actively exploited in the wild. Run at least 8.2.3 / 8.0.17 / 7.0.28 /
    6.0.27 / 5.0.32 / 4.4.30.
  - ClickHouse **CVE-2025-1385** — RCE via the library-bridge API when that
    feature is enabled. Run at least 25.1.5.5 / 24.12.5.65 / 24.11.5.34 /
    24.8.14.27 / 24.3.18.6.
- **On-disk spill**: `wal/pending/` and `resume/` under the plugin data folder
  carry database-grade content (chat, command lines, IPs, resolved query
  plans). Keep them inside the same backup and file-permission hygiene as the
  database itself — don't ship them to a world-readable backup target.
- **In-game tiers**: `spyglass.search` does not reveal IPs; that needs
  `spyglass.search.ip` (see commands.md). Auth-style command arguments are
  redacted at the recorder by `events.command.redact` — extend that list if
  your plugins add new credential-bearing commands.

## Things NOT to do

- Don't `OPTIMIZE TABLE … FINAL` on a hot path or in a tight loop — it rewrites parts on disk and can cost minutes on a year of data.
- Don't disable WAL during a known-flaky DB period; that's exactly when you want it on.
- Don't bind-mount `LocalSettings.php`-style overrides into the plugin data folder; the plugin's config is HOCON, not properties, and the runtime reload path expects to own the file.
