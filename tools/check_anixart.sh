#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
json_jar=${ANIXART_JSON_JAR:-"$test_dir/json.jar"}
if [[ ! -f "$json_jar" ]]; then
    curl -fsSL --retry 3 https://maven-central.storage-download.googleapis.com/maven2/org/json/json/20240303/json-20240303.jar -o "$json_jar"
fi
expected=3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
printf '%s  %s\n' "$expected" "$json_jar" | sha256sum --check --status

bin=${JAVA_HOME:+$JAVA_HOME/bin/}
source=overlay/src/main/java/dev/aniart/vr
"${bin}javac" -encoding UTF-8 -cp "$json_jar" -d "$test_dir" \
    "$source/AnixartCatalog.java" "$source/AnixartPlayback.java" "$source/KodikStreams.java" \
    "$source/IncomingVideo.java" tools/tests/AnixartCheck.java
"${bin}java" -cp "$test_dir:$json_jar" dev.aniart.vr.AnixartCheck "$@"
