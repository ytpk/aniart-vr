package dev.aniart.vr;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class IncomingVideo {
    private static final Pattern QUALITY = Pattern.compile("/([0-9]{3,4})\\.mp4:hls:manifest\\.m3u8$");
    private static final Map<String, String> HEADER_NAMES = Map.of(
            "referer", "Referer", "origin", "Origin", "user-agent", "User-Agent");

    record Link(String url, String host, boolean hls, String quality, Map<String, String> headers) {}

    static Link parse(String value, String[] extras) {
        if (value == null || value.length() > 8192) return null;
        value = value.trim();
        if (value.chars().anyMatch(Character::isWhitespace)) return null;
        URI uri = https(value);
        if (uri == null) return null;
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        boolean hls = path.endsWith(".m3u8");
        if (!hls && !path.endsWith(".mp4")) return null;

        var headers = new LinkedHashMap<>(KodikStreams.headersForCdn(value));
        if (extras != null && extras.length <= 32) {
            // Video callers commonly use either flat name/value pairs or "Name: Value" entries.
            for (int i = 0; i < extras.length; i++) {
                String line = extras[i];
                if (line == null) continue;
                int split = line.indexOf(':');
                if (split >= 0) add(headers, line.substring(0, split), line.substring(split + 1));
                else if (i + 1 < extras.length) add(headers, line, extras[++i]);
            }
        }
        var quality = QUALITY.matcher(path);
        return new Link(value, uri.getHost(), hls, quality.find() ? quality.group(1) + "p" : hls ? "HLS" : "MP4",
                Map.copyOf(headers));
    }

    static String title(String externalTitle, String fallback) {
        if (externalTitle == null || externalTitle.length() > 160
                || externalTitle.chars().anyMatch(Character::isISOControl)) return fallback;
        String title = externalTitle.trim();
        return title.isEmpty() ? fallback : title;
    }

    private static void add(Map<String, String> headers, String key, String value) {
        String name = HEADER_NAMES.get(key.trim().toLowerCase(Locale.ROOT));
        if (name == null || value == null || value.length() > 2048 || value.chars().anyMatch(Character::isISOControl)) return;
        value = value.trim();
        if (value.isEmpty() || (!name.equals("User-Agent") && https(value) == null)) return;
        headers.put(name, value);
    }

    private static URI https(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null ? uri : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
