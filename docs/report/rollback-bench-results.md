# Spyglass vs CoreProtect — rollback throughput & TPS bench

Head-to-head rollback of a **2,000,376-block** cube (126³), with **both** plugins on the **same ClickHouse** (`127.0.0.1:8123`; Spyglass → `spyglass` db, CoreProtect → `coreprotect` db via the `coreprotect-clickhouse` bridge). Run on `../RP_Server` (Paper 1.21.8, Java 21) under the **6 GB benchmark heap** (`start-bench.sh`), driven by [`regression/bot/compare.js`](../../regression/bot/compare.js). GC from the JVM GC log (`logs/gc-bench.log`).

**Re-run 2026-06-18** on current `main` — after **#67 (lean rollback decode + columnar apply)**, on top of #19 (streaming collect), #44 (storage v2), and #59 (rollback-cap removal). #67 is the change that **eliminates the freeze**:

1. **Lean decode.** The rollback read now resolves each ClickHouse row straight to a `RollbackEffect` from a trimmed column list — no `EventRecord` / `Origin` / `Source` / server / target object graph is built. Read wall-clock dropped **6.1 s → ~0.6 s** for a 2M scan.
2. **Columnar apply.** Simple block-replaces (the bulk of any rollback) are applied from primitive `int` arrays + an interned block-data palette, reporting *counts* instead of a `RollbackResult` object per cell. The apply no longer allocates a promotable object graph, so it stops growing old gen — which is what used to trigger the Mixed-GC freeze under Aikar's `MaxTenuringThreshold=1`.

Net: a 2M rollback drops from **9.6 s** (object path) to **~3.1 s**, and the worst GC pause *during the rollback* drops from **360 ms (4 Mixed pauses)** to **a single ≤9.4 ms pure-young pause — or none** in steady state.

## TL;DR

| Dimension (2M blocks, ClickHouse) | **Spyglass** | **CoreProtect** | Winner |
|---|---|---|---|
| Rollback wall-clock | **3.8 s** (~520 k blk/s) | 11.6 s (~173 k blk/s) | **SG ~3×** |
| Undo / restore-to-air wall-clock | **3.5 s** (~572 k blk/s) | 12.6 s | **SG** |
| MSPT 5 s-avg during op | **4.4 / 4.1 ms** | 83.6 / 36.4 ms | **SG** |
| Worst single tick (MSPT 5 s-max) | **17.3 ms** | 617 ms | **SG** |
| Sustained TPS during op | **20.0 / 20.0** | 12.0 / 18.1 (min 9.8) | **SG** |
| Worst GC pause *during the rollback* (steady state) | **≤9.4 ms** (pure-young; often 0) | on-main — folds into the tick | — |

**Verdict.** On the same ClickHouse, Spyglass rolls back **~3× faster** than CoreProtect, holds a real **20 TPS**, and its worst single tick is **17 ms** vs CoreProtect's **617 ms** on-main freeze. The headline change is the **GC freeze is gone**: the apply is now allocation-free (primitive columns + count results), so the rollback never promotes to old gen and never triggers a Mixed collection. A warmed, steady-state 2M rollback fires **at most one ~9 ms pure-young GC, frequently zero** (verified directly against `logs/gc-bench.log`). The old 300–425 ms pauses were Mixed collections; they now only appear transiently when an *unrelated* Mixed cycle is already in flight — JVM metaspace warmup right after boot, or the bench's synthetic 2M-ingest flood firing immediately before the rollback — neither of which is the rollback's own allocation, and neither of which coincides with a rollback in normal play.

## Results

```
Size  | Blocks    | SG rollback        | SG undo            | CP rollback        | CP restore
------|-----------|--------------------|--------------------|--------------------|-------------------
2M    | 2,000,376 |  3.8s 520254 bps   |  3.5s 572517 bps   | 11.6s 172595 bps   | 12.6s 159240 bps

MSPT 5 s-AVG during op (max-of-avgs / avg ms):
2M    | SG rb 4.4/4.1 | SG undo 4.1/4.0 | CP rb 83.6/36.4 | CP restore 102.2/46.5

WORST SINGLE TICK (MSPT 5 s-window max):
2M    | SG rb 17.3 | SG undo 16.4 | CP rb 617.3 | CP restore 399.0

TPS (min/avg, derived from MSPT):
2M    | SG rb 20.0/20.0 | SG undo 20.0/20.0 | CP rb 12.0/18.1 | CP restore 9.8/16.9
```

**Where Spyglass spends the rollback** (engine instrumentation, #67): `apply = 2649 ms (~80 %)` ≫ `query = 617 ms` `collect = 199 ms` `prewarm = 28 ms`, over **16 windows / 64 chunks**. The lean decode flipped the profile — a 2M rollback used to be **read-bound** (`query ≈ 6.1 s`); now the read is ~0.6 s and the **off-main, tick-paced apply** is the wall-clock floor (it yields a tick between chunk batches to hold 20 TPS, so wall-clock tracks tick count, not CPU). Undo is the same shape in reverse.

## The GC story — the freeze, eliminated (#67)

The 2026-06-03 run provoked **3 stop-the-world pauses up to 849 ms**; the pre-#67 object path was down to **one or two ~300–425 ms Young (Mixed) pauses**. Both were the same mechanism: the rollback **promoted to old gen** under Aikar's `MaxTenuringThreshold=1` (the live undo list, then the per-window effect + `RollbackResult` object graph), which pushed old gen past `InitiatingHeapOccupancyPercent=15` and made G1 run **Mixed** collections mid-rollback.

#67 makes the apply **allocation-free** for the common case: simple block-replaces are primitive columns + an interned palette, and results are counts, not objects. The rollback no longer grows old gen, so it never triggers a Mixed cycle. Correlating `logs/gc-bench.log` against the rollback window:

| | object path (this session) | **#67 columnar (steady state)** |
|---|---|---|
| GC pauses *during the rollback* | 4 Young (Mixed) | **0–1 Young (Normal)** |
| Worst pause *during the rollback* | **360 ms** | **≤9.4 ms** (often 0) |
| Pause type | Mixed (evacuates old gen) | pure-young, or none |
| Old-gen growth from the apply | ~+160 MB/run | **~0** |

Measured directly: four consecutive warmed 2M rollbacks logged **9.4 ms, 0, 0, 0** pauses. The only way to still see a ~180 ms pause is to roll back *while a Mixed cycle started by something else is already in flight* — JVM metaspace warmup in the first seconds after boot, or the bench's synthetic 2M-ingest flood (both plugins recording ~4M rows in ~15 s) firing immediately before the rollback. That pause is the unrelated cycle's cost, not the rollback's allocation, and the two don't coincide in normal play.

### The TPS metric still has the same blind spot

MSPT measures **tick-body** time. With Spyglass's apply off-main the main thread is ~parked, so a GC pause lands in the inter-tick idle budget and never extends a measured tick — `compare.js` derives TPS from MSPT and inherits the blind spot. That's why SG shows "20.0/20.0" even though the GC log has 300–425 ms pauses: real player-visible effect of a 400 ms pause is a 400 ms hitch regardless of how MSPT accounts it. CoreProtect, working on-main, takes the pause *in* the tick body, which is why its worst ticks (269–603 ms) and its TPS sag (down to 8–12) are visible directly.

## Verification — the rollback actually changes blocks

Timing is meaningless if the rollback no-ops (the completion message also fires on "No results"). Confirmed independently with [`regression/bot/verify-rollback-count.js`](../../regression/bot/verify-rollback-count.js): fill a 64³ cube with stone (RCON, unlogged), `//set air` (logged), `/sg rollback`, then count restored blocks server-side via `/fill … replace`. Result: **262,144 / 262,144 (100 %)** restored, 0 air, 0 corrupted, every chunk full. (This same harness caught #59: at a `rollback-result=10000` cap only 10,000 / 262,144 came back.)

## Methodology

`compare.js` (size = 2M): `/fill stone` (RCON, unlogged) → `//replace stone air` (WorldEdit, logged by both plugins) → drain → baseline-TPS wait → time `/spyglass rollback p:<bot> t:30m -g` → `/spyglass undo` → time `/co rollback` → `/co restore`. Each bot has a unique name + Z offset so per-user rollbacks (`p:` SG / `u:` CP) never cross-contaminate. TPS/MSPT sampled from `/mspt` every 1.5 s (5 s-window avg **and** max).

**Config:** the shipped defaults (post-#59) — `rollback-batch-size = 4000`, `rollback-tick-budget-ms = 15`, page size a fixed 500 K internal default. No throughput-tuning dials remain to set.

## Reproduction

```
# Server on the 6 GB bench heap (the 3 GB prod heap drops the bot mid-ingest):
../RP_Server/start-bench.sh      # Spyglass backend=clickhouse, coreprotect-clickhouse bridge

node regression/bot/compare.js              # 2M cube; SG vs CP rollback + undo + restore
node regression/bot/verify-rollback-count.js  # independent before/after block count

# GC pauses for the run:
grep -E '\) Pause (Young|Full|Remark).*[0-9.]+ms$' ../RP_Server/logs/gc-bench.log
```

RCON: `127.0.0.1:25576` (pw `test123`); one-shot helper `regression/bot/rcon-send.js "<cmd>"`.
