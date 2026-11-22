package dev.aniart.vr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class AnixartCatalog {
    private static final String HOST = "https://api-s.anixsekai.com/";
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;

    record Title(long id, String name, String year, int episodes, String poster, boolean blocked) {}
    record Option(long id, String name, int episodes) {}
    record Episode(long position, String name, String url) {}
    record Link(String url, boolean hls, boolean embedded) {}

    List<Title> titles(String query, int page) throws Exception {
        JSONObject data = query.isEmpty()
                ? request("discover/watching/" + page, null)
                : request("search/releases/" + page, new JSONObject().put("query", query));
        return titles(array(data, query.isEmpty() ? "content" : "releases"));
    }

    List<Option> voices(Title title) throws Exception {
        return options(array(request("episode/" + title.id(), null), "types"));
    }

    List<Option> sources(Title title, Option voice) throws Exception {
        return options(array(request("episode/" + title.id() + "/" + voice.id(), null), "sources"));
    }

    List<Episode> episodes(Title title, Option voice, Option source) throws Exception {
        var entries = array(request("episode/" + title.id() + "/" + voice.id() + "/" + source.id(), null), "episodes");
        List<Episode> result = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item == null || !item.has("position")) continue;
            long position = item.optLong("position", -1);
            if (position < 0) continue;
            result.add(new Episode(position, label(item, "name", "Серия " + position),
                    label(item, "url", "")));
        }
        return List.copyOf(result);
    }

    Link resolve(Title title, Option source, Episode episode) throws Exception {
        Link existing = link(episode.url());
        if (existing != null && !existing.embedded()) return existing;
        var data = request("episode/target/" + title.id() + "/" + source.id() + "/" + episode.position(), null);
        JSONObject item = data.getJSONObject("episode");
        Link target = link(label(item, "url", ""));
        if (target == null) throw new IOException("Источник не вернул HTTPS-ссылку");
        return target;
    }

    static Link link(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException error) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return null;
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        boolean hls = path.endsWith(".m3u8");
        return new Link(uri.toASCIIString(), hls, !hls && !path.endsWith(".mp4"));
    }

    static URL posterUrl(String value) throws Exception {
        Link link = link(value);
        if (link == null) throw new IOException("Invalid poster URL");
        URL url = new URL(link.url());
        if (!url.getHost().endsWith(".anixmirai.com")) throw new IOException("Unknown poster host");
        return url;
    }

    private static List<Title> titles(JSONArray entries) throws Exception {
        List<Title> result = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            var item = entries.optJSONObject(i);
            if (item == null || item.optLong("id") <= 0 || item.optBoolean("is_deleted")) continue;
            String name = label(item, "title_ru", label(item, "title_original", ""));
            if (name.isEmpty()) continue;
            result.add(new Title(item.optLong("id"), name, label(item, "year", ""),
                    item.optInt("episodes_released"), label(item, "image", ""),
                    item.optBoolean("is_play_disabled") || item.optBoolean("is_view_blocked")));
        }
        return List.copyOf(result);
    }

    private static List<Option> options(JSONArray entries) throws Exception {
        List<Option> result = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            var item = entries.optJSONObject(i);
            if (item == null || item.optLong("id") <= 0) continue;
            String name = label(item, "name", "");
            if (!name.isEmpty()) result.add(new Option(item.optLong("id"), name, item.optInt("episodes_count")));
        }
        return List.copyOf(result);
    }

    private static JSONArray array(JSONObject data, String key) throws Exception {
        JSONArray result = data.optJSONArray(key);
        if (result == null && data.optJSONObject(key) != null) result = data.getJSONObject(key).optJSONArray("content");
        if (result == null) throw new IOException("Неожиданный ответ Anixart");
        return result;
    }

    private static String label(JSONObject item, String key, String fallback) {
        if (item.isNull(key)) return fallback;
        String value = item.optString(key).trim();
        return value.isEmpty() ? fallback : value;
    }

    private static JSONObject request(String path, JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(HOST + path).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "AniartVR/0.3");
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (payload != null) {
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Api-Version", "v2");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Anixart HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                JSONObject data = new JSONObject(new String(read(input, MAX_RESPONSE), StandardCharsets.UTF_8));
                if (data.optInt("code", -1) != 0) throw new IOException("Anixart вернул ошибку");
                return data;
            }
        } finally {
            connection.disconnect();
        }
    }

    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = input.read(buffer)) != -1;) {
            if (out.size() + n > limit) throw new IOException("Response too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
