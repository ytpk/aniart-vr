package dev.aniart.vr;

import android.content.Context;
import android.util.Log;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

final class DepthModels {
    static final String LIMBO_FILE = "LimboV3_504x280_fp32.onnx";
    static final int CAPTURE_W = 256, CAPTURE_H = 144, MAP_W = 192, MAP_H = 108;
    private static final String VDA = "https://huggingface.co/EmreGneri/video-depth-anything-small-stream/resolve/56ff0a88aaf2262d50d5d82d69b47e9d40e1c991/";
    private static final String ZIP = "https://huggingface.co/lc700x/ZipDepth-EdgePad/resolve/main/";
    private static final String ZIP_GPU = "https://github.com/tB0nE/nightfall/releases/download/edgepad-models-2026-10-08/";
    private static final String LIMBO_HASH = "6a8f894f08fdb651036c3e6875bf357574b03003a4da2ae1ca9e82b66fc8534f";
    private static final float[] MEAN = {.485f, .456f, .406f};
    private static final float[] STD = {.229f, .224f, .225f};

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile int epoch;
    private volatile int selected;
    private volatile boolean busy, closed, failed;
    private record MapFrame(int epoch, byte[] map) {}
    private final AtomicReference<MapFrame> result = new AtomicReference<>();
    private volatile String status = "";
    private Backend backend;
    private int loaded, previousEpoch = -1;
    private float[] lastSignature;
    private float low, high;

    DepthModels(Context context) { this.context = context; }

    static String label(int model) {
        return switch (model) {
            case 1 -> "ZipDepth";
            case 2 -> "Limbo V3";
            case 3 -> "Video Depth Anything";
            default -> "По яркости";
        };
    }

    String status() { return status; }

    void select(int model) {
        // Called on the GL thread, including when a seek invalidates the previous frame.
        boolean reload = selected != model || failed;
        epoch++;
        selected = model;
        failed = false;
        result.set(null);
        status = model == 0 ? "" : "Ожидание карты";
        if (reload && model == 0 && !closed) worker.execute(this::release);
    }

    boolean wantsFrame() { return selected != 0 && !busy && !failed && !closed; }

    byte[] takeMap() {
        var frame = result.getAndSet(null);
        return frame != null && frame.epoch() == epoch ? frame.map() : null;
    }

    void submit(byte[] rgba) {
        if (!wantsFrame()) return;
        int job = epoch, model = selected;
        busy = true;
        worker.execute(() -> {
            try {
                if (job != epoch) return;
                if (loaded != model) {
                    release();
                    update(job, "Подготовка модели…");
                    backend = load(job, model);
                    loaded = model;
                }
                if (job != epoch) return;
                long started = System.nanoTime();
                float[] signature = signature(rgba);
                boolean reset = job != previousEpoch || isCut(lastSignature, signature);
                if (reset) {
                    backend.reset();
                    previousEpoch = job;
                }
                lastSignature = signature;
                float[] values;
                try {
                    values = backend.infer(rgba);
                } catch (RuntimeException | LinkageError error) {
                    if (!(backend instanceof ZipGpuBackend) || job != epoch) throw error;
                    Log.w("AniartDepth", "ZipDepth GPU fallback", error);
                    release();
                    backend = loadCpu(job, model);
                    loaded = model;
                    values = backend.infer(rgba);
                }
                byte[] map = normalize(values, backend.width, backend.height, model == 2, reset);
                if (job == epoch && !closed) {
                    result.set(new MapFrame(job, map));
                    update(job, (backend instanceof ZipGpuBackend ? "GPU " : "CPU ")
                            + Math.round((System.nanoTime() - started) / 1_000_000.0) + " мс");
                }
            } catch (Exception | LinkageError error) {
                if (job == epoch && !closed) {
                    failed = true;
                    update(job, error instanceof MissingWeights ? "Нужен файл Limbo V3" : "Ошибка модели");
                    Log.w("AniartDepth", label(model), error);
                }
            } finally {
                busy = false;
            }
        });
    }

    private void update(int job, String text) {
        if (job == epoch && !closed) status = text;
    }

    void shutdown() {
        closed = true;
        epoch++;
        worker.execute(this::release);
        worker.shutdown();
    }

    private void release() {
        if (backend != null) backend.close();
        backend = null;
        loaded = 0;
    }

    private Backend load(int job, int model) throws Exception {
        if (model == 1) {
            try {
                File file = modelFile(job, ZIP_GPU, "zipdepth-wide-512x288-edgepad-gpu.tflite", 12342180,
                        "dfa40c15155df2a7ae2eb2ccdf78da610ae4722b1cc388fa93e6888bcc78d0df");
                return new ZipGpuBackend(file);
            } catch (Exception | LinkageError error) {
                if (job != epoch || closed) throw error;
                Log.w("AniartDepth", "ZipDepth GPU unavailable", error);
            }
        }
        return loadCpu(job, model);
    }

    private Backend loadCpu(int job, int model) throws Exception {
        OrtEnvironment ort = OrtEnvironment.getEnvironment();
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            if (model == 1) {
                File file = modelFile(job, ZIP, "zipdepth_wide_512x288.onnx", 24625797,
                        "197d598f50ee3270b12831096dbab260c6bd5979bab13b87f01a28fad0268274");
                return new Backend(ort, options, 512, 288, false, "image", file);
            }
            if (model == 2) {
                File file = new File(context.getFilesDir(), LIMBO_FILE);
                if (!file.isFile() || file.length() != 100449120 || !LIMBO_HASH.equals(sha256(file))) {
                    throw new MissingWeights();
                }
                return new Backend(ort, options, 504, 280, true, "image", file);
            }
            File encoder = modelFile(job, VDA, "encoder_fp16.onnx", 43327081,
                    "3c774358e5dc294fd69366869e4cae5eb27d240658194043c291899cc577f6a1");
            File bootstrap = modelFile(job, VDA, "bootstrap_fp16.onnx", 14170095,
                    "bc8bef05465c2a80e7ac2ee166a7b553bf0e54f4c64f328866b24a9ebd34d564");
            File stream = modelFile(job, VDA, "stream_fp16.onnx", 15536625,
                    "9120ea5b7b45730c246f9f41cb5352bfe5988097f5912dd29c35ba6a4d7a227b");
            return new VideoBackend(ort, options, encoder, bootstrap, stream);
        }
    }

    private static final class MissingWeights extends IOException {}

    void importLimbo(InputStream input) throws Exception {
        File file = new File(context.getFilesDir(), LIMBO_FILE);
        File temp = File.createTempFile("limbo", ".onnx", context.getFilesDir());
        try {
            try (var output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    total += count;
                    if (total > 100449120) throw new MissingWeights();
                    output.write(buffer, 0, count);
                }
            }
            if (temp.length() != 100449120 || !LIMBO_HASH.equals(sha256(temp))) throw new MissingWeights();
            Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    private File modelFile(int job, String base, String name, long size, String hash) throws Exception {
        File folder = new File(context.getFilesDir(), "depth-models");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create model directory");
        File file = new File(folder, name);
        if (file.isFile() && file.length() == size && hash.equals(sha256(file))) return file;
        File temp = File.createTempFile(name, ".download", folder);
        var connection = (HttpURLConnection) URI.create(base + name + "?download=true").toURL().openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        try {
            try (var input = connection.getInputStream(); var output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int progress = -1;
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (closed || job != epoch) throw new IOException("Cancelled model download");
                    total += count;
                    if (total > size) throw new IOException("Model was larger than expected");
                    output.write(buffer, 0, count);
                    int percent = (int) (total * 100 / size);
                    if (percent != progress) {
                        progress = percent;
                        update(job, "Загрузка модели " + percent + "%");
                    }
                }
            }
            if (temp.length() != size || !hash.equals(sha256(temp))) throw new IOException("Invalid model checksum");
            Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            update(job, "Подготовка модели…");
            return file;
        } finally {
            connection.disconnect();
            Files.deleteIfExists(temp.toPath());
        }
    }

    private static String sha256(File file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        var hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b & 255) >> 4, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    private static class Backend {
        final OrtEnvironment ort;
        final OrtSession encoder;
        final int width, height;
        final boolean normalize;
        final String inputName;

        Backend(OrtEnvironment ort, OrtSession.SessionOptions options, int width, int height,
                boolean normalize, String inputName, File file) throws Exception {
            this.ort = ort;
            this.width = width;
            this.height = height;
            this.normalize = normalize;
            this.inputName = inputName;
            encoder = file == null ? null : ort.createSession(file.getAbsolutePath(), options);
        }

        float[] rgb(byte[] rgba, boolean planar) {
            int pixels = width * height;
            float[] rgb = new float[pixels * 3];
            for (int y = 0; y < height; y++) {
                // GL readback is bottom-up. Models expect top-down RGB.
                float srcY = (height - y - .5f) * CAPTURE_H / height - .5f;
                int y0 = clamp((int) Math.floor(srcY), CAPTURE_H);
                int y1 = clamp((int) Math.ceil(srcY), CAPTURE_H);
                for (int x = 0; x < width; x++) {
                    float srcX = (x + .5f) * CAPTURE_W / width - .5f;
                    int x0 = clamp((int) Math.floor(srcX), CAPTURE_W);
                    int x1 = clamp((int) Math.ceil(srcX), CAPTURE_W);
                    for (int c = 0; c < 3; c++) {
                        float value = sample(rgba, c, x0, x1, y0, y1, srcX, srcY);
                        int index = planar ? c * pixels + y * width + x : (y * width + x) * 3 + c;
                        rgb[index] = normalize ? (value - MEAN[c]) / STD[c] : value;
                    }
                }
            }
            return rgb;
        }

        OnnxTensor input(byte[] rgba) throws Exception {
            return OnnxTensor.createTensor(ort, FloatBuffer.wrap(rgb(rgba, true)), new long[]{1, 3, height, width});
        }

        private static float sample(byte[] rgba, int c, int x0, int x1, int y0, int y1, float x, float y) {
            float fx = Math.max(0, x - x0), fy = Math.max(0, y - y0);
            int a = (y0 * CAPTURE_W + x0) * 4 + c, b = (y0 * CAPTURE_W + x1) * 4 + c;
            int d = (y1 * CAPTURE_W + x0) * 4 + c, e = (y1 * CAPTURE_W + x1) * 4 + c;
            float top = (rgba[a] & 255) * (1 - fx) + (rgba[b] & 255) * fx;
            float bottom = (rgba[d] & 255) * (1 - fx) + (rgba[e] & 255) * fx;
            return (top * (1 - fy) + bottom * fy) / 255;
        }

        float[] infer(byte[] rgba) throws Exception {
            try (var input = input(rgba); var output = encoder.run(Map.of(inputName, input))) {
                var tensor = output.get("depth").orElse(output.get(0));
                return floats((OnnxTensor) tensor);
            }
        }

        void reset() {}
        void close() { closeSession(encoder); }
    }

    private static final class ZipGpuBackend extends Backend {
        private final GpuDelegate delegate;
        private final Interpreter interpreter;
        private final ByteBuffer inputBuffer, outputBuffer;

        ZipGpuBackend(File file) throws Exception {
            super(null, null, 512, 288, false, "image", null);
            inputBuffer = ByteBuffer.allocateDirect(width * height * 3 * Float.BYTES).order(ByteOrder.nativeOrder());
            outputBuffer = ByteBuffer.allocateDirect(width * height * Float.BYTES).order(ByteOrder.nativeOrder());
            delegate = new GpuDelegate(new GpuDelegate.Options().setPrecisionLossAllowed(true));
            try {
                interpreter = new Interpreter(file, new Interpreter.Options().addDelegate(delegate));
            } catch (RuntimeException | LinkageError error) {
                delegate.close();
                throw error;
            }
        }

        @Override float[] infer(byte[] rgba) {
            inputBuffer.asFloatBuffer().put(rgb(rgba, false));
            interpreter.run(inputBuffer, outputBuffer);
            var packed = outputBuffer.asFloatBuffer();
            float[] depth = new float[width * height];
            for (int y = 0; y < height; y += 2) {
                for (int x = 0; x < width; x += 2) {
                    int pos = y * width + x;
                    depth[pos] = Math.max(0, packed.get());
                    depth[pos + 1] = Math.max(0, packed.get());
                    depth[pos + width] = Math.max(0, packed.get());
                    depth[pos + width + 1] = Math.max(0, packed.get());
                }
            }
            return depth;
        }

        @Override void close() {
            try { interpreter.close(); }
            finally { delegate.close(); }
        }
    }

    private static final class VideoBackend extends Backend {
        private final OrtSession bootstrap, stream;
        private final List<float[][]> history = new ArrayList<>();
        private static final int[] SPATIAL = {209, 209, 60, 60, 209, 209, 836, 836};
        private static final int[] CHANNELS = {192, 192, 384, 384, 64, 64, 64, 64};

        VideoBackend(OrtEnvironment ort, OrtSession.SessionOptions options, File first, File initial, File recurrent)
                throws Exception {
            super(ort, options, 266, 154, true, "pixels", first);
            OrtSession start = null;
            try {
                start = ort.createSession(initial.getAbsolutePath(), options);
                stream = ort.createSession(recurrent.getAbsolutePath(), options);
                bootstrap = start;
            } catch (Exception error) {
                closeSession(start);
                super.close();
                throw error;
            }
        }

        @Override float[] infer(byte[] rgba) throws Exception {
            try (var input = input(rgba); var encoded = encoder.run(Map.of(inputName, input))) {
                var feeds = new HashMap<String, OnnxTensor>();
                for (int i = 1; i <= 4; i++) feeds.put("feat" + i, tensor(encoded, "feat" + i));
                boolean first = history.isEmpty();
                var packed = new ArrayList<OnnxTensor>();
                try {
                    if (!first) {
                        for (int i = 0; i < 8; i++) {
                            int spatial = SPATIAL[i], channels = CHANNELS[i];
                            float[] values = new float[spatial * 31 * channels];
                            for (int pos = 0; pos < spatial; pos++) {
                                for (int time = 0; time < 31; time++) {
                                    int slot = time < 2 ? time : history.size() - 31 + time;
                                    System.arraycopy(history.get(slot)[i], pos * channels,
                                            values, (pos * 31 + time) * channels, channels);
                                }
                            }
                            var cache = OnnxTensor.createTensor(ort, FloatBuffer.wrap(values),
                                    new long[]{spatial, 31, channels});
                            packed.add(cache);
                            feeds.put("cache" + i, cache);
                        }
                    }
                    try (var output = (first ? bootstrap : stream).run(feeds)) {
                        float[][] frame = new float[8][];
                        for (int i = 0; i < 8; i++) frame[i] = floats(tensor(output, (first ? "cache" : "nextCache") + i));
                        if (first) {
                            for (int i = 0; i < 32; i++) history.add(frame);
                        } else {
                            history.add(frame);
                            if (history.size() > 42) history.remove(1);
                        }
                        return floats(tensor(output, "depth"));
                    }
                } finally {
                    packed.forEach(OnnxTensor::close);
                }
            }
        }

        @Override void reset() { history.clear(); }
        @Override void close() {
            closeSession(stream);
            closeSession(bootstrap);
            super.close();
        }
    }

    private static OnnxTensor tensor(OrtSession.Result result, String name) {
        return (OnnxTensor) result.get(name).orElseThrow();
    }

    private static float[] floats(OnnxTensor tensor) {
        FloatBuffer data = tensor.getFloatBuffer();
        float[] values = new float[data.remaining()];
        data.get(values);
        return values;
    }

    private static void closeSession(OrtSession session) {
        if (session == null) return;
        try { session.close(); }
        catch (Exception error) { Log.w("AniartDepth", "Close depth session", error); }
    }

    private byte[] normalize(float[] values, int width, int height, boolean inverse, boolean reset) throws IOException {
        if (values.length != width * height) throw new IOException("Unexpected depth map size " + values.length);
        float[] samples = new float[(values.length + 15) / 16];
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            float d = values[i];
            if (inverse && Float.isFinite(d) && d > 0) d = 1 / Math.max(d, .000001f);
            values[i] = d;
            if (i % 16 == 0 && Float.isFinite(d)) samples[count++] = d;
        }
        byte[] map = new byte[MAP_W * MAP_H];
        if (count < 2) {
            Arrays.fill(map, (byte) 128);
            return map;
        }
        Arrays.sort(samples, 0, count);
        float bottom = samples[(int) ((count - 1) * .02f)], top = samples[(int) ((count - 1) * .98f)];
        low = reset ? bottom : .85f * low + .15f * bottom;
        high = reset ? top : .85f * high + .15f * top;
        if (high - low <= .000001f) {
            Arrays.fill(map, (byte) 128);
            return map;
        }
        for (int y = 0; y < MAP_H; y++) {
            int sy = clamp((MAP_H - y - 1) * height / MAP_H, height);
            for (int x = 0; x < MAP_W; x++) {
                float v = values[sy * width + x * width / MAP_W];
                map[y * MAP_W + x] = (byte) (Float.isFinite(v)
                        ? Math.round(255 * Math.max(0, Math.min(1, (v - low) / (high - low)))) : 128);
            }
        }
        return map;
    }

    private static int clamp(int value, int size) { return Math.max(0, Math.min(size - 1, value)); }

    private static float[] signature(byte[] rgba) {
        float[] result = new float[16];
        for (int y = 0; y < CAPTURE_H; y += 4) {
            for (int x = 0; x < CAPTURE_W; x += 4) {
                int i = (y * CAPTURE_W + x) * 4;
                result[(y * 4 / CAPTURE_H) * 4 + x * 4 / CAPTURE_W] +=
                        (.2126f * (rgba[i] & 255) + .7152f * (rgba[i + 1] & 255) + .0722f * (rgba[i + 2] & 255))
                                / (255 * CAPTURE_W * CAPTURE_H / 256f);
            }
        }
        return result;
    }

    private static boolean isCut(float[] before, float[] now) {
        if (before == null) return true;
        float diff = 0;
        for (int i = 0; i < now.length; i++) diff += Math.abs(now[i] - before[i]);
        return diff / now.length > .24f;
    }
}
