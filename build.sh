#!/usr/bin/env bash
# Baut dist/luna-map-<version>.jar. Braucht JDK 25, python3, zip, curl.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION="2.1.1+26.3"
OUT="$ROOT/dist/luna-map-$VERSION.jar"
DEPS="$ROOT/.deps"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

"$ROOT/tools/fetch_deps.sh" >/dev/null
CP="$(find "$DEPS" -name '*.jar' | sort | tr '\n' ':')"

mkdir -p "$WORK/jar" "$ROOT/dist"
"$JAVAC" -nowarn --release 25 -encoding UTF-8 -cp "$CP" -d "$WORK/jar" \
  $(find "$ROOT/src/main/java" -name '*.java')

cp -r "$ROOT/src/main/resources/." "$WORK/jar/"
sed -i "s/\${version}/$VERSION/" "$WORK/jar/fabric.mod.json"
cp "$ROOT/LICENSE" "$WORK/jar/LICENSE-luna-map"

rm -f "$OUT"
(cd "$WORK/jar" && zip -q -X -r "$OUT" .)
echo "Fertig: $OUT"
