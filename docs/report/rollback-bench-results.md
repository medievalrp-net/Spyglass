# Spyglass vs CoreProtect — rollback throughput & TPS bench

Head-to-head rollback of a **2,000,376-block** cube (126³), with **both** plugins on the **same ClickHouse** (`127.0.0.1:8123`; Spyglass → `spyglass` db, CoreProtect → `coreprotect` db via the `coreprotect-clickhouse` bridge). Run on `../RP_Server` (Paper 1.21.8, Java 21) under the **6 GB benchmark heap** (`start-bench.sh`), driven by [`regression/bot/compare.js`](../../regression/bot/compare.js). GC from the JVM GC log (`logs/gc-bench.log`).

**Re-run 2026-06-18** on current `main` — after #19 (streaming collect), #44 (storage v2), and #59 (rollback-cap removal). **Two back-to-back runs**; numbers are reported `run1 / run2`. This supersedes the 2026-06-03 run. Two things changed materially since then:

1. **`/spyglass undo` now completes.** It used to materialize the whole inverse-effect list in heap and OOM'd above ~250 K effects, so the old run skipped it. It now streams, and reverses a 2M cube in ~8.6 s.
2. **The rollback-time GC freezes are roughly halved.** The dials that drove them — `rollback-page-size` / `rollback-undo-cap` at 1M / 10M — are gone (#59); the engine now streams **16 bounded ~500 K-row windows** instead of one 1M-row window plus a 2M-element live undo list, so the +2 GB old-gen promotion that caused the freezes no longer happens.

## TL;DR

| Dimension (2M blocks, ClickHouse) | **Spyglass** | **CoreProtect** | Winner |
|---|---|---|---|
| Rollback wall-clock | **8.4 / 9.3 s** (~226 k blk/s) | 12.7 / 12.2 s (~161 k blk/s) | **SG ~1.4×** |
| Undo / restore-to-air wall-clock | **8.8 / 8.4 s** (now works) | 12.8 / 15.3 s | **SG** |
| MSPT 5 s-avg during op | **5.3 / 5.5 ms** | 44.1 / 36.7–55.9 ms | **SG** |
| Worst single tick (MSPT 5 s-max) | **129 / 75 ms** | 276 / 603 ms | **SG** |
| Sustained TPS during op | **20.0 / 20.0** | 17.1 / 15.7 (min 8–12) | **SG** |
| Worst GC pause *during the rollback* | **307 / 424 ms** (was 849 ms) | on-main — folds into the tick | — |

**Verdict.** On the same ClickHouse, Spyglass rolls back **~1.4× faster** than CoreProtect and holds a real **20 TPS** by doing the apply off-main (main thread ~parked the whole time); CoreProtect applies on-main and sags to ~16–18 TPS (dipping to 8–12) for the *entire* operation. `/spyglass undo` now reverses a 2M rollback in ~8.6 s — the heap blowup that forced it to be skipped is fixed. The rollback-time GC freezes the 2026-06-03 run flagged (3 pauses up to **849 ms**) are down to **one or two ~300–425 ms** Young (Mixed) pauses: the streaming windows removed the +2 GB old-gen promotion and the humongous 1M-row arrays that drove them. They are **reduced, not eliminated** — a 2M rollback still costs a couple of sub-half-second pauses. The *largest* pauses in the run (550–669 ms) are now during the **synthetic 2M ingest flood** (both plugins recording ~4M rows in ~15 s), not the rollback.

## Results

```
Size  | Blocks    | SG rollback        | SG undo            | CP rollback        | CP restore
------|-----------|--------------------|--------------------|--------------------|-------------------
run1  | 2,000,376 |  8.4s 237829 bps   |  8.8s 226159 bps   | 12.7s 157622 bps   | 12.8s 156732 bps
run2  | 2,000,376 |  9.3s 215140 bps   |  8.4s 239079 bps   | 12.2s 164383 bps   | 15.3s 130966 bps

MSPT 5 s-AVG during op (max-of-avgs / avg ms):
run1  | SG rb 6.6/5.3 | SG undo 5.5/4.7 | CP rb 100.6/44.1 | CP restore 102.5/46.7
run2  | SG rb 6.1/5.5 | SG undo 6.6/5.5 | CP rb  85.1/36.7 | CP restore 122.2/55.9

WORST SINGLE TICK (MSPT 5 s-window max):
run1  | SG rb 128.8 | SG undo  40.2 | CP rb 276.0 | CP restore 370.8
run2  | SG rb  74.6 | SG undo 157.4 | CP rb 269.0 | CP restore 603.4

TPS (min/avg, derived from MSPT — see caveat):
run1  | SG rb 20.0/20.0 | SG undo 20.0/20.0 | CP rb 9.9/17.1 | CP restore 9.8/16.9
run2  | SG rb 20.0/20.0 | SG undo 20.0/20.0 | CP rb 11.8/18.0 | CP restore 8.2/15.7
```

**Where Spyglass spends the rollback** (engine instrumentation, run1 / run2): `query = 5117 / 5492 ms (~60 %)` ≫ `collect = 441 / 377 ms` `prewarm = 34 / 54 ms` `apply = 2296 / 2808 ms (~28 %)`, over **16 windows / 64 chunks**. Still **read-bound on ClickHouse**, not write-bound — the off-main apply is ~2.5 s of the ~9 s. Undo is the same shape in reverse (`query ≈ 4.8–5.3 s`, `apply ≈ 2.3 s`).

## The GC story (the 2026-06-03 caveat, re-measured)

The old run's headline warning was that the throughput-tuned config (`rollback-page-size`/`rollback-batch-size`/`rollback-undo-cap` = 1M/1M/10M) provoked **3 stop-the-world pauses of 518 / 758 / 849 ms** during a single 2M rollback, by promoting ~2 GB to old gen (the 2M-element live undo list) and allocating humongous 1M-element arrays straight into old gen.

Those dials are gone. Correlating `logs/gc-bench.log` pause timestamps against the rollback window (the `Spyglass rollback … 8313ms` / `9208ms` log lines):

| | 2026-06-03 (tuned) | **2026-06-18 (current)** |
|---|---|---|
| Pauses > 500 ms *during the rollback* | 3 | **0** |
| Worst pause *during the rollback* | **849 ms** | **307 / 424 ms** |
| Apply windows | 1 (one 1M-row page) | **16 (bounded ~500 K)** |
| Largest pause anywhere in the run | 849 ms (rollback) | 550 / 669 ms (**ingest flood**, not rollback) |

So the rollback no longer drives the old gen up by 2 GB; it streams. What remains: a 2M rollback still pays one or two ~300–425 ms Young (Mixed) pauses (the apply churns short-lived per-window effect objects), and the heaviest pauses in a bench *run* are now the ingest of 2M edits recorded by both plugins at once — an artificial condition you'd never hit in normal play.

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
