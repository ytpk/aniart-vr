#!/usr/bin/env python3
"""Single-viewer Aniart VR LAN transcoder for finite HTTP(S) video sources."""

import argparse
import hmac
import json
import logging
import os
from pathlib import Path
import re
import secrets
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

import numpy as np

from depth import Limbo, Stereo


def source_request(body):
    url = body["url"]
    parsed = urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or any(ord(c) < 33 for c in url):
        raise ValueError("Expected an HTTP(S) video URL")
    headers = body.get("headers", {})
    if not isinstance(headers, dict) or len(headers) > 24:
        raise ValueError("Invalid HTTP headers")
    lines = []
    for name, value in headers.items():
        if not re.fullmatch(r"[a-zA-Z0-9-]+", name) or not isinstance(value, str) or "\r" in value or "\n" in value:
            raise ValueError("Invalid HTTP header")
        lines.append(f"{name}: {value}\r\n")
    input_options = ["-rw_timeout", "18000000", "-protocol_whitelist", "http,https,tcp,tls,crypto"]
    if lines:
        input_options += ["-headers", "".join(lines)]
    return input_options + ["-i", url]


def commands(args, source):
    decoder = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-filter_threads", "2"]
    if args.anime4k:
        decoder += ["-init_hw_device", f"vulkan=shader:{args.vulkan_device}", "-filter_hw_device", "shader"]
    if args.decode_device:
        decoder += ["-hwaccel", "vaapi", "-hwaccel_device", args.decode_device, "-hwaccel_output_format", "vaapi"]
    size = f"{args.width}:{args.height}"
    filters = []
    if args.decode_device:
        filters += ["hwdownload", "format=nv12"]
    if args.anime4k:
        # Anime4K's hook runs only when both target dimensions exceed the input by 20%.
        # Keep it active on same-resolution requests, then resize to the advertised frame.
        filters += ["format=yuv420p", "hwupload",
                    f"libplacebo=w='max(iw*1.25,{args.width})':h='max(ih*1.25,{args.height})'"
                    ":format=yuv420p:custom_shader_path=Anime4K_Upscale_CNN_x2_S.glsl",
                    "hwdownload", "format=yuv420p", f"scale={size}"]
    else:
        filters += [f"scale={size}"]
    filters += [f"fps={args.fps}", "format=rgb24"]
    decoder += source + ["-map", "0:v:0", "-an", "-sn", "-vf", ",".join(filters), "-f", "rawvideo", "pipe:1"]

    encoder = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-filter_threads", "2"]
    if args.encode_device:
        encoder += ["-vaapi_device", args.encode_device]
    encoder += ["-f", "rawvideo", "-pixel_format", "rgb24", "-video_size",
                f"{args.width if args.half_sbs else args.width * 2}x{args.height}", "-framerate", str(args.fps), "-i", "pipe:0"]
    encoder += source + ["-map", "0:v", "-map", "1:a:0?", "-c:a", "aac", "-b:a", "160k"]
    sar = "setsar=2" if args.half_sbs else "setsar=1"
    if args.encode_device:
        encoder += ["-vf", sar + ",format=nv12,hwupload", "-c:v", "h264_vaapi"]
    else:
        encoder += ["-vf", sar, "-c:v", "libx264", "-threads", "4", "-preset", "veryfast", "-tune", "zerolatency", "-pix_fmt", "yuv420p"]
    encoder += ["-b:v", args.bitrate, "-g", str(args.fps), "-bf", "0", "-shortest", "-f", "mpegts", "pipe:1"]
    return decoder, encoder


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, args, model):
        super().__init__((args.host, args.port), Handler)
        self.args, self.model = args, model
        self.jobs = {}
        self.lock = threading.Lock()
        self.viewer = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    timeout = 15
    def log_message(self, fmt, *args):
        logging.info("%s %s", self.address_string(), fmt % args)

    def authorized(self):
        supplied = self.headers.get("Authorization", "").encode()
        if hmac.compare_digest(supplied, f"Bearer {self.server.args.token}".encode()):
            return True
        self.send_error(401)
        return False

    def do_POST(self):
        if not self.authorized():
            return
        if self.path != "/v1/streams":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            if not 0 < length <= 32768:
                raise ValueError()
            source = source_request(json.loads(self.rfile.read(length)))
        except (ValueError, KeyError, TypeError, AttributeError):
            self.send_error(400, "Invalid stream request")
            return
        now = time.monotonic()
        key = secrets.token_urlsafe(18)
        with self.server.lock:
            jobs = self.server.jobs
            for old in [k for k, (expiry, _) in jobs.items() if expiry < now]:
                del jobs[old]
            if len(jobs) >= 16:
                self.send_error(429)
                return
            jobs[key] = (now + 120, source)
        response = json.dumps({"path": f"/v1/streams/{key}.ts", "stereo": "sbs"}).encode()
        self.send_response(201)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def do_GET(self):
        if not self.authorized():
            return
        match = re.fullmatch(r"/v1/streams/([A-Za-z0-9_-]+)\.ts", self.path)
        if not match:
            self.send_error(404)
            return
        if not self.server.viewer.acquire(blocking=False):
            self.send_error(503, "Another stream is closing or active")
            return
        try:
            with self.server.lock:
                job = self.server.jobs.get(match[1])
            if not job or job[0] < time.monotonic():
                self.send_error(404)
                return
            self.stream(job[1])
        finally:
            self.server.viewer.release()

    def stream(self, source):
        args = self.server.args
        decoder_cmd, encoder_cmd = commands(args, source)
        decoder = subprocess.Popen(decoder_cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   cwd=Path(__file__).resolve().parents[1])
        encoder = None
        writer = None
        stopping = threading.Event()
        started = time.monotonic()

        def produce():
            try:
                stereo = Stereo(args.width, args.height, args.strength, args.half_sbs)
                sample = None
                frame_id = 0
                frame_bytes = args.width * args.height * 3
                while not stopping.is_set():
                    raw = decoder.stdout.read(frame_bytes)
                    if len(raw) != frame_bytes:
                        break
                    frame = np.frombuffer(raw, np.uint8).reshape(args.height, args.width, 3)
                    current = frame[::32, ::32].astype(np.float32)
                    cut = sample is None or np.abs(current - sample).mean() > 28
                    sample = current
                    if cut or frame_id % args.depth_every == 0:
                        prediction = self.server.model(frame, args.depth_width, args.depth_height)
                        stereo.update(prediction, cut)
                    encoder.stdin.write(stereo.render(frame).tobytes())
                    frame_id += 1
                    if frame_id % 120 == 0:
                        logging.info("Processed %d frames at %.1f fps", frame_id, frame_id / (time.monotonic() - started))
            except (BrokenPipeError, ValueError, RuntimeError, OSError) as error:
                logging.warning("Frame processing stopped (%s)", type(error).__name__)
            finally:
                encoder.stdin.close()

        try:
            encoder = subprocess.Popen(encoder_cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            writer = threading.Thread(target=produce)
            writer.start()
            chunk = encoder.stdout.read1(65536)
            if not chunk:
                self.send_error(502, "No output from FFmpeg; check video, Vulkan and VAAPI")
                return
            logging.info("First output in %.2f s", time.monotonic() - started)
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.connection.settimeout(10)
            while chunk:
                self.wfile.write(chunk)
                chunk = encoder.stdout.read1(65536)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        finally:
            stopping.set()
            for process in (decoder, encoder):
                if process is not None and process.poll() is None:
                    process.kill()
            if writer:
                writer.join()
            for process in (decoder, encoder):
                if process is not None:
                    process.wait()
                    for pipe in (process.stdin, process.stdout):
                        if pipe:
                            pipe.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cuda")
    parser.add_argument("--vulkan-device", type=int, default=0)
    parser.add_argument("--decode-device")
    parser.add_argument("--encode-device")
    parser.add_argument("--width", type=int, default=1280, help="Per-eye width")
    parser.add_argument("--height", type=int, default=720, help="Per-eye height")
    parser.add_argument("--half-sbs", action="store_true", help="Pack both eyes into the configured output width")
    parser.add_argument("--fps", type=int, default=24)
    parser.add_argument("--depth-width", type=int, default=504)
    parser.add_argument("--depth-height", type=int, default=280)
    parser.add_argument("--depth-every", type=int, default=2)
    parser.add_argument("--strength", type=float, default=.022)
    parser.add_argument("--bitrate", default="12M")
    parser.add_argument("--no-anime4k", action="store_false", dest="anime4k")
    args = parser.parse_args()
    args.token = os.environ.get("ANIART_PC_TOKEN", "")
    if len(args.token) < 16 or not re.fullmatch(r"[A-Za-z0-9_-]+", args.token):
        parser.error("Set ANIART_PC_TOKEN to at least 16 URL-safe characters")
    if (not 16 <= args.width <= 3840 or not 16 <= args.height <= 2160 or args.width % 2 or args.height % 2
            or (args.half_sbs and args.width % 4)
            or not 1 <= args.fps <= 60 or not 1 <= args.depth_every <= 12 or not 0 <= args.strength <= .1
            or any(d < 28 or d > 896 or d % 14 for d in (args.depth_width, args.depth_height))):
        parser.error("Invalid output size, FPS, depth size, interval or strength")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    model = Limbo(args.checkpoint, args.device)
    with Server(args, model) as server:
        logging.info("Listening on %s:%d", args.host, args.port)
        server.serve_forever()


if __name__ == "__main__":
    main()
