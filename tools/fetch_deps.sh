#!/usr/bin/env bash
# Laedt die Jars, gegen die Luna Map kompiliert wird, nach .deps/ (nicht im Repo).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPS="$ROOT/.deps"
mkdir -p "$DEPS"

fetch() { [ -s "$DEPS/$1" ] || curl -fsSL -o "$DEPS/$1" "$2"; }

MC_JSON="https://piston-meta.mojang.com/v1/packages/11f1aefe473eefba0af62e3286c33b692807b2eb/26.3.json"
curl -fsSL -o "$DEPS/26.3.json" "$MC_JSON"
fetch minecraft-26.3-client.jar "$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["downloads"]["client"]["url"])' "$DEPS/26.3.json")"
fetch fabric-loader-0.20.0.jar https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.20.0/fabric-loader-0.20.0.jar
fetch fabric-api-0.163.0+26.3.jar "https://cdn.modrinth.com/data/P7dR8mSH/versions/bq3SsNGk/fabric-api-0.163.0%2B26.3.jar"
fetch modmenu-21.0.0.jar https://cdn.modrinth.com/data/mOgUt4GM/versions/kyy7dbrZ/modmenu-21.0.0.jar
fetch iris-fabric-1.11.7+mc26.3.jar "https://cdn.modrinth.com/data/YL57xq9U/versions/vTN4NRGW/iris-fabric-1.11.7%2Bmc26.3.jar"

# Bibliotheken von Minecraft selbst (slf4j, joml, guava, ...)
mkdir -p "$DEPS/libs"
python3 -I - "$DEPS/26.3.json" <<'PY2' | while read -r url; do
import json, sys
for lib in json.load(open(sys.argv[1]))["libraries"]:
    art = lib.get("downloads", {}).get("artifact")
    if art and "natives" not in art["path"]:
        print(art["url"])
PY2
  f="$DEPS/libs/${url##*/}"; [ -s "$f" ] || curl -fsSL -o "$f" "$url"
done

# Fabric API ist ein Sammel-Jar: die benoetigten Module daraus auspacken
for m in fabric-api-base fabric-rendering-v1 fabric-key-mapping-api-v1 fabric-lifecycle-events-v1; do
  unzip -q -o -j "$DEPS/fabric-api-0.163.0+26.3.jar" "META-INF/jars/$m-*.jar" -d "$DEPS"
done
echo "Abhaengigkeiten in $DEPS"
