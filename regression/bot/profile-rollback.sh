#!/bin/bash
# Profile a Spyglass 2M rollback with Sundial, against EXISTING data (no
# re-ingest). Runs a `main`-thread profile (what eats tick time → lag
# spikes) then an `all`-threads profile (where the off-main work goes).
# Usage: profile-rollback.sh <botTag>
#
# Run this in the BACKGROUND (sleeps are fine there). Sundial is ONLY on
# during these profiling rollbacks — never during the compare.js timing
# run, where it would bias SG's MSPT via forced safepoints.
TAG="$1"
RP="${RP_SERVER:-$(cd "$(dirname "$0")/../../../RP_Server" 2>/dev/null && pwd)}"
LOG="$RP/logs/latest.log"
cd "$(dirname "$0")" || exit 2

profile_one () {
  MODE="$1"
  before=$(grep -c "Spyglass rollback .* timings" "$LOG")
  echo ">>> sundial start $MODE  +  /spyglass rollback p:$TAG t:60m -g"
  node rcon-send.js "sundial start $MODE 5" >/dev/null 2>&1
  sleep 1
  node rcon-send.js "spyglass rollback p:$TAG t:60m -g" >/dev/null 2>&1
  # Wait for a NEW rollback-completion line in the server log.
  for i in $(seq 1 120); do
    now=$(grep -c "Spyglass rollback .* timings" "$LOG")
    if [ "$now" -gt "$before" ]; then break; fi
    sleep 1
  done
  sleep 1
  node rcon-send.js "sundial stop" >/dev/null 2>&1
  sleep 2
  echo "   timings: $(grep 'Spyglass rollback .* timings' "$LOG" | tail -1 | sed 's/.*\] //')"
  echo "   report : $(ls -t "$RP/plugins/Sundial/reports/"*.txt 2>/dev/null | head -1)"
}

echo "=== PROFILE: main thread (lag-spike attribution) ==="
profile_one main
sleep 3
echo "=== PROFILE: all threads (work distribution) ==="
profile_one all
echo "=== done ==="
