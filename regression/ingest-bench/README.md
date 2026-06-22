# Ingest-MSPT benchmark

Measures the per-tick cost a block-logging plugin adds to the server **main
thread** under sustained event load, for Spyglass vs CoreProtect vs a vanilla
baseline. Ingest (logging every player action, continuously) is the metric that
matters day to day for a logging plugin; rollback is rare.

## Method

- **Identical reproducible load.** The `IngestBench` plugin (in `plugin/`) fires
  real `BlockBreakEvent` / `BlockPlaceEvent`s through the plugin manager at a set
  events/second, as one online actor, on a forceloaded flat stone platform. The
  world is never mutated, so the per-tick load is constant. The MONITOR listeners
  of whatever logging plugin is installed record those events exactly as they
  would a real player's.
- **The generator runs in every config**, so its own cost cancels in the delta:

  ```
  added_MSPT(plugin, rate) = MSPT(plugin, rate) - MSPT(vanilla, rate)
  ```

- **Accurate MSPT.** IngestBench records the server's own per-tick work duration
  from `Server.getTickTimes()` (nanoseconds) once per tick into a CSV, then
  reports the raw distribution (mean / p95 / p99 / worst tick). It does **not**
  use `/tps` or `/mspt` rolling averages for the headline, and does not use
  Sundial (its safepoints bias the tick). The newest completed tick is found by
  diffing the `getTickTimes()` ring against the previous tick's snapshot (robust
  to the buffer's index convention), cross-checked against `/spark health`.
- **Warmup + runs.** Discard a warmup window (JIT + cache + queue warm), then
  measure; several runs per cell, median reported with run-to-run spread.
- **Isolation / fairness.** Throwaway servers on alt ports only (never touches
  `../RP_Server`). Flat world, forceloaded region, mob spawning / random tick /
  weather / daylight off, autosave off during the window, same `-Xmx` + Aikar
  flags + Paper build across every config. Fresh server (fresh DB) per
  (config, rate). mineflayer 4.37 speaks 1.21.8 natively, so no ViaVersion is in
  the loop.

## Components

| File | Role |
|------|------|
| `plugin/` | the `IngestBench` Paper plugin (one class) + `build.sh` (javac against the cached paper-api 1.21.8, no Gradle) |
| `run.sh` | the harness: `phase0` (validate on one server) and `sweep` (full matrix) modes |
| `actor.mjs` | idle mineflayer actor so the plugin has a real Player to fire events as |
| `analyze.py` | CSVs -> per-(config,rate) percentiles -> added-MSPT table + JSON |

## Running

```sh
# 1. build the plugin and (from the repo root) the Spyglass shaded jar
regression/ingest-bench/plugin/build.sh
./gradlew :spyglass:shadowJar

# 2. validate the methodology on one server (both plugins, short windows,
#    proves events reach both DBs + tick capture matches /spark)
regression/ingest-bench/run.sh phase0

# 3. full sweep (defaults: vanilla/coreprotect/spyglass x {0,300,600,1200,2400,5000}
#    ev/s, 3 runs, 90s warmup / 180s measure). Tunable via env.
regression/ingest-bench/run.sh sweep

# 4. analyze
PAPER_VERSION=1.21.8 WARMUP=90 MEASURE=180 \
  python3 regression/ingest-bench/analyze.py "$WORK/out/csv"
```

Key env vars: `WORK` (scratch dir, default `/Volumes/External-NVME/tmp-ingestbench`),
`CONFIGS`, `RATES`, `RUNS`, `WARMUP`, `MEASURE`, `XMX`, `GAME_PORT`/`RCON_PORT`,
`SPYGLASS_JAR`, `COREPROTECT_JAR`, `INGESTBENCH_JAR`, `BOT_BASE`, `SPARK_CHECK`.

CoreProtect is benchmarked with the **official build, default SQLite backend**
(the same zero-ops store Spyglass defaults to) so the comparison is apples to
apples. Record the exact CoreProtect version with the results.

CSVs and the analyzer output land under `$WORK/out/csv` (outside the repo).
