"""Build the offline side-by-side calibration clip with Pillow and FFmpeg."""
from math import sin, tau
from pathlib import Path
from random import Random
import subprocess

from PIL import Image, ImageDraw

WIDTH, HEIGHT, FPS, SECONDS = 640, 360, 24, 12
OUTPUT = Path(__file__).resolve().parents[1] / "overlay/src/main/assets/stereo-demo.mp4"

random = Random(14)
stars = [(random.randrange(-8, 650), random.randrange(15, 193), random.randrange(1, 4)) for _ in range(95)]


def render(eye, phase):
    image = Image.new("RGB", (WIDTH, HEIGHT))
    draw = ImageDraw.Draw(image)
    for y in range(HEIGHT):
        blend = min(1, y / 242)
        tone = tuple(round(a + (b - a) * blend) for a, b in zip((18, 16, 49), (150, 63, 123)))
        draw.line((0, y, WIDTH, y), tone)
    for x, y, size in stars:
        x -= eye * 3
        draw.ellipse((x, y, x + size, y + size), fill=(217, 203, 249))

    moon = 446 - eye * 2
    draw.ellipse((moon, 47, moon + 88, 135), fill=(255, 214, 178))
    draw.ellipse((moon + 29, 34, moon + 107, 113), fill=(34, 22, 65))
    far = [(x - eye * 2, y) for x, y in
           [(-12, 246), (76, 181), (126, 219), (226, 137), (346, 229), (441, 157), (655, 247)]]
    draw.polygon(far + [(660, HEIGHT), (-20, HEIGHT)], fill=(78, 55, 112))
    draw.line(far, fill=(204, 157, 212), width=2)
    near = [(x + eye * 3, y) for x, y in
            [(-20, 285), (63, 236), (102, 242), (188, 216), (272, 283), (425, 218), (565, 281), (660, 248)]]
    draw.polygon(near + [(667, HEIGHT), (-20, HEIGHT)], fill=(30, 29, 70))
    draw.line(near, fill=(140, 93, 162), width=2)

    x = round(306 + 130 * sin(phase)) + eye * 9
    y = round(195 + 25 * sin(phase * 2))
    craft = [(x - 55, y + 16), (x + 29, y - 21), (x + 65, y - 12),
             (x + 47, y + 8), (x + 16, y + 17), (x - 33, y + 20)]
    draw.polygon(craft, fill=(62, 56, 118), outline=(17, 11, 43), width=3)
    draw.polygon([(x - 38, y + 12), (x + 29, y - 19), (x + 61, y - 10), (x + 12, y + 6)],
                 fill=(235, 161, 225), outline=(252, 230, 252), width=2)
    draw.polygon([(x + 10, y - 10), (x + 27, y - 22), (x + 47, y - 21), (x + 50, y - 14)],
                 fill=(105, 220, 245), outline=(27, 45, 81), width=2)
    for i in range(4):
        draw.line((x - 124 + 9 * i, y + 23 - i * 4, x - 49, y + 17 - i * 3),
                  fill=(255, 189 - i * 13, 136 + i * 12), width=2)

    for branch in (-10, 580):
        bx = branch + eye * 13
        draw.polygon([(bx, 360), (bx + 30, 274), (bx + 63, 257), (bx + 49, 300),
                      (bx + 84, 297), (bx + 53, 326), (bx + 48, 360)], fill=(17, 17, 41))
    return image


def main():
    command = [
        "ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s", f"{WIDTH * 2}x{HEIGHT}", "-r", str(FPS), "-i", "-", "-an",
        "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(OUTPUT),
    ]
    with subprocess.Popen(command, stdin=subprocess.PIPE) as encoder:
        for index in range(FPS * SECONDS):
            phase = index / (FPS * SECONDS) * tau
            frame = Image.new("RGB", (WIDTH * 2, HEIGHT))
            frame.paste(render(1, phase), (0, 0))
            frame.paste(render(-1, phase), (WIDTH, 0))
            encoder.stdin.write(frame.tobytes())
        encoder.stdin.close()
        if encoder.wait():
            raise SystemExit("FFmpeg failed to encode the demo")


if __name__ == "__main__":
    main()
