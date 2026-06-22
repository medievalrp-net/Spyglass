#!/usr/bin/env bash
# Compile the IngestBench Paper plugin against the cached paper-api 1.21.8 and
# package it as a jar. Uses the host JDK with --release 21 (Paper 1.21.8 runs on
# Java 21); no Gradle, no network (paper-api is already in the Gradle cache from
# the main build).
#
# Usage: regression/ingest-bench/plugin/build.sh
# Output: regression/ingest-bench/plugin/build/IngestBench.jar
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"
CLASSES="$OUT/classes"

CACHE="$HOME/.gradle/caches/modules-2"
PAPER_API="$(find "$CACHE" -name 'paper-api-1.21.8-R0.1-SNAPSHOT.jar' 2>/dev/null | head -1)"
if [ -z "$PAPER_API" ]; then
  echo "paper-api 1.21.8 not found in the Gradle cache. Run './gradlew :spyglass:compileJava' once to populate it." >&2
  exit 1
fi

# paper-api references adventure + jetbrains annotations transitively; add the
# newest cached copy of each so javac can resolve the JavaPlugin supertypes.
pick() { find "$CACHE" -name "$1" 2>/dev/null | grep -viE 'sources|javadoc' | sort -V | tail -1; }
CP="$PAPER_API"
for art in 'adventure-api-*.jar' 'adventure-key-*.jar' 'examination-api-*.jar' 'annotations-26*.jar' \
           'guava-3*.jar' 'bungeecord-chat-*.jar' 'gson-*.jar' 'snakeyaml-*.jar'; do
  j="$(pick "$art")"
  [ -n "$j" ] && CP="$CP:$j"
done

rm -rf "$OUT"
mkdir -p "$CLASSES"
echo "Compile classpath:"; echo "$CP" | tr ':' '\n' | sed 's#.*/#  #'
javac --release 21 -Xlint:all,-options -cp "$CP" -d "$CLASSES" \
  $(find "$HERE/src" -name '*.java')

jar --create --file "$OUT/IngestBench.jar" -C "$CLASSES" . -C "$HERE/resources" .
echo "Built: $OUT/IngestBench.jar"
