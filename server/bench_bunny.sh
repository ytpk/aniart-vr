#!/usr/bin/env bash
# Run a local video benchmark without Quest or an APK.
set -uo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(dirname "$script_dir")
checkpoint=${1:-"$script_dir/models/Limbo.onnx"}
device=${2:-/dev/dri/renderD128}
render_node=$(readlink -f -- "$device" 2>/dev/null) || render_node=$device
gpu_sysfs="/sys/class/drm/${render_node##*/}/device"
python_bin=${PYTHON:-python}
refine_sweep=${ANIART_DEPTH_REFINE_SWEEP:-0}
pipeline_sweep=${ANIART_DEPTH_PIPELINE_SWEEP:-0}
run_dir=$(mktemp -d "$PWD/aniart-bench-$(date +%Y%m%d-%H%M%S)-XXXXXX") || exit 1
reports="$run_dir/logs"
mkdir -p "$reports" "$run_dir/input" "$run_dir/video" || exit 1

server_pid=
source_pid=
gpu_pid=
stop_server() {
    if [[ -n "$server_pid" ]]; then
        kill -TERM -- "-$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
        server_pid=
    fi
}

stop_gpu_monitor() {
    if [[ -n "$gpu_pid" ]]; then
        kill "$gpu_pid" 2>/dev/null || true
        wait "$gpu_pid" 2>/dev/null || true
        gpu_pid=
    fi
}

sample_gpu() {
    local sensor column label value row header=unix_s
    local -a sensors=()
    for sensor in "$gpu_sysfs"/{gpu_busy_percent,mem_busy_percent} \
        "$gpu_sysfs"/hwmon/hwmon*/{temp[0-9]*_input,power[0-9]*_average,power[0-9]*_input,freq[0-9]*_input}; do
        [[ -r "$sensor" ]] || continue
        sensors+=("$sensor")
        column=${sensor##*/}
        if [[ "$column" == *_input ]] &&
            IFS= read -r label 2>/dev/null < "${sensor%_input}_label"; then
            column+=":${label//,/_}"
        fi
        header+=",$column"
    done
    (( ${#sensors[@]} )) || return 0
    {
        printf '%s\n' "$header"
        while true; do
            row=$EPOCHREALTIME
            for sensor in "${sensors[@]}"; do
                IFS= read -r value 2>/dev/null < "$sensor" || value=
                row+=",$value"
            done
            printf '%s\n' "$row"
            sleep 1
        done
    } >"$1"
}

finish() {
    stop_gpu_monitor
    stop_server
    if [[ -n "$source_pid" ]]; then
        kill "$source_pid" 2>/dev/null || true
        wait "$source_pid" 2>/dev/null || true
    fi
    if tar -C "$run_dir" -czf "$run_dir/logs.tar.gz" logs; then
        printf '\nПрикрепи этот архив: %s\n' "$run_dir/logs.tar.gz"
    fi
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for tool in "$python_bin" curl ffmpeg ffprobe setsid timeout tar; do
    if ! command -v "$tool" >/dev/null; then
        printf 'Не найдена команда %s\n' "$tool" | tee -a "$reports/summary.txt"
        exit 1
    fi
done
if [[ ! -r "$checkpoint" ]]; then
    printf 'Не найдена модель: %s\n' "$checkpoint" | tee -a "$reports/summary.txt"
    exit 1
fi

{
    ffmpeg -version
    "$python_bin" -c 'import sys, cv2, numpy, onnxruntime as ort; print(sys.version); print("OpenCV", cv2.__version__, "NumPy", numpy.__version__, "ORT", ort.__version__, ort.get_available_providers())'
    if command -v lspci >/dev/null; then lspci -nnk | grep -iEA3 'VGA|Display'; fi
    printf '\nVAAPI: %s → ' "$device"
    readlink -f "$gpu_sysfs"
    ls -l "$device"
    if command -v vainfo >/dev/null; then timeout 15 vainfo --display drm --device "$device"; fi
    if command -v vulkaninfo >/dev/null; then timeout 20 vulkaninfo --summary; fi
} >"$reports/system.log" 2>&1

input_file="$run_dir/input/bunny.mp4"
if [[ -n "${ANIART_BENCH_SOURCE:-}" ]]; then
    clip=$ANIART_BENCH_SOURCE
    [[ "$clip" = /* ]] || clip="$PWD/$clip"
    input_file="$run_dir/input/clip"
    if [[ ! -f "$clip" || ! -r "$clip" ]] || ! ln -s -- "$clip" "$input_file"; then
        printf 'Не удалось открыть ANIART_BENCH_SOURCE.\n' | tee -a "$reports/summary.txt"
        exit 1
    fi
else
    clip=https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4
    if ! curl -fSL --retry 2 --connect-timeout 10 --max-time 60 "$clip" -o "$input_file" >"$reports/download.log" 2>&1; then
        printf 'Не удалось скачать Big Buck Bunny. См. download.log.\n' | tee -a "$reports/summary.txt"
        exit 1
    fi
fi
if ! ffprobe -v error -show_streams -show_format -of json "$input_file" >"$reports/source.json" 2>&1; then
    printf 'Не удалось прочитать видео. См. source.json.\n' | tee -a "$reports/summary.txt"
    exit 1
fi

# Serve only the test file. An ephemeral port avoids touching existing services.
"$python_bin" -u - "$run_dir/input" >"$reports/source-http.log" 2>&1 <<'PY' &
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import sys

with ThreadingHTTPServer(("127.0.0.1", 0), partial(SimpleHTTPRequestHandler, directory=sys.argv[1])) as server:
    print(server.server_port, flush=True)
    server.serve_forever()
PY
source_pid=$!
source_port=
for ((attempt=0; attempt<30; attempt++)); do
    if [[ -s "$reports/source-http.log" ]]; then
        read -r source_port <"$reports/source-http.log" || true
    fi
    [[ "$source_port" =~ ^[0-9]+$ ]] && break
    sleep .1
done
if [[ ! "$source_port" =~ ^[0-9]+$ ]]; then
    printf 'Локальный HTTP-источник не запустился. См. source-http.log.\n' | tee -a "$reports/summary.txt"
    exit 1
fi
payload=$(printf '{"url":"http://127.0.0.1:%s/%s"}' "$source_port" "${input_file##*/}")
token=$("$python_bin" -c 'import secrets; print(secrets.token_urlsafe(24))') || exit 1

run_profile() {
    local name=$1 interval=$2 port status response path stream_code attempt ready=false
    shift 2
    printf '\n=== %s ===\n' "$name" | tee -a "$reports/summary.txt"
    port=$("$python_bin" -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])') || return
    local base="http://127.0.0.1:$port"
    local log="$reports/$name.server.log"
    local raw="$run_dir/input/$name.gray" preview_size=504x280 preview_scale=504:280
    if [[ "${ANIART_DEPTH_PREVIEW:-0}" == 1 ]]; then
        set -- "$@" --depth-preview "$raw"
        if [[ "$refine_sweep" == 1 || "$pipeline_sweep" == 1 ]]; then
            set -- "$@" --depth-preview-full
            preview_size=1280x720
            preview_scale=1280:720
        fi
    fi
    ANIART_PC_TOKEN="$token" setsid "$python_bin" -u "$script_dir/aniart_server.py"         --host 127.0.0.1 --port "$port" --checkpoint "$checkpoint" --depth-every "$interval" --ffmpeg-log         "$@" >"$log" 2>&1 &
    server_pid=$!
    for ((attempt=0; attempt<100; attempt++)); do
        if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
        status=$(curl -sS --max-time 1 -o /dev/null -w '%{http_code}' "$base/" 2>/dev/null)
        if [[ "$status" == 401 ]]; then ready=true; break; fi
        sleep .2
    done
    if [[ "$ready" == false ]]; then
        printf 'Сервер не запустился. См. %s.server.log\n' "$name" | tee -a "$reports/summary.txt"
        stop_server
        return
    fi
    response="$reports/$name.post.json"
    status=$(curl -sS --max-time 10 -o "$response" -w '%{http_code}'         -H "Authorization: Bearer $token" -H 'Content-Type: application/json'         --data-binary "$payload" "$base/v1/streams" 2>>"$log")
    if [[ "$status" != 201 ]]; then
        printf 'POST: HTTP %s\n' "$status" | tee -a "$reports/summary.txt"
        stop_server
        return
    fi
    path=$("$python_bin" -c 'import json, sys; print(json.load(sys.stdin)["path"])' <"$response")
    if [[ ! "$path" =~ ^/v1/streams/[a-zA-Z0-9_-]+\.ts$ ]]; then
        printf 'Сервер вернул неверный путь.\n' | tee -a "$reports/summary.txt"
        stop_server
        return
    fi
    stream_code=0
    sample_gpu "$reports/$name.gpu.csv" &
    gpu_pid=$!
    curl -sS --max-time 120 -H "Authorization: Bearer $token"         -o "$run_dir/video/$name.ts"         -w 'GET HTTP %{http_code} | first byte %{time_starttransfer} s | total %{time_total} s | %{size_download} bytes\n'         "$base$path" >"$reports/$name.curl.txt" 2>&1 || stream_code=$?
    stop_gpu_monitor
    printf 'curl exit: %s\n' "$stream_code" >>"$reports/$name.curl.txt"
    cat "$reports/$name.curl.txt" | tee -a "$reports/summary.txt"
    ffprobe -v error -count_frames -show_entries stream=codec_name,width,height,avg_frame_rate,nb_read_frames         -of compact "$run_dir/video/$name.ts" >"$reports/$name.ffprobe.txt" 2>&1
    stop_server
    grep -E 'First output in|Processed [0-9]+ frames|Depth timing|Stereo warp timing' "$log" | tee -a "$reports/summary.txt"
    if [[ -s "$raw" ]]; then
        if ffmpeg -nostdin -hide_banner -loglevel error -i "$input_file" \
            -f rawvideo -pixel_format gray -video_size "$preview_size" -framerate 24 -i "$raw" \
            -filter_complex_threads 2 \
            -filter_complex "[0:v]fps=24,scale=$preview_scale,setsar=1,format=yuv420p[video];[1:v]setsar=1,format=yuv420p[depth];[video][depth]hstack=shortest=1" \
            -an -c:v libx264 -preset fast -crf 19 -movflags +faststart "$run_dir/video/$name-depth.mp4" \
            >"$reports/$name.preview.log" 2>&1; then
            rm -- "$raw"
            printf 'Depth preview: video/%s-depth.mp4\n' "$name" | tee -a "$reports/summary.txt"
        fi
    fi
}

if [[ "$refine_sweep" == 1 || "$pipeline_sweep" == 1 ]]; then
    depth_device=${ANIART_BENCH_DEPTH_DEVICE:-webgpu}
    if [[ "$refine_sweep" == 1 ]]; then
        run_profile depth-720p-linear 1 --encode-device "$device" --device "$depth_device"
    fi
    run_profile depth-720p-refined 1 --encode-device "$device" --device "$depth_device" --depth-refine
    if [[ "$pipeline_sweep" == 1 ]]; then
        run_profile depth-720p-pipelined 1 --encode-device "$device" --device "$depth_device" \
            --depth-refine --depth-pipeline
    fi
    if [[ "${ANIART_WEBGPU_CAPTURE_SWEEP:-0}" == 1 ]]; then
        run_profile depth-720p-graph-capture 1 --encode-device "$device" --device webgpu \
            --depth-refine --webgpu-graph-capture
    fi
    exit 0
fi

if [[ "${ANIART_DEPTH_SWEEP:-0}" == 1 ]]; then
    for interval in 6 3 2 1; do
        run_profile "depth-every-$interval" "$interval" --encode-device "$device"             --device "${ANIART_BENCH_DEPTH_DEVICE:-cpu}"
    done
    exit 0
fi

run_profile 01-baseline 6 --no-anime4k
run_profile 02-vaapi 6 --no-anime4k --encode-device "$device"
run_profile 03-anime4k 6
run_profile 04-anime4k-vaapi 6 --encode-device "$device"

run_vulkan_probe() {
    local name=$1 filter=$2
    shift 2
    (
        cd "$repo_dir" || exit 1
        timeout 30 ffmpeg -hide_banner -loglevel debug "$@"             -f lavfi -i 'testsrc2=size=1280x720:rate=24'             -vf "$filter" -frames:v 1 -f null -
    ) >"$reports/$name.log" 2>&1
    printf '%s exit: %s\n' "$name" "$?" | tee -a "$reports/summary.txt"
}

filter=libplacebo=w=1600:h=900:format=yuv420p
run_vulkan_probe 05-vulkan-plain "$filter"
run_vulkan_probe 06-vulkan-shader "$filter:custom_shader_path=Anime4K_Upscale_CNN_x2_S.glsl"
run_vulkan_probe 07-vulkan-explicit "$filter:custom_shader_path=Anime4K_Upscale_CNN_x2_S.glsl"     -init_hw_device vulkan=shader:0 -filter_hw_device shader

printf '\nВидео и логи сохранены в %s\n' "$run_dir"
