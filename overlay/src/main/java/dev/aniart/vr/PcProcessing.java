package dev.aniart.vr;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;

record PcProcessing(URI origin, String token) {
    static final int DEPTH_BAND_HEIGHT = 192;

    record Stream(String url, long duration, boolean seekProtocol, boolean layers) {
        long clamp(long position) {
            return Math.max(0, Math.min(position, duration - 100));
        }

        String at(long position) {
            return seekProtocol ? url + "?start_ms=" + clamp(position) : url;
        }
    }

    static PcProcessing parse(String value) {
        URI uri = URI.create(value.trim());
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1
                || uri.getPort() > 65535 || uri.getUserInfo() != null || uri.getQuery() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || uri.getFragment() == null || !uri.getFragment().matches("[A-Za-z0-9_-]{16,128}")) {
            throw new IllegalArgumentException("Use http://PC_IP:8765#TOKEN");
        }
        return new PcProcessing(URI.create("http://" + uri.getRawAuthority()), uri.getFragment());
    }

    Map<String, String> headers() { return Map.of("Authorization", "Bearer " + token); }

    Stream prepare(Catalog.Episode episode, boolean layers) throws Exception {
        var connection = (HttpURLConnection) origin.resolve("/v1/streams").toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        headers().forEach(connection::setRequestProperty);
        byte[] payload = new JSONObject().put("url", episode.url().toString())
                .put("headers", new JSONObject(episode.headers()))
                .put("stereo", layers ? "layers" : "sbs").put("seekable", true)
                .toString().getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (var out = connection.getOutputStream()) { out.write(payload); }
            int status = connection.getResponseCode();
            if (status != 201) throw new IOException("HTTP " + status);
            try (var in = connection.getInputStream()) {
                var response = new JSONObject(new String(in.readNBytes(2048), StandardCharsets.UTF_8));
                String path = response.getString("path");
                if (!path.matches("/v1/streams/[A-Za-z0-9_-]+\\.ts")) throw new IOException("Invalid stream path");
                String mode = response.optString("stereo", "sbs");
                if (!mode.equals("sbs") && !mode.equals("layers")) throw new IOException("Invalid stream format");
                return new Stream(origin.resolve(path).toString(), Math.max(0, response.optLong("duration_ms")),
                        response.has("duration_ms"), mode.equals("layers"));
            }
        } finally {
            connection.disconnect();
        }
    }
}
