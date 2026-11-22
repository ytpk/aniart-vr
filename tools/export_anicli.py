#!/usr/bin/env python3
"""Export one fresh AniLiberty episode via anicli-api for Aniart VR."""
import argparse
import json
import sys
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("query", help="Название тайтла")
    parser.add_argument("--release", type=int, help="Номер тайтла в выдаче, начиная с 1")
    parser.add_argument("--episode", type=int, help="Номер серии, по умолчанию первая доступная")
    parser.add_argument("--quality", type=int, default=720, help="Требуемое разрешение")
    args = parser.parse_args()

    try:
        import httpx
        from anicli_api.source.anilibria import Extractor
    except ImportError:
        parser.exit(1, "Установите anicli-ru в этом Python-окружении.\n")

    with httpx.Client(timeout=12.0, headers={"User-Agent": "AniartVR/0.4"}) as http:
        try:
            results = Extractor(http_client=http).search(args.query)
            if not results:
                parser.exit(1, "AniLiberty не вернула тайтлы.\n")
            if args.release is None and len(results) > 1:
                for i, item in enumerate(results, 1):
                    print(f"{i}. {item.title}", file=sys.stderr)
                parser.exit(2, "Укажите номер тайтла через --release.\n")
            choice = args.release if args.release is not None else 1
            if not 1 <= choice <= len(results):
                parser.exit(2, "Номер тайтла вне выдачи.\n")
            title = results[choice - 1].get_anime()
            episodes = title.get_episodes()
            episode = next((item for item in episodes
                            if args.episode is None or item.ordinal == args.episode), None)
            if episode is None:
                parser.exit(1, "У тайтла нет серии с таким номером.\n")
            sources = episode.get_sources()
            options = [video for source in sources for video in source.get_videos()
                       if video.type in ("m3u8", "mp4") and urlsplit(video.url).scheme == "https"]
        except httpx.HTTPError as error:
            # Diagnostics stay free of transient media URLs.
            parser.exit(1, f"Запрос AniLiberty завершился ошибкой {type(error).__name__}.\n")

    selected = next((item for item in options if item.quality == args.quality and item.type == "m3u8"), None)
    if selected is None:
        selected = next((item for item in options if item.quality == args.quality), None)
    if selected is None:
        available = ", ".join(str(n) for n in sorted({item.quality for item in options}))
        parser.exit(1, f"Нет {args.quality}p. Доступно: {available or 'нет потоков'}.\n")
    json.dump([{
        "title": title.title,
        "episode": f"Серия {episode.ordinal}",
        "voice": "AniLiberty",
        "quality": f"{selected.quality}p",
        "url": selected.url,
        "type": "hls" if selected.type == "m3u8" else "mp4",
        "headers": dict(selected.headers),
    }], sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    main()
