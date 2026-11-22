#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
revision=47c4761d05b27f884247480ac3fb8a6657907325
source=.build/openxr
mkdir -p .build
if [[ ! -d "$source/.git" ]]; then
    git clone --quiet --depth 1 --branch release-1.1.49 https://github.com/KhronosGroup/OpenXR-SDK-Source.git "$source"
fi
if [[ "$(git -C "$source" rev-parse HEAD)" != "$revision" ]]; then
    echo "Unexpected OpenXR source revision" >&2
    exit 1
fi
patch="$PWD/openxr-cinema.patch"
if git -C "$source" apply --check "$patch" 2>/dev/null; then
    git -C "$source" apply "$patch"
else
    git -C "$source" apply --reverse --check "$patch"
fi
app="$source/src/tests/hello_xr"
# The generated copy must also reflect Java files removed from the overlay.
rm -rf "$app/src/main/java/dev/aniart/vr"
cp -R overlay/. "$app/"
python3 generate_shaders.py Anime4K_Upscale_CNN_x2_S.glsl "$app/anime4k_generated.h"
cd "$app"
./gradlew --no-daemon assembleDebug "$@"
