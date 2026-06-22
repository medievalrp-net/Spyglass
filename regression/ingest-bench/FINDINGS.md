# Ingest-MSPT benchmark - findings (LOCAL, gitignored)

This file and the whole `regression/ingest-bench/` tree are kept local via
`.git/info/exclude` (not committed, not pushed). The GitHub issues below are the
shareable handoff; this is the full local record.

## TL;DR

- **Ingest is parity** between Spyglass and CoreProtect. Both add a small,
  sub-millisecond per-tick cost; the differences are within run-to-run noise.
  Rollback remains Spyglass's real differentiator.
- The per-event main-thread cost is **not GC and not lock contention** (both
  ruled out with evidence). It is steady per-event CPU, dominated by
  `BlockData.getAsString()` on the main thread (~19%).
- GitHub: **#153** (bench + parity result), **#154** (the getAsString
  optimization, refines #152). Related pre-existing: #152, #129.

## Results - added MSPT per tick over vanilla (ms, median of 3x180s)

| rate ev/s | CoreProtect mean / p95 / p99 | Spyglass mean / p95 / p99 |
|---|---|---|
| 600 | 0.14 / 0.51 / 0.69 | 0.16 / 0.40 / 0.84 |
| 1,200 | 0.23 / 0.66 / 0.62 | 0.21 / 0.65 / 0.85 |
| 2,400 | 0.46 / 1.13 / 1.77 | 0.52 / 0.90 / 2.25 |
| 5,000 (stress) | 0.55 / 1.52 / 2.59 | 0.67 / 1.45 / 3.53 |

- <= 300 ev/s: under measurement noise for both (deltas go slightly negative).
- p99 per-run at 5000 OVERLAPS (SG 5.23/7.74/6.79 vs CP 5.85/5.35/6.56) -> tie.
- Both hold flat 20 TPS even at 5000 ev/s (mean tick ~1.5 ms vs 50 ms budget).
- Fairness: both logged exactly 690 x rate events per cell (e.g. 5000 ->
  SG 3,450,002 / CP 3,450,459; offset = platform setup fill).
- Paper 1.21.8, 4 GB + Aikar, both on default SQLite, official CoreProtect 23.1.

## Method (why it is trustworthy)

- added = MSPT(generator+plugin) - MSPT(generator alone); generator in all configs.
- MSPT = per-tick `Server.getTickTimes()`, captured by diffing the ring each
  tick (0 anomalies all sweep). Not /tps, not /mspt averages, not Sundial.
- Cross-checked vs `/spark health`: my p95 == spark 95%ile and my worst tick ==
  spark max to the decimal (e.g. 153.06 vs 153.1).
- Realistic break+place via real air<->stone toggling so both log 1:1.

## Root cause (the "why")

- NOT GC: `gc-correlate.py` vs `-Xlog:gc` - only 1-3% of slow ticks coincide
  with a pause; big pauses (755/344/208 ms) were all boot/warmup; exactly 1
  in-window pause (115 ms) caused the single worst tick; ~1 young GC / 180 s.
- NOT lock contention: JFR `profile` - AsyncRecorder enqueue 0.7%, lock/park
  1.0% of active main-thread CPU. The 23,843 ThreadPark events were Paper's
  inter-tick pacing sleep (`MinecraftServer.waitForTasks`->`parkNanos`).
- IS steady per-event CPU: `CraftBlockData`/`getAsString()` ~19%, record/context
  ~17%, snapshot ~8%.

Code: `BlockSnapshots.captureRaw()` runs `state.getBlockData().getAsString()` on
the main thread (~line 127). Listeners call the full inline `capture()`:
`BlockBreakListener.java:36`, `BlockPlaceListener.java:36-37` (twice per place).

Extra (commented on #154): the place path also missed #116's break-path opts -
`BlockPlaceListener.java:36` does a full `capture()` even when the replaced
state is air (break uses cached `BlockSnapshots.air()`), and `:38` does
`fromLocation(getLocation())` (throwaway Location) where break uses `fromBlock()`.

## GitHub issues (the handoff)

- **#153** test(regression): the bench + parity result, full method + table.
- **#154** perf(listener): defer block-path `getAsString()` off the main thread;
  refines #152 (whose finishCapture-only deferral leaves getAsString on main for
  the no-item common path). Has a comment about the place-path #116 gap.
- **#152** (pre-existing): convert block/env listeners to the deferred split.
- **#129** (pre-existing): EntityDeath/explosion NBT serialized inline on main.

NOT implemented on main or dev (both b1974cf, still inline `capture()`); no PR.

## Reproduce

```sh
regression/ingest-bench/plugin/build.sh
./gradlew :spyglass:shadowJar
MUTATE=1 regression/ingest-bench/run.sh phase0     # validate methodology
MUTATE=1 regression/ingest-bench/run.sh sweep      # full matrix (~3.7 h, resumable)
PAPER_VERSION=1.21.8 WARMUP=90 MEASURE=180 python3 regression/ingest-bench/analyze.py "$WORK/out/csv"
```

Raw artifacts: `/Volumes/External-NVME/tmp-ingestbench/` (out/csv = sweep;
research-gc / research-jfr = the deep dives; gc.log + profile.jfr in the srv dirs).
jfr tool: `/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/jfr`.

## Parked (not done, by instruction)

- Do NOT implement #154 (per user, 2026-06-21). It is documented for a later pass.
- README Performance rewrite: a draft exists (lead with the ingest-parity table,
  demote the rollback table, reframe backends to SQLite-default, keep the
  Crusalis spark link). Kept local; not started, not filed as a GitHub issue
  (README messaging is the user's call).
