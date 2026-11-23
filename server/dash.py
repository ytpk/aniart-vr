"""Read static DASH duration and prepare single-track manifests for FFmpeg."""

from copy import deepcopy
from dataclasses import dataclass
import re
from urllib.parse import urljoin
from urllib.request import HTTPRedirectHandler, Request, build_opener
from xml.etree import ElementTree as ET


MAX_MANIFEST_BYTES = 1024 * 1024


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


@dataclass(frozen=True)
class Dash:
    duration_ms: int = 0
    video: bytes | None = None
    audio: bytes | None = None


def duration_ms(value):
    match = re.fullmatch(r"P(?:(\d+)D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?", value)
    if not match:
        return 0
    days, hours, minutes, seconds = (float(part or 0) for part in match.groups())
    return round((days * 86400 + hours * 3600 + minutes * 60 + seconds) * 1000)


def media_type(adaptation, representation):
    return (representation.get("contentType") or
            representation.get("mimeType", "").split("/")[0] or
            adaptation.get("contentType") or adaptation.get("mimeType", "").split("/")[0])


def single_track(root, url, track):
    root = deepcopy(root)
    base = root.find("{*}BaseURL")
    if base is None:
        base = ET.Element(root.tag.rsplit("}", 1)[0] + "}BaseURL" if "}" in root.tag else "BaseURL")
        root.insert(0, base)
    base.text = urljoin(url, (base.text or "./").strip())
    period = root.find("{*}Period")
    selected = False
    for adaptation in period.findall("{*}AdaptationSet"):
        representations = adaptation.findall("{*}Representation")
        representation = next((rep for rep in representations if media_type(adaptation, rep) == track), None)
        if selected or representation is None:
            period.remove(adaptation)
        else:
            for rep in representations:
                if rep is not representation:
                    adaptation.remove(rep)
            selected = True
    return ET.tostring(root, encoding="utf-8", xml_declaration=True) if selected else None


def probe_dash(url, headers):
    request_headers = dict(headers)
    if not any(key.lower() == "user-agent" for key in request_headers):
        request_headers["User-Agent"] = "Mozilla/5.0"
    try:
        # The original FFmpeg path handles redirects and unfamiliar manifests.
        opener = build_opener(NoRedirect)
        with opener.open(Request(url, headers=request_headers), timeout=3) as response:
            payload = response.read(MAX_MANIFEST_BYTES + 1)
        if len(payload) > MAX_MANIFEST_BYTES or re.search(rb"<!\s*DOCTYPE", payload, re.IGNORECASE):
            return Dash()
        root = ET.fromstring(payload)
        if root.tag.rsplit("}", 1)[-1] != "MPD" or root.get("type", "static") != "static":
            return Dash()
        periods = root.findall("{*}Period")
        length = duration_ms(root.get("mediaPresentationDuration", ""))
        if not length and len(periods) == 1:
            length = duration_ms(periods[0].get("duration", ""))
    except (OSError, ValueError, OverflowError, ET.ParseError):
        return Dash()
    # A pipe has no HTTP context from which libavformat can copy segment
    # headers. Preserve the normal input for sources with explicit headers.
    if headers or not length or len(periods) != 1 or root.find(".//{*}ContentProtection") is not None:
        return Dash(length)
    video, audio = (single_track(root, url, track) for track in ("video", "audio"))
    return Dash(length, video, audio) if video and audio else Dash(length)
