#!/usr/bin/env bash
# RCON-driven WorldEdit edit -> /spyglass rollback -> undo, for versions no
# mineflayer build can reach (26.x). Spyglass logs console WorldEdit
# EditSessions (#105), so a console //set is a real, rollbackable edit with no
# player. Isolated ports (25599/25579) - never touches the live RP_Server.
#
# Usage: scripts/worldedit-rollback-test.sh <jar> <version> [sqlite|mongo|clickhouse]
set -u
JAR_IN="${1:?jar}"; V="${2:?version}"; BE="${3:-sqlite}"
JAR="$(cd "$(dirname "$JAR_IN")" && pwd)/$(basename "$JAR_IN")"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${BOOT_SMOKE_DIR:-/Volumes/External-NVME/tmp-bootmatrix}"
WE_JAR="$WORK/worldedit.jar"
pj="$WORK/paper-cache/paper-$V.jar"
[ -s "$pj" ] || { echo "FAIL  $V  no cached paper jar"; exit 2; }
[ -s "$WE_JAR" ] || { echo "FAIL  $V  no worldedit.jar at $WE_JAR"; exit 2; }

GAME_PORT=25599; RPORT=25579
for p in "$GAME_PORT" "$RPORT"; do
  lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 && { echo "FAIL  $V  port $p busy"; exit 2; }
done

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
rcon.port=$RPORT
rcon.password=test123
motd=we-rollback
EOF
cfg="$dir/plugins/Spyglass/config.conf"
cp "$ROOT/spyglass/src/main/resources/config.conf" "$cfg"
case "$BE" in
  mongo)      sed -i '' -e 's/^  backend = .*/  backend = "mongo"/' "$cfg" ;;
  clickhouse) sed -i '' -e 's/^  backend = .*/  backend = "clickhouse"/' -e 's/port = 8123/port = 18123/' -e 's/password = ""/password = "sgtest"/' "$cfg" ;;
  *)          sed -i '' -e 's/^  backend = .*/  backend = "sqlite"/' "$cfg" ;;
esac
rm -f "$dir/plugins"/*.jar
cp "$JAR" "$dir/plugins/Spyglass.jar"
cp "$WE_JAR" "$dir/plugins/worldedit.jar"

log="$dir/we-server.log"; : > "$log"
( cd "$dir" && exec java -Xms1G -Xmx2G -jar "$pj" --nogui ) > "$log" 2>&1 &
pid=$!
ok=""
for i in $(seq 1 220); do
  grep -qE 'Done \([0-9]' "$log" && { ok=1; break; }
  kill -0 "$pid" 2>/dev/null || break
  sleep 2
done
[ -n "$ok" ] || { echo "FAIL  $V  server-never-ready"; tail -12 "$log"; kill -9 "$pid" 2>/dev/null; exit 2; }
sleep 3

export SG_RCON_PORT="$RPORT" SG_RCON_PASS=test123 SG_HOST=127.0.0.1
R() { node "$ROOT/regression/bot/rcon-cli.js" "$@"; }
isblk() { R "execute if block $1 $2 $3 minecraft:$4" | grep -qi passed; }

x0=16000; y=80; z0=16000; x1=16004; z1=16004
checks=0; fails=0
ck() { checks=$((checks+1)); if [ "$1" = 0 ]; then echo "ok    $2"; else echo "FAIL  $2"; fails=$((fails+1)); fi; }

R "forceload add $x0 $z0 $x1 $z1" >/dev/null
R "fill $x0 $y $z0 $x1 $y $z1 stone" >/dev/null; sleep 1
isblk "$x0" "$y" "$z0" stone; ck $? "region filled with stone (unlogged setup)"

# Console WorldEdit: set the world + selection, then //set air (a logged edit).
echo "  //world  -> $(R '//world world' | head -c 80)"
echo "  //pos1   -> $(R "//pos1 $x0,$y,$z0" | head -c 80)"
echo "  //pos2   -> $(R "//pos2 $x1,$y,$z1" | head -c 80)"
weset=""
for form in "//set air" "/set air" "worldedit:/set air"; do
  resp="$(R "$form")"
  echo "  set [$form] -> $(echo "$resp" | head -c 90)"
  if echo "$resp" | grep -qiE 'operation completed|block.{0,3}(changed|affected)|[0-9]+ block'; then weset="$form"; break; fi
done
sleep 5
isblk "$x0" "$y" "$z0" air; ck $? "console //set air cleared the region (WE edit logged)"

# search: async result lines aren't reliably captured over RCON; the proof
# that the query path works is the rollback below (it queries the same records).
# Here we only assert the command executes (no Adventure/internal crash).
sg="$(SG_RCON_QUIET=4000 R "spyglass search t:180s -g")"
echo "  search   -> $(echo "$sg" | tr '\n' ' ' | head -c 120)"
if echo "$sg" | grep -qi 'internal error'; then ck 1 "/spyglass search executed"; else ck 0 "/spyglass search executed (no crash)"; fi

# rollback: the functional proof - the WE-cleared region returns to stone.
echo "  rollback -> $(SG_RCON_QUIET=4000 R "spyglass rollback t:180s -g" | tr '\n' ' ' | head -c 120)"
sleep 3
isblk "$x0" "$y" "$z0" stone; ck $? "rollback restored the stone region (near corner)"
isblk "$x1" "$y" "$z1" stone; ck $? "rollback restored the stone region (far corner)"

# undo is player-scoped (pops the player's stack), so from console it must cleanly
# report "must be a player" - which proves the command executes AND renders an
# Adventure component (the asComponent fix). Undo's world effect is covered by the
# 1.21.x bot gameplay tests.
un="$(SG_RCON_QUIET=2000 R "spyglass undo")"
echo "  undo     -> $(echo "$un" | tr '\n' ' ' | head -c 80)"
if echo "$un" | grep -qi 'internal error'; then ck 1 "/spyglass undo executed"; else ck 0 "/spyglass undo executed + rendered feedback (Adventure ok)"; fi

kill -TERM "$pid" 2>/dev/null
w=0; while kill -0 "$pid" 2>/dev/null && [ "$w" -lt 30 ]; do sleep 1; w=$((w+1)); done
kill -9 "$pid" 2>/dev/null; wait "$pid" 2>/dev/null

echo "WE-ROLLBACK  $V  $BE  $([ "$fails" = 0 ] && echo PASS || echo FAIL)  $((checks-fails))/$checks  (weset=${weset:-none})"
exit $([ "$fails" = 0 ] && echo 0 || echo 1)
