package dev.aniart.vr;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;

record PcProcessing(URI origin, String token) {
    static final int DEPTH_BAND_HEIGHT = 192;

    record Video(int height, Boolean anime4k, int depthEvery, Boolean refine) {
        Video withHeight(int value) { return new Video(value, anime4k, depthEvery, refine); }
        Video withAnime4k(boolean value) { return new Video(height, value, depthEvery, refine); }
        Video withDepthEvery(int value) { return new Video(height, anime4k, value, refine); }
        Video withRefine(boolean value) { return new Video(height, anime4k, depthEvery, value); }
    }

    record Stream(String url, long duration, boolean seekProtocol, boolean layers, Video video) {
        long clamp(long position) {
            return Math.max(0, Math.min(position, duration - 100));
        }

        String at(long position) {
            return seekProtocol ? url + "?start_ms=" + clamp(position) : url;
        }
    }

    static PcProcessing parse(String value) {
        URI uri = URI.create(value.trim());
        return fromUri(uri, uri.getFragment());
    }

    static PcProcessing manual(String address, String token) {
        String value = address.trim();
        URI uri;
        try {
            uri = URI.create(value.contains("://") ? value : "http://" + value);
        } catch (IllegalArgumentException invalidAddress) {
            throw new IllegalArgumentException("Проверьте адрес сервера");
        }
        if (uri.getFragment() != null) throw new IllegalArgumentException("Укажите токен в отдельном поле");
        return fromUri(uri, token.trim());
    }

    private static PcProcessing fromUri(URI uri, String token) {
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() == 0
                || uri.getPort() > 65535 || uri.getUserInfo() != null || uri.getQuery() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("Введите адрес ПК, например 192.168.1.10:8765");
        }
        if (token == null || !token.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new IllegalArgumentException("Токен должен содержать 16–128 латинских букв, цифр, «-» или «_»");
        }
        return new PcProcessing(URI.create("http://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 8765 : uri.getPort())), token);
    }

    Map<String, String> headers() { return Map.of("Authorization", "Bearer " + token); }

    Stream prepare(Catalog.Episode episode, boolean layers, Video video) throws Exception {
        var connection = (HttpURLConnection) origin.resolve("/v1/streams").toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        headers().forEach(connection::setRequestProperty);
        var request = new JSONObject().put("url", episode.url().toString())
                .put("headers", new JSONObject(episode.headers()))
                .put("stereo", layers ? "layers" : "sbs").put("seekable", true);
        if (video.height() > 0) request.put("height", video.height());
        if (video.anime4k() != null) request.put("anime4k", video.anime4k());
        if (video.depthEvery() > 0) request.put("depth_every", video.depthEvery());
        if (video.refine() != null) request.put("depth_refine", video.refine());
        byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
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
                var effective = new Video(response.optInt("height"), booleanField(response, "anime4k"),
                        response.optInt("depth_every"), booleanField(response, "depth_refine"));
                return new Stream(origin.resolve(path).toString(), Math.max(0, response.optLong("duration_ms")),
                        response.has("duration_ms"), mode.equals("layers"), effective);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Boolean booleanField(JSONObject object, String key) {
        return object.opt(key) instanceof Boolean value ? value : null;
    }
}
