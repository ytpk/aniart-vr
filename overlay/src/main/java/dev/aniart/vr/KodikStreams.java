package dev.aniart.vr;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.json.JSONObject;

final class KodikStreams {
    private static final Set<String> PLAYER_HOSTS = Set.of(
            "kodikplayer.com", "kodik.info", "kodik.biz", "kodik.cc", "aniqit.com");
    private static final Pattern EPISODE_PATH = Pattern.compile(
            "/(seria|serial|video|episode)/([0-9]+)/([a-zA-Z0-9_-]{8,128})(?:/[^/]*)?/?");
    private static final Pattern PAGE_PARAMS = Pattern.compile(
            "\\burlParams\\s*=\\s*(['\"])(\\{.*?\\})\\1", Pattern.DOTALL);
    private static final Pattern VIDEO_FIELD = Pattern.compile(
            "\\bvInfo\\.(type|hash|id)\\s*=\\s*['\"]([a-zA-Z0-9_-]+)['\"]");
    private static final String USER_AGENT = "AniartVR/0.3";
    private static final int MAX_DOCUMENT = 2 * 1024 * 1024;

    record Stream(String url, int height, boolean hls, Map<String, String> headers) {}
    record Response(int status, String body) {}

    @FunctionalInterface
    interface Transport {
        Response request(URI uri, Map<String, String> headers, String body) throws IOException;
    }

    static boolean isEmbed(String value) {
        URI uri = https(value);
        return uri != null && PLAYER_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                && EPISODE_PATH.matcher(uri.getPath()).matches();
    }

    static Map<String, String> headersForCdn(String value) {
        URI uri = https(value);
        return isCdn(uri) ? playbackHeaders("https://kodikplayer.com") : Map.of();
    }

    List<Stream> resolve(String url) throws Exception {
        CookieManager cookies = new CookieManager();
        return resolve(url, (uri, headers, body) -> request(uri, headers, body, cookies));
    }

    static List<Stream> resolve(String url, Transport transport) throws Exception {
        if (!isEmbed(url)) throw new IOException("Kodik: неподдерживаемый адрес плеера");
        URI page = URI.create(url);
        String origin = "https://" + page.getHost();
        Response html = transport.request(page, Map.of("Accept", "text/html", "Referer", "https://anixart-app.com/"), null);
        if (html.status() != 200) throw new IOException("Kodik: веб-плеер вернул HTTP " + html.status());

        var params = PAGE_PARAMS.matcher(html.body());
        if (!params.find()) throw new IOException("Kodik: плеер не выдал параметры видео");
        JSONObject json = new JSONObject(params.group(2));
        var path = EPISODE_PATH.matcher(page.getPath());
        if (!path.matches()) throw new IOException("Kodik: неверный адрес серии");
        Map<String, String> video = new HashMap<>();
        video.put("type", path.group(1));
        video.put("id", path.group(2));
        video.put("hash", path.group(3));
        var fields = VIDEO_FIELD.matcher(html.body());
        while (fields.find()) video.put(fields.group(1), fields.group(2));
        if (!Set.of("seria", "serial", "video", "episode").contains(video.get("type"))
                || !video.get("id").matches("[0-9]+")
                || !video.get("hash").matches("[a-zA-Z0-9_-]{8,128}")) {
            throw new IOException("Kodik: плеер вернул неверное описание серии");
        }

        StringJoiner form = new StringJoiner("&");
        for (String key : List.of("d", "d_sign", "pd", "pd_sign", "ref_sign")) {
            String value = json.optString(key);
            if (value.isEmpty()) throw new IOException("Kodik: плеер не выдал параметры видео");
            field(form, key, value);
        }
        field(form, "ref", URLDecoder.decode(json.optString("ref"), "UTF-8"));
        for (String key : List.of("type", "id", "hash")) field(form, key, video.get(key));

        Response answer = transport.request(URI.create(origin + "/ftor"), Map.of(
                "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept", "application/json", "Origin", origin,
                "Referer", page.toASCIIString(), "X-Requested-With", "XMLHttpRequest"
        ), form.toString());
        if (answer.status() != 200) throw new IOException("Kodik: запрос потоков вернул HTTP " + answer.status());
        JSONObject links = new JSONObject(answer.body()).optJSONObject("links");
        if (links == null) throw new IOException("Kodik не вернул потоки для этой серии");
        List<Stream> streams = new ArrayList<>();
        for (var keys = links.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!key.matches("[0-9]{3,4}")) continue;
            int height = Integer.parseInt(key);
            var variants = links.optJSONArray(key);
            if (variants == null) continue;
            for (int i = 0; i < variants.length(); i++) {
                JSONObject variant = variants.optJSONObject(i);
                if (variant == null) continue;
                URI media = decode(variant.optString("src"));
                if (media == null) continue;
                String mediaPath = media.getPath().toLowerCase(Locale.ROOT);
                boolean hls = mediaPath.endsWith(".m3u8");
                if (!hls && !mediaPath.endsWith(".mp4")) continue;
                streams.add(new Stream(media.toASCIIString(), height, hls, playbackHeaders(origin)));
                break;
            }
        }
        if (streams.isEmpty()) throw new IOException("Kodik не вернул прямой HLS или MP4");
        // Prefer 720p, then the nearest smaller offered resolution. Higher resolutions stay selectable.
        streams.sort(Comparator.comparingInt((Stream stream) -> stream.height() <= 720
                ? 720 - stream.height() : 10000 + stream.height()));
        return List.copyOf(streams);
    }

    private static void field(StringJoiner form, String key, String value) throws IOException {
        form.add(key + "=" + URLEncoder.encode(value, "UTF-8"));
    }

    private static Map<String, String> playbackHeaders(String origin) {
        return Map.of("Referer", origin + "/", "User-Agent", USER_AGENT);
    }

    private static URI decode(String source) {
        URI direct = https(source.startsWith("//") ? "https:" + source : source);
        if (isCdn(direct)) return direct;
        if (source.length() > 8192 || !source.matches("[a-zA-Z0-9+/=]+")) return null;
        // The player response can transport the URL as a shifted Base64 string.
        for (int shift = 0; shift < 26; shift++) {
            StringBuilder normalized = new StringBuilder(source.length());
            for (int i = 0; i < source.length(); i++) {
                char ch = source.charAt(i);
                if (ch >= 'a' && ch <= 'z') ch = (char) ('a' + (ch - 'a' + shift) % 26);
                else if (ch >= 'A' && ch <= 'Z') ch = (char) ('A' + (ch - 'A' + shift) % 26);
                normalized.append(ch);
            }
            try {
                String decoded = new String(Base64.getDecoder().decode(normalized.toString()), StandardCharsets.UTF_8);
                URI candidate = https(decoded.startsWith("//") ? "https:" + decoded : decoded);
                if (isCdn(candidate)) return candidate;
            } catch (IllegalArgumentException error) {
                // This encoding did not yield a URI.
            }
        }
        return null;
    }

    private static URI https(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getPort() == -1 ? uri : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static boolean isCdn(URI uri) {
        if (uri == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("solodcdn.com") || host.endsWith(".solodcdn.com")
                || host.equals("kodik-cdn.com") || host.endsWith(".kodik-cdn.com")
                || PLAYER_HOSTS.contains(host);
    }

    private static Response request(URI uri, Map<String, String> headers, String body, CookieManager cookies)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        headers.forEach(connection::setRequestProperty);
        cookies.get(uri, Map.of()).forEach((key, values) -> connection.setRequestProperty(key, String.join("; ", values)));
        try {
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status != 200) return new Response(status, "");
            cookies.put(uri, connection.getHeaderFields());
            try (var input = connection.getInputStream()) {
                return new Response(status,
                        new String(AnixartCatalog.read(input, MAX_DOCUMENT), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
