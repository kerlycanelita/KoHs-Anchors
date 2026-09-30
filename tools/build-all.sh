#!/usr/bin/env bash
# Builds KoHs Anchor's for every Minecraft version in gradle/versions.properties, checks every
# Mixin target against that version's bytecode, and copies the playable jars to dist/<mod_version>/
# with their SHA-256 sums. The same as build-all.ps1, for Linux and macOS (and the release workflow).
#
#   tools/build-all.sh
#   tools/build-all.sh 26.2 26.3
#
# Needs JDK 25 (JAVA_HOME or the java on PATH) and Python 3.
set -euo pipefail
cd "$(dirname "$0")/.."

versions=("$@")
if [ ${#versions[@]} -eq 0 ]; then
    mapfile -t versions < <(grep -E '^[0-9][^#=]*=' gradle/versions.properties | cut -d= -f1 | tr -d ' ')
fi

mod_version=$(grep '^mod_version=' gradle.properties | cut -d= -f2 | tr -d ' \r')
dist="dist/$mod_version"
mkdir -p "$dist"

for version in "${versions[@]}"; do
    echo "== Minecraft $version"
    bash ./gradlew build "-Pmc=$version" --console=plain
    cp "build/libs/kohs-anchors-$version-$mod_version.jar" "$dist/"
done

python3 tools/verify_mixin_targets.py "${versions[@]}"

(cd "$dist" && sha256sum -- *.jar > CHECKSUMS.sha256)
echo "Jars in $dist"
