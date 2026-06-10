# Spyglass vs CoreProtect — rollback throughput & TPS bench

Head-to-head rollback of a **2,000,376-block** cube (126³), with **both** plugins on the **same ClickHouse** (`127.0.0.1:8123`; Spyglass → `spyglass` db, CoreProtect → `coreprotect` db via the `coreprotect-clickhouse` bridge). Run on `../RP_Server` (Paper 1.21.8, Java 21) under the **6 GB benchmark heap** (`start-bench.sh`), driven by [`regression/bot/compare.js`](../../regression/bot/compare.js). Profiling by the **Sundial** plugin (`/sundial`) + the JVM GC log (`logs/gc-bench.log`).

Date of run: 2026-06-03. ClickHouse held ~65 M Spyglass / ~62 M CoreProtect rows from prior runs; all rollback queries are scoped per-bot (`p:`/`u:` + `t:`), so DB size is not a confound for the apply path.

> **Read this first.** The wall-clock and MSPT numbers below make Spyglass look like it holds a flat 20 TPS. **It does not, at the config used.** A 2M rollback triggers G1 **stop-the-world GC pauses of ~0.5–1.0 s** that the MSPT metric cannot see (the rollback's work is off-main, so the pauses land in the inter-tick idle gap rather than extending a measured tick). See [The TPS metric is lying](#the-tps-metric-is-lying) and [Root cause](#root-cause-of-the-gc-freezes). The freezes are config-driven and have a known fix; this document records the *measured* state, warts included.

## TL;DR

| Dimension (2M blocks, ClickHouse) | **Spyglass** | **CoreProtect** | Winner |
|---|---|---|---|
| Rollback wall-clock | **13.7 s** (146 k blk/s) | 26.9 s (74 k blk/s) | SG (1.96×) |
| Restore / re-apply wall-clock | n/a (undo skipped¹) | 14.1 s (142 k blk/s) | — |
| MSPT 5 s-avg during op (avg) | **5.6 ms** | 66.3 ms | SG |
| Worst single tick (MSPT 5 s-max) | 90 ms | 1132 ms | SG — but see caveat |
| Main-thread CPU during rollback (Sundial) | **2.3 %** (90 % parked) | sustained on-main | SG |
| **Worst GC stop-the-world pause** | **849 ms** | 429 ms | **CoreProtect** |
| GC pauses > 500 ms during op | **3** (758/849/518 ms) | 2 (424/429 ms) | CoreProtect |
| Sustained TPS feel | 20 TPS *between* GC freezes | ~15 TPS the **whole** op | SG |

¹ `/spyglass undo` is skipped in the bench — it currently materializes the full inverse-effect list in heap on replay and OOMs above ~250 K effects. This is part of the same heap problem documented below and is covered by the fix.

**Verdict.** Spyglass is ~2× faster than CoreProtect on the same ClickHouse and moves essentially all rollback work off the main thread (main is 90 % parked; tick *bodies* stay ~5 ms). CoreProtect does its apply on the main thread, so it lags *continuously* for the full 27 s (~15 TPS) and additionally throws its own 0.4 s GC freezes. **But Spyglass is not freeze-free:** at the throughput-tuned config it provokes a handful of ~0.5–1.0 s stop-the-world GC pauses per 2M rollback — its *largest* single pause (849 ms) is actually bigger than CoreProtect's (429 ms). Spyglass wins decisively on sustained smoothness and speed; it loses on worst-case single-pause length, and "flat 20 TPS" is an artifact of the metric, not reality. Eliminating the GC freezes is tracked as the rollback-heap fix.

## Verification — the rollback actually changes blocks

Before trusting any timing, confirmed the rollback physically rewrites the world (the completion message also fires on "No results", so a no-op would look instant). Via [`regression/bot/verify-rollback.js`](../../regression/bot/verify-rollback.js) on a 16³ cube, reading server-side ground truth (`execute if block`) at 9 sample points:

| Stage | Server truth |
|---|---|
| after `fill stone` | stone ×9 |
| after `//replace stone air` | air ×9 |
| after `/spyglass rollback` | **stone ×9** |

Engine reported `4096 reversals across 4 chunks`. Durability confirmed via [`regression/bot/persist-test.js`](../../regression/bot/persist-test.js): rolled-back blocks survive both a `save-all flush` and a *passive* chunk unload→reload (`ChunkDirectWriter.finishChunk` → `setUnsaved(true)` works on this Paper build). Note: the engine applies **unconditionally** (force-overwrite) — `applied` = blocks written, and re-running a rollback re-writes rather than skipping.

## Methodology

`compare.js` (size = 2M): `/fill stone` (RCON, unlogged) → `//replace stone air` (WorldEdit, logged by both plugins) → drain → baseline-TPS wait → time `/spyglass rollback p:<bot> t:30m -g` → `/fill air` reset → time `/co rollback` → time `/co restore`. TPS/MSPT sampled from `/mspt` every 1.5 s (5 s-window avg **and** max). **Sundial profiling is OFF during the timing run** — all-thread sampling forces a safepoint every 5 ms and biases SG's MSPT; it is run separately for attribution. Profiling rollbacks are issued from the console (RCON) against the leftover data, so they re-apply the real 2M set without re-ingesting.

**Config used (the live `RP_Server/plugins/Spyglass/config.conf`):**

| key | value used | shipped default |
|---|---|---|
| `rollback-page-size` | **1,000,000** | 20,000 |
| `rollback-batch-size` | **1,000,000** | 4,000 |
| `rollback-undo-cap` | **10,000,000** | 500,000 |

These were raised during the perf push to maximize throughput. They are the direct cause of the GC behavior below.

## Results

```
Size  | Blocks    | SG rollback        | CP rollback        | CP restore
------|-----------|--------------------|--------------------|-------------------
2M    | 2,000,376 | 13.7s  146002 bps  | 26.9s   74444 bps  | 14.1s  141579 bps

mspt-5s-AVG during op (max-of-avgs / avg ms):
2M    | SG 7.8/5.6 ms      | CP rb 178.0/66.3 ms   | CP restore 104.1/45.4 ms

WORST SINGLE TICK (mspt 5s-window max):
2M    | SG 90.1 ms         | CP rb 1132.4 ms       | CP restore 534.4 ms

TPS (min/avg, derived from mspt — see caveat):
2M    | SG 20.0/20.0       | CP rb 5.6/14.8        | CP restore 9.6/16.9
```

**Where Spyglass spends the 13.3 s** (engine's own instrumentation): `query=7227ms (54%)` ≫ `prewarm=1210ms` `collect=612ms` `apply=2020ms (15%)`. The rollback is **read-bound on ClickHouse**, not write-bound. Per-page CH read (1M rows): `submit≈1.0s fetch≈2.2s decode≈0.9s`.

**Sundial main-thread profile** (during SG rollback): `90.3% Unsafe.park` — the main thread is parked the whole time; Spyglass's own main-thread footprint is **2.3%**. Confirms the off-main design works.

### The TPS metric is lying

During the SG rollback the GC log shows stop-the-world pauses of **131 / 758 / 849 / 518 ms** — yet the bench reported a 90 ms worst tick and "flat 20 TPS." MSPT measures **tick-body** time; with the rollback's work off-main the main thread is ~90 % parked, so a GC pause mostly lands in the inter-tick idle budget and never extends a measured tick. `compare.js` derives TPS *from* MSPT, so it inherits the blind spot. The tell: SG's **larger** 849 ms pause produced a **smaller** worst tick (90 ms) than CoreProtect's 429 ms pause (1132 ms tick) — only possible because CoreProtect works on-main (pause hits the tick body) and SG does not. Real player-visible effect of an 849 ms pause is an 849 ms freeze regardless of how MSPT accounts it.

### Root cause of the GC freezes

The big pauses are **"Pause Young (Mixed)"** — G1 evacuating *old-gen* regions, i.e. long-lived data was promoted. G1 region accounting across one 2M rollback (region size 8 MB, 6 GB heap):

| | start | peak | meaning |
|---|---|---|---|
| Old regions | 223 (~1.8 GB) | **470 (~3.8 GB)** | **+~2 GB promoted to old gen mid-rollback** |
| Humongous regions | 25 | 38 | the 1M-element backing arrays (~8 MB each) |
| GC pause | — | **0.5–1.0 s** | back-to-back Mixed GCs evacuating the swollen old gen |

Two config dials drive it:
1. **`rollback-undo-cap = 10M`** — `RollbackService` keeps one inverse `RollbackEffect` per applied block, for the **whole** rollback (2M live objects), pushing to `undo_history` only at the end. These survive Young GCs and promote to old gen — most of the +2 GB. (config's own comment: *"the inverse list itself becomes a heap problem."*)
2. **`rollback-page-size` / `rollback-batch-size = 1M`** — a 1M-element `Object[]`/`RollbackResult[]` is ~8 MB, over G1's 4 MB **humongous** threshold, so each lands directly in old gen (the 25–38 humongous regions). Prefetch keeps two 1M-row pages live at once.

On a 6 GB heap this drives old gen 1.8 → 3.8 GB, and G1 can't honor `MaxGCPauseMillis=200` evacuating a 470-region old gen. It is **retention during the op, not a leak** — old regions fall back afterward. Small rollbacks never hit it (4 096 blocks: 256 ms, zero GC drama).

The fix (tracked separately) keeps undo fully functional for arbitrarily large rollbacks by **streaming inverse effects to `undo_history` per page** (the table is already chunked, keyed on `operation_id, chunk_index`) and **paging the undo replay**, plus bounding apply sub-batches under the 4 MB humongous threshold — so `undo-cap` can stay high (undo is the safety net for an accidental huge rollback) at near-zero heap cost.

## Reproduction

```
# Server up on the bench heap (≥6 GB; the 3 GB prod heap drops the bot mid-ingest):
../RP_Server/start-bench.sh

# Timing head-to-head (Sundial OFF):
node regression/bot/compare.js                 # 2M cube; SG vs CP rollback + CP restore

# Verify blocks change + durability:
node regression/bot/verify-rollback.js
node regression/bot/persist-test.js            # add 'nosave' arg for passive-unload test

# Profile a rollback (Sundial main + all) against existing data, no re-ingest:
bash regression/bot/profile-rollback.sh <botTag>     # reports under RP_Server/plugins/Sundial/reports/

# GC pauses for a window:  grep 'Pause Young' RP_Server/logs/gc-bench.log
```

RCON: `127.0.0.1:25576` (pw `test123`); one-shot helper `regression/bot/rcon-send.js "<cmd>"`.
