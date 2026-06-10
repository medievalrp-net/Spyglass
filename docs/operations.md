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

A multi-million-block rollback is read- and GC-bound — the apply runs off-main, so the main thread stays ~90% parked and MSPT stays flat regardless. Whether it is also *freeze-free* is decided by `rollback-page-size`, which since [#17](https://github.com/medievalrp-net/Spyglass/issues/17) governs `/spyglass undo` identically (shared pipeline). Measured 2026-06-10 sweep — 2M blocks, stock Aikar flags, 6 GB heap, local ClickHouse:

| `rollback-page-size` | 2M rollback / undo | player experience (worst tick + GC log) |
|---|---|---|
| 20,000 (default) | ~20 s / ~20 s | worst tick ~32 ms, GC < 80 ms — **invisible** |
| 400,000 | ~11 s / ~10 s | occasional 150–330 ms GC pauses; worst tick can reach ~500 ms |
| 1,000,000 | ~7–9 s / ~10 s | occasional 250–450 ms GC pauses |

The smoothness cliff sits **between the default and 400K**: any large page keeps ~two pages of records resident, which G1 promotes under Aikar's `MaxTenuringThreshold=1` and then evacuates mid-operation ([#19](https://github.com/medievalrp-net/Spyglass/issues/19) removes that transient, for both directions). Keep the default on player-facing servers; raise it only when finishing a huge rollback fast matters more than a few sub-half-second hitches. For reference, CoreProtect on the same runs: 9–16 s wall-clock with 250–660 ms worst ticks and TPS sag for the whole duration.

- **Undo is replay-by-reference** ([#17](https://github.com/medievalrp-net/Spyglass/issues/17)): completing a rollback/restore writes one small ledger row — the resolved query plus a time ceiling — and `/spyglass undo` re-streams the same records through the engine in the opposite direction. There is no per-effect capture, so undo adds ~zero cost to the rollback and any operation size is undoable; an undo costs about the same as the rollback it reverses. `rollback-undo-cap` is parsed but ignored.
- **Don't trust `/mspt` averages for freeze claims.** Off-main work means a GC pause lands *between* measured ticks: the bench showed "flat 20 TPS" while the GC log recorded an 849 ms stop-the-world pause. Judge a config with the `/mspt` **max** column and the JVM GC log (`grep 'Pause Young' logs/gc*.log`) during a trial rollback — `regression/bot/compare.js` prints a WORST SINGLE TICK row for exactly this reason.
- A failed or cancelled undo replay does **not** consume the operation — it stays poppable, and the force-overwrite apply makes the retry converge. Undo references survive as long as the ledger row (24h TTL); the records they point at live for the full event retention, so the reference is the binding constraint.
- **The per-block rollback audit trail is synthesized** ([#22](https://github.com/medievalrp-net/Spyglass/issues/22), `storage.rolled-audit = "synthesized"` by default): a completed operation writes one `rollback-op` record, and searches compute the `rolled-place`/`rolled-break` entries from it on demand — a 2M-block rollback adds **one row instead of two million**, and re-runs add one more. Wand timelines, `a:rolled-place` filters, and time windows behave identically to the old persisted receipts (parity is integration-tested). Receipt rows written by older builds stay searchable as ordinary records. Set `"receipts"` only if an external consumer reads `rolled-*` rows straight from the database.

## Disabling events

`config.conf` has per-event toggles under `events.<name>`. Disabling an event:
- Skips listener registration entirely (no overhead).
- Hides the event from `/spyglass search` results (it's not in the store).
- Does **not** retroactively delete already-recorded rows; lower `storage.retention` if you want them to age out.

## Things NOT to do

- Don't `OPTIMIZE TABLE … FINAL` on a hot path or in a tight loop — it rewrites parts on disk and can cost minutes on a year of data.
- Don't disable WAL during a known-flaky DB period; that's exactly when you want it on.
- Don't bind-mount `LocalSettings.php`-style overrides into the plugin data folder; the plugin's config is HOCON, not properties, and the runtime reload path expects to own the file.
