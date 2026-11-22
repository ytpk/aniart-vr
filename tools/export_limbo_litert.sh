#!/usr/bin/env bash
set -euo pipefail
destination=$(realpath -m "${1:?output .tflite path required}")
root=$(cd "$(dirname "$0")/.." && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
cd "$scratch"
curl -fsSL --retry 3 https://codeload.github.com/ByteDance-Seed/Depth-Anything-3/tar.gz/3d835ec1a5802d64a8b8b15f817a1ab54809bfe4 | tar xz --strip-components=1
curl -fsSL --retry 3 https://huggingface.co/litert-community/Depth-Anything-3-Small/raw/5cd25d936e3fde2edef68f53b4123401454ac9c8/convert_da3.py -o convert_da3.py
base=https://raw.githubusercontent.com/NevermindNilas/Ai-models/4c0950ecc08629cda85754d0766d6deb2e95495a/Limbo-V3
curl -fsSL --retry 3 "$base/Limbo.safetensors" -o Limbo.safetensors
curl -fsSL --retry 3 "$base/Limbo_504x280_fp32.onnx" -o Limbo.onnx
sha256sum -c <<'HASHES'
8aa1dccf97bd7f1bca7d051ffa983dfe6faeed2356ba5eb9db63808d49f036f3  Limbo.safetensors
6a8f894f08fdb651036c3e6875bf357574b03003a4da2ae1ca9e82b66fc8534f  Limbo.onnx
HASHES
OMP_NUM_THREADS=2 python "$root/tools/export_limbo_litert.py"
mkdir -p "$(dirname "$destination")"
cp da3_small_gpu.tflite "$destination"
