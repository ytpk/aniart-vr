package dev.aniart.vr;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

interface Catalog {
    record Episode(String title, String episode, String voice, String quality,
                   Uri url, boolean hls, Map<String, String> headers, int stereo) {
        String key() { return title + "\n" + episode + "\n" + voice + "\n" + quality; }
        String variant() {
            return (voice.isEmpty() ? "Оригинал" : voice) + (quality.isEmpty() ? "" : "  ·  " + quality);
        }
    }

    record Series(String title, Map<String, List<Episode>> episodes) {}

    static List<Series> group(List<Episode> entries) {
        Map<String, LinkedHashMap<String, List<Episode>>> titles = new LinkedHashMap<>();
        for (Episode episode : entries) {
            titles.computeIfAbsent(episode.title(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(episode.episode(), key -> new ArrayList<>()).add(episode);
        }
        List<Series> result = new ArrayList<>();
        for (var entry : titles.entrySet()) {
            Map<String, List<Episode>> groups = new LinkedHashMap<>();
            entry.getValue().forEach((name, variants) -> groups.put(name, List.copyOf(variants)));
            result.add(new Series(entry.getKey(), java.util.Collections.unmodifiableMap(groups)));
        }
        return List.copyOf(result);
    }

    List<Episode> load(Context context) throws Exception;

    final class Json implements Catalog {
        @Override public List<Episode> load(Context context) throws Exception {
            File override = new File(context.getFilesDir(), "catalog.json");
            try (InputStream stream = override.isFile() ? new FileInputStream(override)
                    : context.getAssets().open("catalog.json")) {
                return parse(read(stream), !override.isFile());
            }
        }

        static byte[] read(InputStream stream) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int length; (length = stream.read(buffer)) != -1;) {
                if (bytes.size() + length > 1024 * 1024) {
                    throw new IllegalArgumentException("Каталог больше 1 МиБ");
                }
                bytes.write(buffer, 0, length);
            }
            return bytes.toByteArray();
        }

        static List<Episode> parse(byte[] data, boolean bundled) throws Exception {
            JSONArray entries = new JSONArray(new String(data, StandardCharsets.UTF_8));
            List<Episode> episodes = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                Uri url = Uri.parse(entry.getString("url"));
                boolean demo = bundled && (url.toString().equals("asset:///stereo-demo.mp4")
                        || url.toString().equals("asset:///room-demo.mp4"));
                if (!demo && (!"https".equals(url.getScheme()) || url.getHost() == null)) {
                    throw new IllegalArgumentException("Укажите HTTPS-адрес потока");
                }
                String type = entry.getString("type");
                if (!type.equals("hls") && !type.equals("mp4")) {
                    throw new IllegalArgumentException("Допустимые типы: hls, mp4");
                }
                Map<String, String> headers = new LinkedHashMap<>();
                JSONObject fields = entry.optJSONObject("headers");
                if (fields != null) {
                    for (var keys = fields.keys(); keys.hasNext();) {
                        String key = keys.next();
                        String value = fields.getString(key);
                        if (!key.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+") ||
                                value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                            throw new IllegalArgumentException("Некорректный HTTP-заголовок");
                        }
                        headers.put(key, value);
                    }
                }
                String title = entry.getString("title").trim();
                String episode = entry.getString("episode").trim();
                if (title.isEmpty() || episode.isEmpty()) throw new IllegalArgumentException("Пустое название");
                int stereo = switch (entry.optString("stereo").toLowerCase(Locale.ROOT)) {
                    case "sbs" -> 1;
                    case "ou" -> 2;
                    case "", "2d" -> 0;
                    default -> throw new IllegalArgumentException("Допустимые форматы stereo: 2d, sbs, ou");
                };
                episodes.add(new Episode(title, episode, entry.optString("voice"), entry.optString("quality"),
                        url, type.equals("hls"), Map.copyOf(headers), stereo));
            }
            return List.copyOf(episodes);
        }
    }
}
