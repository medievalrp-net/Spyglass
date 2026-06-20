#!/usr/bin/env bash
# Boot a throwaway RCON-enabled server for <version>/<backend>, run the SG-only
# gameplay+rollback scenario bot against it, capture PASS/FAIL, stop the server.
# Reuses the Paper jar cached by boot-smoke.sh. Never touches the live RP_Server.
#
# Usage: scripts/gameplay-test.sh <jar> <version> [sqlite|mongo|clickhouse]
set -u
JAR_IN="${1:?usage: gameplay-test.sh <jar> <version> [backend]}"
V="${2:?version}"; BE="${3:-sqlite}"
JAR="$(cd "$(dirname "$JAR_IN")" && pwd)/$(basename "$JAR_IN")"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${BOOT_SMOKE_DIR:-/Volumes/External-NVME/tmp-bootmatrix}"
pj="$WORK/paper-cache/paper-$V.jar"
[ -s "$pj" ] || { echo "FAIL  $V  $BE  no cached paper jar (run boot-smoke first)"; exit 2; }

# Isolated ports (the live bench/RP_Server uses 25566/25576 - never touch it).
GAME_PORT=25599; RCON_PORT=25579
for p in "$GAME_PORT" "$RCON_PORT"; do
  if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "FAIL  $V  $BE  test port $p busy (refusing to collide)"; exit 2
  fi
done

# Reuse the boot-smoke server dir for this version so the Paper remap + the
# lean jar's downloaded libraries are already cached (a fresh dir re-remaps).
dir="$WORK/srv-$V"
mkdir -p "$dir/plugins/Spyglass"
echo "eula=true" > "$dir/eula.txt"
cat > "$dir/server.properties" <<EOF
online-mode=false
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=6
simulation-distance=6
max-players=4
server-port=$GAME_PORT
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=test123
motd=gameplay
EOF

cfg="$dir/plugins/Spyglass/config.conf"
cp "$ROOT/spyglass/src/main/resources/config.conf" "$cfg"
case "$BE" in
  mongo)      sed -i '' -e 's/^  backend = .*/  backend = "mongo"/' "$cfg" ;;
  clickhouse) sed -i '' -e 's/^  backend = .*/  backend = "clickhouse"/' \
                        -e 's/port = 8123/port = 18123/' \
                        -e 's/password = ""/password = "sgtest"/' "$cfg" ;;
  *)          sed -i '' -e 's/^  backend = .*/  backend = "sqlite"/' "$cfg" ;;
esac
rm -f "$dir/plugins"/*.jar
cp "$JAR" "$dir/plugins/Spyglass.jar"

log="$dir/server.log"; : > "$log"
( cd "$dir" && exec java -Xms1G -Xmx2G -jar "$pj" --nogui ) > "$log" 2>&1 &
pid=$!

ok=""
for i in $(seq 1 220); do
  grep -qE 'Done \([0-9]' "$log" && { ok=1; break; }
  kill -0 "$pid" 2>/dev/null || break
  sleep 2
done
if [ -z "$ok" ]; then
  echo "FAIL  $V  $BE  server-never-ready"; tail -15 "$log"
  kill -9 "$pid" 2>/dev/null; exit 2
fi
sleep 3

export BOT_VERSION="$V" BOT_NAME="gp$(echo "$V" | tr -cd '0-9')"
export SG_HOST=127.0.0.1 SG_PORT="$GAME_PORT" SG_RCON_PORT="$RCON_PORT" SG_RCON_PASS=test123
node "$ROOT/regression/bot/_version-gameplay.js"; rc=$?

kill -TERM "$pid" 2>/dev/null
w=0; while kill -0 "$pid" 2>/dev/null && [ "$w" -lt 30 ]; do sleep 1; w=$((w+1)); done
kill -9 "$pid" 2>/dev/null; wait "$pid" 2>/dev/null

echo "GAMEPLAY-RESULT  $V  $BE  rc=$rc"
exit $rc
