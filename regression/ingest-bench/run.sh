#!/usr/bin/env bash
# Ingest-MSPT benchmark harness: Spyglass vs CoreProtect vs a vanilla baseline.
#
# For each (config, rate) it stands up a throwaway Paper 1.21.8 server on ALT
# PORTS (never touches ../RP_Server), installs IngestBench (+ the logging plugin
# under test), connects one idle mineflayer actor so the plugin has a real
# Player to fire events "as", then drives warmup + measured windows over RCON.
# IngestBench fires real Block break/place events at a set ev/s and writes the
# server's own per-tick durations (Server.getTickTimes()) to a CSV.
#
# The generator runs in ALL configs, so its cost cancels in the delta:
#   added_MSPT(plugin) = MSPT(generator + plugin) - MSPT(generator alone).
#
# Modes:
#   run.sh phase0   one server with BOTH CoreProtect + Spyglass; proves events
#                   reach both DBs, the tick capture matches /spark, baseline flat
#   run.sh sweep    the full {vanilla,coreprotect,spyglass} x rates x RUNS sweep
#
# Key env (all overridable; no hardcoded machine paths leak into git output):
#   WORK, GAME_PORT, RCON_PORT, RCON_PASS, XMX, PAPER_VERSION,
#   CONFIGS, RATES, RUNS, WARMUP, MEASURE, SPYGLASS_JAR, COREPROTECT_JAR,
#   INGESTBENCH_JAR, BOT_BASE, SPARK_CHECK
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
MODE="${1:-sweep}"

WORK="${WORK:-/Volumes/External-NVME/tmp-ingestbench}"
CACHE="$WORK/paper-cache"
OUT="${OUT:-$WORK/out}"
CSV="$OUT/csv"
GAME_PORT="${GAME_PORT:-25601}"
RCON_PORT="${RCON_PORT:-25581}"
RCON_PASS="${RCON_PASS:-test123}"
XMX="${XMX:-4G}"
PAPER_VERSION="${PAPER_VERSION:-1.21.8}"
BOT_NAME="${BOT_NAME:-IngestActor}"
# The actor bot needs mineflayer, which lives in the MAIN worktree's
# regression/bot/node_modules (a linked worktree has none). Resolve the main
# checkout from git's common dir so this works from any worktree without a
# hardcoded path.
_gc="$(git -C "$REPO" rev-parse --git-common-dir 2>/dev/null || echo .git)"
case "$_gc" in /*) MAIN_REPO="$(dirname "$_gc")" ;; *) MAIN_REPO="$REPO" ;; esac
BOT_BASE="${BOT_BASE:-$MAIN_REPO/regression/bot}"
[ -d "$BOT_BASE/node_modules/mineflayer" ] || \
  echo "[warn] mineflayer not found under $BOT_BASE/node_modules - set BOT_BASE to a checkout with 'npm install'ed regression/bot" >&2
SPARK_CHECK="${SPARK_CHECK:-0}"
# MUTATE=1 -> realistic break+place that toggles work cells air<->stone (both
# plugins log every event); MUTATE=0 -> non-mutating break-only.
MUTATE="${MUTATE:-0}"
MUTATE_BOOL=false; [ "$MUTATE" = "1" ] && MUTATE_BOOL=true

# Jars (defaults assume this worktree + the official CoreProtect 23.1 SQLite jar)
SPYGLASS_JAR="${SPYGLASS_JAR:-$REPO/spyglass/build/libs/Spyglass-1.0.2-shaded.jar}"
INGESTBENCH_JAR="${INGESTBENCH_JAR:-$HERE/plugin/build/IngestBench.jar}"
COREPROTECT_JAR="${COREPROTECT_JAR:-/Volumes/External-NVME/Documents/GitHub/MedievalRP/RP_Server/plugins/disabled/CoreProtect-sqlite-orig.jar}"

CONFIGS="${CONFIGS:-vanilla coreprotect spyglass}"
RATES="${RATES:-0 300 600 1200 2400 5000}"
RUNS="${RUNS:-3}"
WARMUP="${WARMUP:-90}"     # discard window for run 1 of a boot (JIT/cache/queue warm)
SETTLE="${SETTLE:-30}"    # shorter re-settle before runs 2..N (JVM already warm; lets buffers drain)
MEASURE="${MEASURE:-180}"

# Platform (a forceloaded flat stone slab the actor fires events over)
PX=0; PY=64; PZ=0; PSIZE=8

# Aikar flags (same across all configs); Xms=Xmx so AlwaysPreTouch is honoured.
AIKAR=(-Xms"$XMX" -Xmx"$XMX"
  -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200
  -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch
  -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M
  -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4
  -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90
  -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem
  -XX:MaxTenuringThreshold=1)

SERVER_PID=""
ACTOR_PID=""
ACTOR_LOG=""

ts() { date +%H:%M:%S; }
# say/die go to stderr: they are diagnostics, so they never pollute the stdout
# captured by command substitutions like pj=$(paper_jar) or $(count_rows).
say() { echo "[$(ts)] $*" >&2; }
die() { echo "[$(ts)] FATAL: $*" >&2; teardown; exit 1; }

# ── RCON (reuse the repo's net-only client; no node_modules needed) ──────────
rc() { SG_HOST=127.0.0.1 SG_RCON_PORT="$RCON_PORT" SG_RCON_PASS="$RCON_PASS" \
       SG_RCON_QUIET="${SG_RCON_QUIET:-400}" node "$REPO/regression/bot/rcon-cli.js" "$@" 2>/dev/null; }

# ── Paper download (mirrors scripts/boot-smoke.sh) ───────────────────────────
paper_jar() {
  local v="$PAPER_VERSION" out="$CACHE/paper-$PAPER_VERSION.jar"
  mkdir -p "$CACHE"
  if [ -s "$out" ]; then echo "$out"; return 0; fi
  local url
  url=$(curl -s -A spyglass-ingestbench "https://api.papermc.io/v2/projects/paper/versions/$v/builds" \
    | python3 -c "import sys,json
d=json.load(sys.stdin); b=d['builds'][-1]
print(f\"https://api.papermc.io/v2/projects/paper/versions/$v/builds/{b['build']}/downloads/{b['downloads']['application']['name']}\")" 2>/dev/null)
  [ -z "$url" ] && return 1
  say "downloading Paper $v ..."
  curl -s -A spyglass-ingestbench -o "$out" "$url" && [ -s "$out" ] && echo "$out"
}

# ── server dir + plugins ─────────────────────────────────────────────────────
setup_server_dir() {  # <dir> <config>
  local dir="$1" config="$2"
  rm -rf "$dir"
  mkdir -p "$dir/plugins"
  echo "eula=true" > "$dir/eula.txt"
  cat > "$dir/server.properties" <<EOF
online-mode=false
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
view-distance=4
simulation-distance=4
entity-broadcast-range-percentage=10
network-compression-threshold=-1
max-players=4
level-seed=ingestbench
server-port=$GAME_PORT
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASS
broadcast-rcon-to-ops=false
motd=ingestbench
EOF

  cp "$INGESTBENCH_JAR" "$dir/plugins/IngestBench.jar" || die "missing IngestBench jar: $INGESTBENCH_JAR"
  # IngestBench config (platform + where to write CSVs). Written before boot so
  # saveDefaultConfig keeps it; the plugin reloads it on each `start`.
  mkdir -p "$dir/plugins/IngestBench" "$CSV"
  cat > "$dir/plugins/IngestBench/config.yml" <<EOF
platform:
  world: world
  x: $PX
  y: $PY
  z: $PZ
  size: $PSIZE
output-dir: "$CSV"
mutate: $MUTATE_BOOL
EOF

  case "$config" in
    vanilla) : ;;
    coreprotect) cp "$COREPROTECT_JAR" "$dir/plugins/CoreProtect.jar" || die "missing CoreProtect jar: $COREPROTECT_JAR" ;;
    spyglass)    cp "$SPYGLASS_JAR" "$dir/plugins/Spyglass.jar" || die "missing Spyglass jar: $SPYGLASS_JAR" ;;
    both)
      cp "$COREPROTECT_JAR" "$dir/plugins/CoreProtect.jar" || die "missing CoreProtect jar"
      cp "$SPYGLASS_JAR" "$dir/plugins/Spyglass.jar" || die "missing Spyglass jar" ;;
    *) die "unknown config: $config" ;;
  esac
}

boot() {  # <dir>
  local dir="$1" pj; pj=$(paper_jar) || die "Paper download failed"
  local log="$dir/server.log"; : > "$log"
  say "booting $(basename "$dir") (Xmx=$XMX, port $GAME_PORT) ..."
  ( cd "$dir" && exec java "${AIKAR[@]}" ${JVM_EXTRA:-} -jar "$pj" --nogui ) > "$log" 2>&1 &
  SERVER_PID=$!
  local i=0
  while [ "$i" -lt 240 ]; do
    grep -qE 'Done \([0-9]' "$log" && { say "server up"; return 0; }
    kill -0 "$SERVER_PID" 2>/dev/null || die "server JVM exited during boot (see $log)"
    sleep 2; i=$((i+2))
  done
  die "server did not reach Done within 240s (see $log)"
}

connect_actor() {  # <dir>
  local dir="$1"
  ACTOR_LOG="$dir/actor.log"; : > "$ACTOR_LOG"
  SG_HOST=127.0.0.1 SG_PORT="$GAME_PORT" SG_BOT_NAME="$BOT_NAME" SG_BOT_VERSION="$PAPER_VERSION" \
    BOT_BASE="$BOT_BASE" node "$HERE/actor.mjs" > "$ACTOR_LOG" 2>&1 &
  ACTOR_PID=$!
  local i=0
  while [ "$i" -lt 60 ]; do
    grep -q 'ACTOR_SPAWNED' "$ACTOR_LOG" && { say "actor online"; return 0; }
    kill -0 "$ACTOR_PID" 2>/dev/null || die "actor exited before spawn (see $ACTOR_LOG)"
    sleep 1; i=$((i+1))
  done
  die "actor did not spawn within 60s (see $ACTOR_LOG)"
}

setup_world() {
  rc "forceload add $PX $PZ $((PX+15)) $((PZ+15))" >/dev/null
  # Stone platform at PY (break-only target, and the floor/against block for the
  # mutate toggle layer at PY+1). Clear air above it for the work layer + headroom.
  rc "fill $PX $PY $PZ $((PX+PSIZE-1)) $PY $((PZ+PSIZE-1)) stone" >/dev/null
  rc "fill $PX $((PY+1)) $PZ $((PX+PSIZE-1)) $((PY+8)) $((PZ+PSIZE-1)) air" >/dev/null
  # Actor perch well ABOVE the work layer, so mutate-mode toggling never disturbs
  # it (and it is harmless for break-only). A 3x3 stone pad at PY+9.
  rc "fill $((PX+3)) $((PY+9)) $((PZ+3)) $((PX+5)) $((PY+9)) $((PZ+5)) stone" >/dev/null
  rc "op $BOT_NAME" >/dev/null
  rc "op SparkProbe" >/dev/null   # the /spark cross-check joins as this op'd player
  rc "gamemode creative $BOT_NAME" >/dev/null
  rc "tp $BOT_NAME $((PX+4)).5 $((PY+10)) $((PZ+4)).5" >/dev/null
  # Hold conditions steady + remove unsynchronised noise (identical in every config).
  rc "gamerule doDaylightCycle false" >/dev/null
  rc "gamerule doWeatherCycle false" >/dev/null
  rc "gamerule randomTickSpeed 0" >/dev/null
  rc "gamerule doMobSpawning false" >/dev/null
  rc "gamerule doFireTick false" >/dev/null
  rc "weather clear" >/dev/null
  rc "time set day" >/dev/null
  rc "save-off" >/dev/null   # no autosave spikes during the measured window
  sleep 2
  say "world ready; players online: $(rc list)"
}

# Drive one warmup+measure window; block until the plugin finishes (poll status,
# not wall-clock, so low TPS just makes us wait longer).
run_window() {  # <rate> <warmup> <measure> <label>
  local rate="$1" warm="$2" meas="$3" label="$4"
  local total=$((warm+meas)) maxwait=$(( (warm+meas)*3 + 90 )) waited=0
  say "  window '$label': rate=$rate ev/s warmup=${warm}s measure=${meas}s"
  rc "ingestbench start $rate $warm $meas $label" >/dev/null
  sleep 3
  while [ "$waited" -lt "$maxwait" ]; do
    sleep 10; waited=$((waited+10))
    local st; st=$(rc "ingestbench status")
    case "$st" in
      *"running=false"*) say "  done: $st"; break ;;
    esac
    [ $((waited % 60)) -eq 0 ] && say "    ...$st"
  done
  if [ "$SPARK_CHECK" = "1" ]; then
    say "  cross-check (this window):"
    say "    getTickTimes> $(csv_stats "$label")"
    # spark sends its report to the *sender* via Adventure messaging, which RCON
    # cannot capture - so we run /spark health as an op'd player (mineflayer) and
    # read the reply from chat. It joins AFTER the measured window, so it never
    # perturbs the samples; spark's last-1m/5m window still covers the window.
    SG_HOST=127.0.0.1 SG_PORT="$GAME_PORT" SG_BOT_NAME=SparkProbe \
      SG_BOT_VERSION="$PAPER_VERSION" BOT_BASE="$BOT_BASE" \
      node "$HERE/spark-probe.mjs" 2>/dev/null \
      | grep -iE 'TPS from|Tick durations|CPU usage|%ile|^ +[0-9].*[/;]' | sed 's/^/    spark> /'
  fi
}

# Mean/p95/p99/max (ms) of a just-written CSV, for the spark cross-check.
csv_stats() {  # <label>
  local f="$CSV/ticks-$1.csv"
  [ -f "$f" ] || { echo "(no csv yet)"; return; }
  python3 - "$f" <<'PY'
import sys, statistics, math
v=[]
with open(sys.argv[1]) as fh:
    next(fh, None)
    for line in fh:
        p=line.rstrip().split(',')
        if len(p)==4:
            try: v.append(int(p[3])/1e6)
            except ValueError: pass
v.sort()
def pct(s,q):
    if not s: return float('nan')
    k=(len(s)-1)*q/100.0; f=math.floor(k); c=math.ceil(k)
    return s[int(k)] if f==c else s[f]*(c-k)+s[c]*(k-f)
print(f"n={len(v)} mean={statistics.fmean(v):.3f} p95={pct(v,95):.3f} p99={pct(v,99):.3f} max={max(v):.3f} (ms)" if v else "no samples")
PY
}

count_rows() {  # <dir> -> prints "sg=<n> cp=<n>"
  local dir="$1" sg="-" cp="-"
  local sgdb="$dir/plugins/Spyglass/spyglass.db"
  local cpdb="$dir/plugins/CoreProtect/database.db"
  command -v sqlite3 >/dev/null || { echo "sg=? cp=? (no sqlite3)"; return; }
  [ -f "$sgdb" ] && sg=$(sqlite3 "$sgdb" "SELECT COUNT(*) FROM records;" 2>/dev/null || echo "err")
  [ -f "$cpdb" ] && cp=$(sqlite3 "$cpdb" "SELECT COUNT(*) FROM co_block;" 2>/dev/null || echo "err")
  echo "sg=$sg cp=$cp"
}

stop_server() {
  [ -n "$SERVER_PID" ] || return 0
  say "stopping server ..."
  rc "save-on" >/dev/null 2>&1 || true
  rc "stop" >/dev/null 2>&1 || true
  local w=0
  while kill -0 "$SERVER_PID" 2>/dev/null && [ "$w" -lt 40 ]; do sleep 1; w=$((w+1)); done
  kill -TERM "$SERVER_PID" 2>/dev/null || true
  sleep 2; kill -9 "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
}

teardown_actor() {
  [ -n "$ACTOR_PID" ] || return 0
  kill -TERM "$ACTOR_PID" 2>/dev/null || true
  sleep 1; kill -9 "$ACTOR_PID" 2>/dev/null || true
  ACTOR_PID=""
}

teardown() { teardown_actor; stop_server; }
trap teardown EXIT INT TERM

# ── modes ────────────────────────────────────────────────────────────────────
do_phase0() {
  SPARK_CHECK=1
  say "=== PHASE 0: methodology validation ==="

  # Server A: BOTH plugins -> prove events reach both DBs, ~1 row/event each.
  local dirA="$WORK/srv-phase0-both"
  setup_server_dir "$dirA" both
  boot "$dirA"; connect_actor "$dirA"; setup_world
  say "--- /spark present? ---"; SG_RCON_QUIET=3000 rc "spark" | sed 's/^/    spark> /'
  say "--- baseline rate 0: idle MSPT (low) ---"
  run_window 0 15 45 "both-r0-run1"
  say "--- rate 600: events must reach BOTH plugins (now ~equal: 1 row/event) ---"
  say "  rows before: $(count_rows "$dirA")"
  run_window 600 20 60 "both-r600-run1"
  sleep 6   # let both async writers drain to their DBs
  say "  rows after : $(count_rows "$dirA")"
  stop_server; teardown_actor

  # Server B: VANILLA (generator only) -> the subtraction floor must be flat.
  local dirB="$WORK/srv-phase0-vanilla"
  setup_server_dir "$dirB" vanilla
  boot "$dirB"; connect_actor "$dirB"; setup_world
  say "--- generator-only baseline across rates (should be low + flat) ---"
  local r
  for r in 0 600 1200 2400; do run_window "$r" 15 45 "vanilla-r$r-run1"; done
  stop_server; teardown_actor

  say "=== PHASE 0 complete. CSVs in $CSV ==="
  ls -la "$CSV"/ticks-*.csv 2>/dev/null
}

# True if all RUNS CSVs for (config, rate) already exist (so we can resume).
cell_done() {  # <config> <rate>
  local config="$1" rate="$2" run
  for run in $(seq 1 "$RUNS"); do
    [ -f "$CSV/ticks-$config-r$rate-run$run.csv" ] || return 1
  done
  return 0
}

do_sweep() {
  say "=== SWEEP: configs=[$CONFIGS] rates=[$RATES] runs=$RUNS warmup=${WARMUP}s settle=${SETTLE}s measure=${MEASURE}s mutate=$MUTATE_BOOL ==="
  mkdir -p "$CSV"
  for config in $CONFIGS; do
    for rate in $RATES; do
      if cell_done "$config" "$rate"; then
        say "skip $config r$rate (already have $RUNS CSVs - resume)"
        continue
      fi
      local dir="$WORK/srv-$config-r$rate"
      setup_server_dir "$dir" "$config"
      boot "$dir"
      connect_actor "$dir"
      setup_world
      local run warm
      for run in $(seq 1 "$RUNS"); do
        warm="$WARMUP"; [ "$run" -gt 1 ] && warm="$SETTLE"
        run_window "$rate" "$warm" "$MEASURE" "$config-r$rate-run$run"
      done
      [ "$config" != vanilla ] && say "  final DB rows ($config r$rate): $(count_rows "$dir")"
      stop_server; teardown_actor
    done
  done
  say "=== SWEEP complete. CSVs in $CSV ==="
  ls "$CSV"/ticks-*.csv 2>/dev/null | wc -l | xargs echo "CSV files:"
}

case "$MODE" in
  phase0) do_phase0 ;;
  sweep)  do_sweep ;;
  *) die "unknown mode '$MODE' (use phase0 | sweep)" ;;
esac
