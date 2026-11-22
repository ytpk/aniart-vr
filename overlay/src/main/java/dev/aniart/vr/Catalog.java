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
import java.util.Map;

interface Catalog {
    record Episode(String title, String episode, String voice, String quality,
                   Uri url, boolean hls, Map<String, String> headers) {}

    List<Episode> load(Context context) throws Exception;

    final class Json implements Catalog {
        @Override
        public List<Episode> load(Context context) throws Exception {
            File override = new File(context.getFilesDir(), "catalog.json");
            try (InputStream stream = override.isFile() ? new FileInputStream(override)
                    : context.getAssets().open("catalog.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int length; (length = stream.read(buffer)) != -1;) {
                    if (bytes.size() + length > 1024 * 1024) {
                        throw new IllegalArgumentException("Каталог больше 1 МиБ");
                    }
                    bytes.write(buffer, 0, length);
                }
                JSONArray entries = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                List<Episode> episodes = new ArrayList<>();
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    Uri url = Uri.parse(entry.getString("url"));
                    if (!"https".equals(url.getScheme()) || url.getHost() == null) {
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
                    episodes.add(new Episode(entry.getString("title"), entry.getString("episode"),
                            entry.optString("voice"), entry.optString("quality"), url,
                            type.equals("hls"), Map.copyOf(headers)));
                }
                return List.copyOf(episodes);
            }
        }
    }
}
