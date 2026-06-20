#!/usr/bin/env bash
# Boot-smoke a Spyglass plugin jar across one or more Paper versions.
#
# For each version it downloads the latest Paper build (v2 API for 1.21.x,
# v3 fill API for 26.x), drops the jar into a throwaway server configured with
# the default embedded-SQLite backend, boots headless, and checks that Spyglass
# enables cleanly and the server reaches "Done". It never touches the live
# RP_Server.
#
# Usage:  scripts/boot-smoke.sh <jar> <version> [<version> ...]
# Env:    BOOT_SMOKE_DIR  scratch dir (default /Volumes/External-NVME/tmp-bootmatrix)
#         BOOT_TIMEOUT    seconds to wait for "Done" per boot (default 360)
set -u

JAR_IN="${1:?usage: boot-smoke.sh <jar> <version>...}"; shift
JAR="$(cd "$(dirname "$JAR_IN")" && pwd)/$(basename "$JAR_IN")"
WORK="${BOOT_SMOKE_DIR:-/Volumes/External-NVME/tmp-bootmatrix}"
TIMEOUT="${BOOT_TIMEOUT:-360}"
CACHE="$WORK/paper-cache"
UA='spyglass-bootsmoke'
mkdir -p "$CACHE"
label="$(basename "$JAR")"

paper_jar() {  # version -> cached local paper jar path (download if needed)
  local v="$1" out="$CACHE/paper-$1.jar"
  if [ -s "$out" ]; then echo "$out"; return 0; fi
  local url=""
  case "$v" in
    1.2*.*)
      url=$(curl -s -A "$UA" "https://api.papermc.io/v2/projects/paper/versions/$v/builds" \
        | python3 -c "import sys,json
d=json.load(sys.stdin); b=d['builds'][-1]
print(f\"https://api.papermc.io/v2/projects/paper/versions/$v/builds/{b['build']}/downloads/{b['downloads']['application']['name']}\")" 2>/dev/null) ;;
    *)
      url=$(curl -s -A "$UA" "https://fill.papermc.io/v3/projects/paper/versions/$v/builds" \
        | python3 -c "import sys,json
d=json.load(sys.stdin); b=d[0] if isinstance(d,list) else d['builds'][0]
print(b['downloads']['server:default']['url'])" 2>/dev/null) ;;
  esac
  [ -z "$url" ] && return 1
  curl -s -A "$UA" -o "$out" "$url" && [ -s "$out" ] && echo "$out"
}

boot_one() {  # version -> echoes "PASS|FAIL  version  reason  logpath"
  local v="$1"
  local dir="$WORK/srv-$v"
  local pj; pj=$(paper_jar "$v") || { echo "FAIL  $v  paper-download-failed  -"; return; }
  mkdir -p "$dir/plugins"
  echo "eula=true" > "$dir/eula.txt"
  cat > "$dir/server.properties" <<EOF
online-mode=false
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=2
server-port=0
motd=bootsmoke
EOF
  rm -rf "$dir/plugins"/*.jar "$dir/plugins/Spyglass"
  cp "$JAR" "$dir/plugins/Spyglass.jar"

  local log="$dir/boot-$label.log"; : > "$log"
  ( cd "$dir" && exec java -Xms512M -Xmx1024M -jar "$pj" --nogui ) > "$log" 2>&1 &
  local pid=$!

  local reason="timeout" i=0
  while [ "$i" -lt "$TIMEOUT" ]; do
    if grep -qE 'Done \([0-9]' "$log"; then reason="done"; break; fi
    if grep -qiE 'Could not load .*Spyglass|Error occurred while enabling Spyglass' "$log"; then reason="enable-error"; break; fi
    if grep -q 'No suitable driver' "$log"; then reason="no-sqlite-driver"; break; fi
    kill -0 "$pid" 2>/dev/null || { reason="jvm-exit"; break; }
    sleep 2; i=$((i+2))
  done

  kill -TERM "$pid" 2>/dev/null
  local w=0
  while kill -0 "$pid" 2>/dev/null && [ "$w" -lt 30 ]; do sleep 1; w=$((w+1)); done
  kill -9 "$pid" 2>/dev/null; wait "$pid" 2>/dev/null

  local enabling enableErr done_
  enabling=$(grep -cE 'Enabling Spyglass' "$log")
  enableErr=$(grep -cE 'Error occurred while enabling Spyglass|Could not load .*Spyglass' "$log")
  done_=$(grep -cE 'Done \([0-9]' "$log")
  if [ "$done_" -gt 0 ] && [ "$enabling" -gt 0 ] && [ "$enableErr" -eq 0 ]; then
    echo "PASS  $v  enabled+done  $log"
  else
    echo "FAIL  $v  $reason  $log"
  fi
}

{
  printf '%-6s %-9s %-18s %s\n' RESULT VERSION REASON LOG
  for v in "$@"; do boot_one "$v"; done
} | tee "$WORK/results-$label.txt"
