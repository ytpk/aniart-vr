"""Generate an original 7.1 channel identification MP4 with Pillow and FFmpeg."""
from pathlib import Path
import subprocess

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parents[1] / "overlay/src/main/assets/room-demo.mp4"
W, H = 640, 360
SPEAKERS = [
    ("FRONT LEFT", 138, 89, 385),
    ("FRONT RIGHT", 502, 89, 430),
    ("CENTER", 320, 72, 500),
    ("LFE", 320, 115, 55),
    ("REAR LEFT", 144, 236, 335),
    ("REAR RIGHT", 496, 236, 360),
    ("SIDE LEFT", 122, 165, 465),
    ("SIDE RIGHT", 518, 165, 545),
]


def draw(active):
    image = Image.new("RGB", (W, H), (16, 13, 29))
    g = ImageDraw.Draw(image)
    g.rounded_rectangle((65, 43, 575, 265), radius=20, fill=(26, 20, 40), outline=(131, 47, 74), width=2)
    g.rounded_rectangle((224, 42, 416, 52), radius=3, fill=(81, 64, 116))
    g.rounded_rectangle((254, 160, 386, 208), radius=15, fill=(53, 47, 80))
    font = ImageFont.load_default(size=30)
    for i, (_, x, y, _) in enumerate(SPEAKERS):
        color = (234, 79, 108) if i == active else (105, 82, 131)
        g.ellipse((x-12, y-12, x+12, y+12), fill=color)
    g.text((W/2, 310), SPEAKERS[active][0], font=font, anchor="mm", fill=(236, 222, 247))
    return image.tobytes()


channels = []
for i, (_, _, _, frequency) in enumerate(SPEAKERS):
    t = f"(t-{i}-0.16)"
    gain = .25 if frequency == 55 else .15
    channels.append(f"if(between({t},0,0.65),{gain}*sin(2*PI*{frequency}*t)*sin(PI*{t}/0.65)^2,0)")
audio = "aevalsrc=exprs='" + "|".join(channels) + "':s=48000:d=8:c=7.1"
command = [
    "ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", "1",
    "-i", "-", "-f", "lavfi", "-i", audio, "-c:v", "libx264", "-crf", "20",
    "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "288k", "-movflags", "+faststart", str(OUT),
]
with subprocess.Popen(command, stdin=subprocess.PIPE) as encoder:
    for i in range(len(SPEAKERS)):
        encoder.stdin.write(draw(i))
    encoder.stdin.close()
    if encoder.wait():
        raise SystemExit("FFmpeg failed to encode the 7.1 demo")
