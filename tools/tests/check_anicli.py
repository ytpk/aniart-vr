import asyncio
from contextlib import redirect_stdout
from io import StringIO
import pkgutil
import ssl
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import anicli_api.source
from anicli_api.player.base import Video
import anicli_bridge as bridge
import httpx


class AniCliCheck(unittest.TestCase):
    def test_every_upstream_catalog_is_available(self):
        expected = {module.name for module in pkgutil.iter_modules(anicli_api.source.__path__)
                    if not module.ispkg and not module.name.startswith('_')}
        providers = {key for key, _ in bridge.providers()}
        self.assertEqual(providers, expected)
        for key in providers:
            with self.subTest(provider=key):
                self.assertTrue(callable(bridge._extractor(key).search))
                self.assertTrue(callable(bridge._extractor(key).ongoing))

    def test_android_clients_load_verified_tls_without_opening_a_ca_path(self):
        with patch.object(bridge, '_on_android', True), \
                patch.object(bridge.certifi, 'where', side_effect=OSError('CA path unavailable')):
            extractor = bridge._extractor.__wrapped__('anilibria')
            self.addCleanup(extractor.http.close)
            self.addCleanup(asyncio.run, extractor.http_async.aclose())
            context = bridge._android_tls_context()
        self.assertTrue(context.check_hostname)
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
        self.assertGreater(context.cert_store_stats()['x509_ca'], 0)

    def test_catalog_keeps_all_voices_and_video_formats(self):
        hls = Video('m3u8', 720, 'https://stream.example/watch', {'Referer': 'https://player.example/'})
        dash = Video('mpd', 1080, '//stream.example/manifest')
        mp4 = Video('mp4', 480, 'http://stream.example/episode.mp4')
        webm = Video('webm', 720, 'https://stream.example/episode.webm')
        source = SimpleNamespace(title='Озвучка A', url='https://player.example/',
                                 get_videos=lambda: [mp4, dash, hls, webm, hls])
        mirror = SimpleNamespace(title='Озвучка A', url='https://alternate.example/')
        subtitles = SimpleNamespace(title='Субтитры B', url='https://player.example/subs')
        episode = SimpleNamespace(num='1', title='Начало', get_sources=lambda: [source, mirror, subtitles])
        release = SimpleNamespace(title='Тайтл', url='/release/123', thumbnail='/poster.jpg',
                                  get_anime=lambda: SimpleNamespace(get_episodes=lambda: [episode]))
        extractor = SimpleNamespace(BASE_URL='https://catalog.example/', search=lambda _: [release])
        with patch.object(bridge, '_extractor', return_value=extractor):
            title, poster, key, item = bridge.titles('animego', 'Тайтл')[0]
        self.assertEqual((title, poster, key), ('Тайтл', 'https://catalog.example/poster.jpg', '/release/123'))
        self.assertEqual(bridge.episodes(item), [('Серия 1', 'Начало', episode)])
        self.assertEqual(bridge.voices(episode), [('Озвучка A', 'player.example', source),
                                                  ('Озвучка A', 'alternate.example', mirror),
                                                  ('Субтитры B', 'player.example', subtitles)])
        self.assertEqual(bridge.streams(source), [
            (720, 'application/x-mpegURL', hls.url, hls.headers),
            (720, 'video/webm', webm.url, {}),
            (1080, 'application/dash+xml', 'https://stream.example/manifest', {}),
            (480, 'video/mp4', 'https://stream.example/episode.mp4', {}),
        ])

    def test_errors_do_not_expose_media_urls(self):
        request = httpx.Request('GET', 'https://player.example/watch?signature=private')
        response = httpx.Response(403, request=request)
        with self.assertRaisesRegex(bridge.CatalogError, '^HTTP 403$'):
            bridge._run(response.raise_for_status)

    def test_failed_connection_retries_once_and_logs_only_host(self):
        output = StringIO()
        request = httpx.Request('GET', 'https://player.example/watch?signature=private')
        error = httpx.ConnectTimeout('timeout')
        with bridge.CatalogTransport() as transport, redirect_stdout(output), \
                patch('anicli_api._http.sleep'), \
                patch.object(httpx.HTTPTransport, 'handle_request', side_effect=error) as send:
            with self.assertRaises(httpx.ConnectTimeout):
                transport.handle_request(request)
        self.assertEqual(send.call_count, 2)
        self.assertEqual(output.getvalue().strip(), 'AniCli HTTP player.example failed ConnectTimeout')

    def test_empty_upstream_result_keeps_http_failure(self):
        response = httpx.Response(403)
        request = httpx.Request('GET', 'https://player.example/catalog')
        with bridge.CatalogTransport() as transport, redirect_stdout(StringIO()), \
                patch.object(httpx.HTTPTransport, 'handle_request', return_value=response):
            def upstream():
                transport.handle_request(request)
                return []

            with self.assertRaisesRegex(bridge.CatalogError, '^HTTP 403$'):
                bridge._run(upstream)
        self.assertEqual(bridge._run(list), [])

    def test_yummy_preserves_fractional_episodes(self):
        from anicli_api.source.yummy_anime import Anime, YummyAnimeApi

        def row(number, name):
            return {'number': number, 'iframe_url': 'https://player.example/', 'data': {'dubbing': name}}

        anime = Anime(title='Тайтл', thumbnail='', description='', data={'anime_id': 1})
        response = SimpleNamespace(is_ok=True, value={'response': [row('1168.5', 'Озвучка A'),
                                                                     row('1168', 'Озвучка A'),
                                                                     row('1168.50', 'Озвучка B')]})
        with patch.object(YummyAnimeApi, 'anime_videos', return_value=response):
            entries = bridge.episodes(SimpleNamespace(get_anime=lambda: anime))
        self.assertEqual([number for number, _, _ in entries], ['Серия 1168', 'Серия 1168.5'])
        self.assertEqual([name for name, _, _ in bridge.voices(entries[1][2])], ['Озвучка A', 'Озвучка B'])


if __name__ == '__main__':
    unittest.main()
