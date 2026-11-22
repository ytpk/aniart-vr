"""In-process adapter from anicli-api extractors to the Quest catalog."""

from functools import lru_cache
from importlib import import_module
from collections import defaultdict
from decimal import Decimal, InvalidOperation
import re
import warnings
from urllib.parse import urljoin, urlsplit

from anicli_api._http import HTTPSync
from anicli_api._logger import logger
import httpx


PROVIDERS = (
    ("animego", "AnimeGO"),
    ("anilibme", "AnimeLib"),
    ("anilibria", "AniLiberty"),
    ("animevost", "AnimeVost"),
    ("dreamcast", "DreamCast"),
    ("hdrezka", "HDRezka"),
    ("sameband", "SameBand"),
    ("yummy_anime", "YummyAnime"),
)

MEDIA_TYPES = {
    "m3u8": "application/x-mpegURL",
    "mpd": "application/dash+xml",
    "mp4": "video/mp4",
    "webm": "video/webm",
}
HEADER_NAME = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+")

# Upstream diagnostic messages can contain expiring player URLs.
logger.disabled = True
warnings.filterwarnings("ignore", module=r"anicli_api(\.|$)")


def providers():
    return PROVIDERS


def _https(value):
    try:
        url = urlsplit(value)
        if url.scheme == "https" and url.hostname and url.username is None:
            return value
    except ValueError:
        pass
    return ""


def _media_url(value):
    # Some providers publish legacy HTTP URLs for CDNs that also serve TLS.
    if value.startswith("http://"):
        value = "https://" + value[len("http://"):]
    elif value.startswith("//"):
        value = "https:" + value
    return _https(value)


class CatalogError(Exception):
    pass


def _run(call, *args):
    try:
        return call(*args)
    except httpx.HTTPStatusError as error:
        raise CatalogError(f"HTTP {error.response.status_code}") from None
    except httpx.TimeoutException:
        raise CatalogError("Сервис не ответил вовремя") from None
    except httpx.RequestError:
        raise CatalogError("Сервис недоступен из этой сети") from None
    except Exception:
        raise CatalogError("Не удалось прочитать ответ сервиса") from None


@lru_cache(maxsize=len(PROVIDERS))
def _extractor(provider):
    if provider not in dict(PROVIDERS):
        raise ValueError("Unknown provider")
    module = import_module(f"anicli_api.source.{provider}")
    return module.Extractor(http_client=HTTPSync(timeout=10.0))


def titles(provider, query):
    extractor = _run(_extractor, provider)
    entries = _run(extractor.search, query) if query else _run(extractor.ongoing)
    return [(item.title, _media_url(urljoin(extractor.BASE_URL, item.thumbnail))
             if item.thumbnail else "", item.url or item.title, item) for item in entries]


def episodes(title):
    anime = _run(title.get_anime)
    if anime is None:
        raise CatalogError("Источник не вернул данные тайтла")
    from anicli_api.source import hdrezka, yummy_anime

    entries = _run(_yummy_episodes, anime) if isinstance(anime, yummy_anime.Anime) else _run(anime.get_episodes)
    result = []
    for item in entries:
        if isinstance(item, hdrezka.Episode):
            number = ("Фильм" if item.is_movie else
                      f"Сезон {item.data_episode['data_season_id']} · Серия {item.data_episode['data_episode_id']}")
        else:
            number = f"Серия {item.num}"
        result.append((number, item.title or "", item))
    return result


def _yummy_episodes(anime):
    from anicli_api.source.yummy_anime import Episode, YummyAnimeApi

    # Upstream 0.12.0 casts every ordinal to int. A single special like 1168.5
    # would prevent the whole release from loading. Keep upstream's voice objects.
    response = YummyAnimeApi.anime_videos(anime.http, id=anime.data["anime_id"])
    if not response.is_ok:
        return []
    groups = defaultdict(list)
    for video in response.value["response"]:
        if "alloha" not in video["iframe_url"]:
            try:
                number = Decimal(str(video["number"]))
            except InvalidOperation:
                continue
            if number.is_finite():
                groups[number].append(video)
    return [Episode(title="", ordinal=number.normalize().to_eng_string() if number % 1 else int(number),
                    data=groups[number], http=anime.http, http_async=anime.http_async)
            for number in sorted(groups)]


def voices(episode):
    entries = _run(episode.get_sources)
    return [(item.title or "Озвучка", urlsplit(item.url).hostname or "", item)
            for item in entries]


def streams(source):
    entries = _run(source.get_videos)
    result = []
    seen = set()
    for video in entries:
        url = _media_url(video.url)
        mime = MEDIA_TYPES.get(video.type)
        if not url or not mime:
            continue
        headers = {
            key: value for key, value in video.headers.items()
            if isinstance(key, str) and isinstance(value, str) and HEADER_NAME.fullmatch(key)
            and not any(char in value for char in "\r\n\x00")
        }
        key = (video.quality, mime, url, tuple(sorted(headers.items())))
        if key not in seen:
            seen.add(key)
            result.append((int(video.quality), mime, url, headers))
    return sorted(result, key=lambda stream: (stream[0] != 720, -stream[0]))
