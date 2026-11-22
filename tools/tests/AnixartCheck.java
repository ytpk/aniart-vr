package dev.aniart.vr;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AnixartCheck {
    private static final String EMBED = "https://kodikplayer.com/seria/11/abcdefgh/720p";
    private static final String SIGNED = EMBED + "?d=fixture&s=f%2Bfixture&ip=";
    private static final String HLS_720 = "https://cloud2.solodcdn.com/test/720.mp4:hls:manifest.m3u8?key=f%2Bx";
    private static final String HLS_480 = "https://cloud2.solodcdn.com/test/480.mp4:hls:manifest.m3u8";
    private static final String PAGE = """
        <script>var urlParams = '{"d":"fixture.example","d_sign":"d-fixture",
        "pd":"kodikplayer.com","pd_sign":"pd-fixture","ref":"https%3A%2F%2Ffixture.example%2F",
        "ref_sign":"ref-fixture"}';</script>
        """;

    private static final class Fixture implements AnixartCatalog.Transport {
        final List<String> paths = new ArrayList<>();
        boolean denied;
        boolean with720 = true;
        int providerStatus = 200;
        int embedRequests, streamRequests;

        @Override public JSONObject request(String path, JSONObject payload) {
            paths.add(path);
            switch (path) {
                case "discover/watching/0" -> {
                    check(payload == null, "browse is GET");
                    return new JSONObject("""
                            {"code":0,"content":[{"id":42,"title_ru":"Тайтл","year":"2026","episodes_released":2}]}
                            """);
                }
                case "search/releases/0" -> {
                    check(payload.getString("query").equals("Тайтл"), "search query");
                    return new JSONObject("""
                            {"code":0,"releases":{"content":[{"id":42,"title_ru":null,"title_original":"Title",
                            "episodes_released":2},{"id":99,"is_deleted":true,"title_ru":"Deleted"}]}}
                            """);
                }
                case "episode/42" -> { return new JSONObject("{\"code\":0,\"types\":[{\"id\":5,\"name\":\"Озвучка\"}]}"); }
                case "episode/42/5" -> { return new JSONObject("{\"code\":0,\"sources\":[{\"id\":8,\"name\":\"Kodik\"}]}"); }
                case "episode/42/5/8" -> {
                    return new JSONObject().put("code", 0).put("episodes", new JSONArray()
                            .put(new JSONObject().put("position", 1).put("url", EMBED))
                            .put(new JSONObject().put("position", 2).put("url", HLS_720).put("iframe", true)));
                }
                case "episode/target/42/8/1" -> {
                    return denied ? new JSONObject().put("code", 8)
                            : new JSONObject().put("code", 0).put("episode", new JSONObject().put("url", SIGNED));
                }
                default -> throw new AssertionError("Unexpected request " + path);
            }
        }

        KodikStreams.Response provider(java.net.URI url, java.util.Map<String, String> headers, String body) {
            if (body == null) {
                embedRequests++;
                check(url.toASCIIString().equals(SIGNED), "signed Anixart target preserved");
                return new KodikStreams.Response(providerStatus, PAGE);
            }
            streamRequests++;
            check(url.toASCIIString().equals("https://kodikplayer.com/ftor"), "same-origin stream request");
            check(headers.get("Referer").equals(SIGNED), "same signed referer");
            check(body.contains("ref=https%3A%2F%2Ffixture.example%2F") && body.contains("id=11"), "forwarded parameters");
            JSONObject streams = new JSONObject()
                    .put("480", variants(HLS_480))
                    .put("1080", variants("https://cloud2.solodcdn.com/test/1080.mp4:hls:manifest.m3u8"))
                    .put("360", variants("https://cloud2.solodcdn.com.example.org/test/360.m3u8"));
            if (with720) streams.put("720", variants(encoded(HLS_720)));
            return new KodikStreams.Response(200, new JSONObject().put("links", streams).toString());
        }
    }

    public static void main(String[] args) throws Exception {
        scripted();
        if (args.length == 1 && args[0].equals("--live")) live();
    }

    private static void scripted() throws Exception {
        var fixture = new Fixture();
        var api = new AnixartCatalog(fixture);
        var playback = new AnixartPlayback(api, url -> KodikStreams.resolve(url, fixture::provider));
        check(api.titles("", 0).size() == 1, "browse titles");
        var results = api.titles("Тайтл", 0);
        check(results.size() == 1 && results.get(0).name().equals("Title"), "search filters and title fallback");
        var title = results.get(0);
        var voice = api.voices(title).get(0);
        var source = api.sources(title, voice).get(0);
        var episodes = api.episodes(title, voice, source);
        check(episodes.get(0).name().equals("Серия 1"), "episode title fallback");

        var direct = playback.resolve(title, source, episodes.get(1));
        check(direct.link().hls() && !direct.link().embedded() && direct.link().url().equals(HLS_720),
                "explicit Anixart HLS is passed to Media3");
        check(fixture.embedRequests == 0 && !fixture.paths.contains("episode/target/42/8/2"), "direct URL requires no resolver");

        var embedded = playback.resolve(title, source, episodes.get(0));
        check(embedded.status().isEmpty(), "provider result");
        check(embedded.streams().stream().map(KodikStreams.Stream::height).toList().equals(List.of(720, 480, 1080)),
                "select 720p first, retain only valid offered qualities");
        var preferred = embedded.streams().get(0);
        check(preferred.url().equals(HLS_720) && preferred.hls() && preferred.headers().containsKey("Referer"),
                "pass HLS with unchanged query and Referer");
        var handoff = IncomingVideo.parse(preferred.url(), new String[]{"Referer: https://kodikplayer.com/"});
        check(handoff != null && handoff.hls() && handoff.quality().equals("720p") && handoff.url().equals(HLS_720),
                "direct-link handoff agrees with catalogue route");

        fixture.with720 = false;
        check(playback.resolve(title, source, episodes.get(0)).streams().get(0).height() == 480,
                "fall back to offered 480p");
        fixture.providerStatus = 500;
        int previousPosts = fixture.streamRequests;
        var failed = playback.resolve(title, source, episodes.get(0));
        check(failed.link().url().equals(SIGNED) && failed.streams().isEmpty() && failed.status().endsWith("HTTP 500"),
                "provider failure retains browser link and diagnostic");
        check(fixture.streamRequests == previousPosts, "no further request after failed embed");
        int previousEmbeds = fixture.embedRequests;
        fixture.denied = true;
        expectFailure(() -> playback.resolve(title, source, episodes.get(0)), "Anixart вернул ошибку");
        check(fixture.embedRequests == previousEmbeds, "catalogue denial stops provider request");
        var blocked = new AnixartCatalog.Title(42, "Blocked", "", 0, "", true);
        int previousRequests = fixture.paths.size();
        expectFailure(() -> playback.resolve(blocked, source, episodes.get(0)), "ограничил");
        check(fixture.paths.size() == previousRequests, "blocked title makes no network request");
        System.out.println("PASS: Anixart search → voice → source → episodes → direct HLS or Kodik, quality order, handoff and denial handling");
    }

    private static void live() throws Exception {
        var api = new AnixartCatalog();
        var popular = api.titles("", 0);
        var search = api.titles("Наруто", 0);
        check(!popular.isEmpty() && !search.isEmpty(), "live browse and search");
        System.out.printf("PASS: live Anixart catalogue/search (%d/%d titles)%n", popular.size(), search.size());
        var playback = new AnixartPlayback(api, new KodikStreams()::resolve);
        var sample = new AnixartCatalog.Title(1, "Guest smoke test", "", 0, "", false);
        boolean playable = false;
        for (var title : List.of(search.get(0), sample)) {
            if (title.blocked()) continue;
            var voices = api.voices(title);
            if (voices.isEmpty()) {
                System.out.println("BLOCKED: no guest voices for release " + title.id());
                continue;
            }
            var voice = voices.get(0);
            var source = api.sources(title, voice).stream().filter(s -> s.name().equalsIgnoreCase("Kodik"))
                    .findFirst().orElse(null);
            if (source == null) {
                System.out.println("BLOCKED: no Kodik source for release " + title.id());
                continue;
            }
            var episodes = api.episodes(title, voice, source);
            if (episodes.isEmpty()) {
                System.out.println("BLOCKED: no guest episodes for release " + title.id());
                continue;
            }
            var result = playback.resolve(title, source, episodes.get(0));
            System.out.println("PASS: live voice → source → episodes → target for release " + title.id());
            if (!result.link().embedded()) {
                playable |= verifyMedia(result.link().url(), result.link().hls(), KodikStreams.headersForCdn(result.link().url()));
            } else if (!result.streams().isEmpty()) {
                var stream = result.streams().get(0);
                playable |= verifyMedia(stream.url(), stream.hls(), stream.headers());
            } else {
                System.out.println("BLOCKED: live Kodik media resolution: " + result.status());
            }
        }
        if (!playable) System.exit(2);
    }

    private static boolean verifyMedia(String url, boolean hls, Map<String, String> headers) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            headers.forEach(connection::setRequestProperty);
            int status = connection.getResponseCode();
            if (status != 200 && status != 206) {
                System.out.println("BLOCKED: media CDN HTTP " + status);
                return false;
            }
            try (var input = connection.getInputStream()) {
                String prefix = new String(input.readNBytes(12), StandardCharsets.UTF_8);
                boolean expected = hls ? prefix.startsWith("#EXTM3U") || prefix.startsWith("\uFEFF#EXTM3U")
                        : prefix.length() >= 8 && prefix.substring(4, 8).equals("ftyp");
                System.out.println(expected ? "PASS: live media CDN returned " + (hls ? "HLS manifest" : "MP4")
                        : "BLOCKED: response does not resemble the advertised video format");
                return expected;
            }
        } catch (IOException error) {
            System.out.println("BLOCKED: media CDN request failed (" + error.getClass().getSimpleName() + ")");
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONArray variants(String source) {
        return new JSONArray().put(new JSONObject().put("src", source));
    }

    private static String encoded(String url) {
        String encoded = Base64.getEncoder().encodeToString(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder rotated = new StringBuilder();
        for (char c : encoded.toCharArray()) {
            if (c >= 'a' && c <= 'z') c = (char) ('a' + (c - 'a' + 8) % 26);
            if (c >= 'A' && c <= 'Z') c = (char) ('A' + (c - 'A' + 8) % 26);
            rotated.append(c);
        }
        return rotated.toString();
    }

    private interface Step { void run() throws Exception; }

    private static void expectFailure(Step step, String fragment) throws Exception {
        try { step.run(); } catch (IOException error) {
            check(error.getMessage().contains(fragment), "expected diagnostic");
            return;
        }
        throw new AssertionError("Expected failure");
    }

    private static void check(boolean condition, String context) {
        if (!condition) throw new AssertionError(context);
    }
}
