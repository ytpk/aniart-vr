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
display_patch="$PWD/openxr-display.patch"
if ! git -C "$source" apply --reverse --check "$display_patch" 2>/dev/null; then
    patch="$PWD/openxr-cinema.patch"
    if git -C "$source" apply --check "$patch" 2>/dev/null; then
        git -C "$source" apply "$patch"
    else
        git -C "$source" apply --reverse --check "$patch"
    fi
    git -C "$source" apply "$display_patch"
fi
app="$source/src/tests/hello_xr"
# The generated copy must also reflect Java files removed from the overlay.
rm -rf "$app/src/main/java/dev/aniart/vr"
cp -R overlay/. "$app/"
weights="$PWD/.build/depth"
mkdir -p "$weights" "$app/src/main/assets/depth"
zip_release=https://github.com/tB0nE/nightfall/releases/download/edgepad-models-2026-10-08
copy_depth_model() {
    local base=$1 name=$2 hash=$3
    if ! printf '%s  %s\n' "$hash" "$weights/$name" | sha256sum --check --status 2>/dev/null; then
        curl -fsSL --retry 3 "$base/$name${4:-}" -o "$weights/$name.part"
        printf '%s  %s\n' "$hash" "$weights/$name.part" | sha256sum --check --status
        mv "$weights/$name.part" "$weights/$name"
    fi
    cp "$weights/$name" "$app/src/main/assets/depth/"
}
copy_depth_model "$zip_release" zipdepth-wide-320x180-t320x192-edgepad-gpu.tflite 9dfd7519b3d1b208b7ad74caa55b9097e35ca537b03fdd0ec690d8cfce56f2ec
copy_depth_model "$zip_release" zipdepth-base-256-cpu.tflite bcca2459c6e97b7bd15c009175fd7372b320522d70627f958590a4385576cbf4
vda_release=https://huggingface.co/EmreGneri/video-depth-anything-small-stream/resolve/56ff0a88aaf2262d50d5d82d69b47e9d40e1c991
copy_depth_model "$vda_release" encoder_fp16.onnx 3c774358e5dc294fd69366869e4cae5eb27d240658194043c291899cc577f6a1 '?download=true'
copy_depth_model "$vda_release" bootstrap_fp16.onnx bc8bef05465c2a80e7ac2ee166a7b553bf0e54f4c64f328866b24a9ebd34d564 '?download=true'
copy_depth_model "$vda_release" stream_fp16.onnx 9120ea5b7b45730c246f9f41cb5352bfe5988097f5912dd29c35ba6a4d7a227b '?download=true'
if [[ "${ANIART_DEPTH_BENCH:-0}" == 1 ]]; then
    limbo=LimboV3_504x280_fp32.onnx
    int8=limbo-v3-cpu-dynamic-int8.onnx
    copy_depth_model https://github.com/NevermindNilas/TAS-Models-Host/releases/download/main "$limbo" 6a8f894f08fdb651036c3e6875bf357574b03003a4da2ae1ca9e82b66fc8534f
    if [[ ! -f "$weights/$int8" || tools/quantize_depth.py -nt "$weights/$int8" || "$weights/$limbo" -nt "$weights/$int8" ]]; then
        python3 tools/quantize_depth.py "$weights/$limbo" "$weights/$int8.part"
        mv "$weights/$int8.part" "$weights/$int8"
    fi
    cp "$weights/$int8" "$app/src/main/assets/depth/"
else
    rm -f "$app/src/main/assets/depth/"{LimboV3_504x280_fp32.onnx,limbo-v3-cpu-dynamic-int8.onnx}
fi
python3 generate_shaders.py Anime4K_Upscale_CNN_x2_S.glsl "$app/anime4k_generated.h"
cd "$app"
./gradlew --no-daemon assembleDebug "$@"
