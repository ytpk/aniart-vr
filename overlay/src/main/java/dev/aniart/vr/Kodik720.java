package dev.aniart.vr;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class Kodik720 {
    private static final Pattern SOURCE = Pattern.compile("/480\\.mp4(?::hls:manifest\\.m3u8)?$");

    @FunctionalInterface
    interface Probe {
        boolean isPlaylist(String url, Map<String, String> headers) throws IOException;
    }

    static List<KodikStreams.Stream> addIfAvailable(List<KodikStreams.Stream> streams, Probe probe) {
        if (streams.stream().anyMatch(stream -> stream.height() == 720)) return streams;
        for (var stream : streams) {
            var candidate = check(stream.url(), stream.headers(), probe);
            if (candidate != null) {
                var choices = new java.util.ArrayList<KodikStreams.Stream>();
                choices.add(candidate);
                choices.addAll(streams);
                return List.copyOf(choices);
            }
            // Check at most one eligible CDN variant.
            if (candidateUrl(stream.url()) != null) break;
        }
        return streams;
    }

    static KodikStreams.Stream check(String source, Map<String, String> headers, Probe probe) {
        String url = candidateUrl(source);
        if (url == null) return null;
        try {
            if (probe.isPlaylist(url, headers)) return new KodikStreams.Stream(url, 720, true, headers);
        } catch (IOException error) {
            // Continue with the original source if the extra quality check times out.
        }
        return null;
    }

    static String candidateUrl(String source) {
        URI uri;
        try {
            uri = URI.create(source);
        } catch (IllegalArgumentException error) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getPort() != -1 || uri.getRawFragment() != null) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("solodcdn.com") && !host.endsWith(".solodcdn.com")
                && !host.equals("kodik-cdn.com") && !host.endsWith(".kodik-cdn.com")) return null;
        String path = uri.getRawPath();
        if (!SOURCE.matcher(path).find()) return null;
        return uri.getScheme() + "://" + uri.getRawAuthority() + SOURCE.matcher(path).replaceFirst("/720.mp4:hls:manifest.m3u8")
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    static boolean isPlaylist(String url, Map<String, String> headers) throws IOException {
        var connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(4000);
        connection.setInstanceFollowRedirects(false);
        headers.forEach(connection::setRequestProperty);
        try {
            if (connection.getResponseCode() != 200) return false;
            try (var input = connection.getInputStream()) {
                byte[] bytes = new byte[4096];
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count == -1) break;
                    offset += count;
                    if (isPlaylistPrefix(new String(bytes, 0, offset, StandardCharsets.UTF_8))) return true;
                }
                return false;
            }
        } finally {
            connection.disconnect();
        }
    }

    static boolean isPlaylistPrefix(String body) {
        if (body.startsWith("\uFEFF")) body = body.substring(1);
        return (body.startsWith("#EXTM3U\n") || body.startsWith("#EXTM3U\r\n"))
                && (body.contains("\n#EXTINF:") || body.contains("\n#EXT-X-STREAM-INF:"));
    }
}
