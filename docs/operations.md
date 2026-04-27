# Operator runbook

Quick reference for running Spyglass in production. Pairs with
the per-class Javadoc; this file covers the **what to look for** and
**what to do** that doesn't belong in source.

## Log lines that matter

Search the server log for `Spyglass`. Lines that warrant a look:

| Severity | Substring | Meaning |
|----------|-----------------------------------|---------|
| WARNING | `recorder queue depth … (warn threshold N)` | Drain is falling behind. Records still queued (no drops). Check DB reachability and disk I/O. Warnings double in cadence as the depth doubles. |
| WARNING | `recorder save failed (Nx, retry in …ms)` | DB write failed; AsyncRecorder is retrying with exponential backoff. Single occurrences are usually network blips. Sustained 10x+ means the DB is down. |
| WARNING | `WAL write failed (…); proceeding with DB save anyway` | Disk-side WAL write threw. Rare. Records still durable iff the DB save succeeds; otherwise lost on crash. Investigate filesystem health. |
| INFO | `WAL recovery: replayed N records from K pending file(s)` | Normal after a hard restart. Confirms crash-recovery path landed records back in the store. |
| WARNING | `Skipping corrupt WAL file …` | A WAL file failed to decode. Either disk corruption or an interrupted partial write. The file is deleted so recovery doesn't stall; records inside are lost. |
| SEVERE | `Recorder shutdown flush gave up within deadline; N records left on the WAL …` | DB was unreachable through the full `storage.flush-timeout` window. With WAL enabled, records replay on next startup. |
| SEVERE | `Recorder shutdown flush gave up within deadline; N records could not be persisted and are lost` | Same scenario as above but with WAL disabled — **records lost**. Set `storage.durability = "wal-batched"` to make this recoverable. |

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
  OPTIMIZE TABLE sg.event_records FINAL DEDUPLICATE;
  ```
  Acceptable as a one-off; expensive on big tables.

Mongo dedup is immediate — `_id` is the record UUID, and `insertMany(unordered)` swallows duplicate-key errors.

## Backpressure tuning

`storage.warn-threshold` in `config.conf` sets the queue depth at which the warning starts firing. The queue is **unbounded** — this is a signal, not a ceiling.

Recommendation: 10x your steady-state peak event rate × seconds of acceptable backlog. MedievalRP's ~600 ev/s peak with 60 s slack → 36 000; we ship 100 000 (~160 s slack) for headroom.

If you're seeing first-crossing warnings during normal play, raise the threshold. If you're seeing them after a Mongo restart and they double quickly, the drain is genuinely losing — check Mongo CPU, replica election, network.

## Disabling events

`config.conf` has per-event toggles under `events.<name>`. Disabling an event:
- Skips listener registration entirely (no overhead).
- Hides the event from `/sg search` results (it's not in the store).
- Does **not** retroactively delete already-recorded rows; lower `storage.retention` if you want them to age out.

## Things NOT to do

- Don't `OPTIMIZE TABLE … FINAL` on a hot path or in a tight loop — it rewrites parts on disk and can cost minutes on a year of data.
- Don't disable WAL during a known-flaky DB period; that's exactly when you want it on.
- Don't bind-mount `LocalSettings.php`-style overrides into the plugin data folder; the plugin's config is HOCON, not properties, and the runtime reload path expects to own the file.
