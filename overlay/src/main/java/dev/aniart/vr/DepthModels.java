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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

final class DepthModels {
    static final String LIMBO_FILE = "LimboV3_504x280_fp32.onnx";
    private static final long LIMBO_SIZE = 100449120;
    private static final String LIMBO_URL =
            "https://github.com/NevermindNilas/TAS-Models-Host/releases/download/main/" + LIMBO_FILE;
    static final int CAPTURE_W = 256, CAPTURE_H = 144, MAP_W = 192, MAP_H = 108;
    private static final String ZIP_GPU = "zipdepth-wide-320x180-t320x192-edgepad-gpu.tflite";
    private static final String ZIP_CPU = "zipdepth-base-256-cpu.tflite";
    private static final String LIMBO_HASH = "6a8f894f08fdb651036c3e6875bf357574b03003a4da2ae1ca9e82b66fc8534f";
    private static final float[] MEAN = {.485f, .456f, .406f};
    private static final float[] STD = {.229f, .224f, .225f};

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile int epoch;
    private volatile int selected;
    private volatile boolean busy, closed, failed;
    private record MapFrame(int epoch, byte[] map) {}
    private final AtomicReference<MapFrame> result = new AtomicReference<>();
    private volatile String status = "";
    private Backend backend;
    private int loaded, previousEpoch = -1;
    private float[] lastSignature;
    private float low, high, shotMedian;
    private byte[] lastFrame;
    private static final byte[] REUSED_FRAME = {2};

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

    boolean wantsFrame() { return selected != 0 && !busy && !failed && !closed && !checking.get(); }

    synchronized void runDiagnostics(String renderer, Consumer<String> report) {
        if (closed || !checking.compareAndSet(false, true)) return;
        report.accept("Диагностика 3D…");
        worker.execute(() -> {
            Log.i("AniartDepth", "Diagnostics begin | " + renderer
                    + " | Android " + android.os.Build.VERSION.SDK_INT
                    + " | " + android.os.Build.MODEL + " | ABI " + Arrays.toString(android.os.Build.SUPPORTED_ABIS)
                    + " | CPU logical cores " + Runtime.getRuntime().availableProcessors());
            try {
                // The production interpreter and the diagnostic delegate share this thread.
                release();
                byte[] rgba = new byte[CAPTURE_W * CAPTURE_H * 4];
                for (int y = 0; y < CAPTURE_H; y++) {
                    for (int x = 0; x < CAPTURE_W; x++) {
                        int at = (y * CAPTURE_W + x) * 4;
                        rgba[at] = (byte) x;
                        rgba[at + 1] = (byte) (255 * y / CAPTURE_H);
                        rgba[at + 2] = (byte) (255 - x);
                        rgba[at + 3] = (byte) 255;
                    }
                }
                int passed = checkBackend("ZipDepth GPU", () -> new ZipLiteBackend(modelAsset(ZIP_GPU), true), rgba, 1);
                passed += checkBackend("ZipDepth CPU", () -> loadCpu(1), rgba, 1);
                try {
                    Log.i("AniartDepth", "ONNX Runtime providers " + OrtEnvironment.getAvailableProviders());
                } catch (RuntimeException | LinkageError error) {
                    Log.w("AniartDepth", "ONNX Runtime provider query failed", error);
                }
                passed += checkBackend("Video Depth Anything CPU", () -> loadCpu(3), rgba, 2);
                String limbo;
                if (new File(context.getFilesDir(), LIMBO_FILE).isFile()) {
                    limbo = checkBackend("Limbo V3 CPU", () -> loadCpu(2), rgba, 1) == 1 ? "OK" : "ошибка";
                } else {
                    limbo = "нет файла";
                    Log.i("AniartDepth", "Limbo V3 CPU skipped: weights not installed");
                }
                report.accept("Проверок пройдено " + passed + "/3 · Limbo: " + limbo);
            } catch (Exception | LinkageError error) {
                Log.w("AniartDepth", "Diagnostics failed", error);
                report.accept("Ошибка диагностики. См. AniartDepth");
            } finally {
                // The next live frame must be calculated by the freshly loaded backend.
                previousEpoch = -1;
                Log.i("AniartDepth", "Diagnostics end");
                checking.set(false);
            }
        });
    }

    private int checkBackend(String name, Callable<Backend> open, byte[] frame, int passes) {
        Backend test = null;
        try {
            long start = System.nanoTime();
            test = open.call();
            Log.i("AniartDepth", name + " initialized in " + (System.nanoTime() - start) / 1_000_000 + " ms");
            for (int pass = 1; pass <= passes; pass++) {
                start = System.nanoTime();
                float[] depth = test.infer(frame);
                int finite = 0;
                for (float value : depth) if (Float.isFinite(value)) finite++;
                if (finite != depth.length || finite != test.width * (test.height - 2 * test.contentPadding())) {
                    throw new IOException("Unexpected depth output: " + depth.length + " values, " + finite + " finite");
                }
                Log.i("AniartDepth", name + " pass " + pass + ": "
                        + (System.nanoTime() - start) / 1_000_000 + " ms, " + finite + " values");
            }
            return 1;
        } catch (Exception | LinkageError error) {
            Log.w("AniartDepth", name + " diagnostic failed", error);
            return 0;
        } finally {
            if (test != null) {
                try {
                    test.close();
                } catch (RuntimeException | LinkageError error) {
                    Log.w("AniartDepth", name + " diagnostic cleanup failed", error);
                }
            }
        }
    }

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
                if (job == previousEpoch && Arrays.equals(lastFrame, rgba)) {
                    if (job == epoch && !closed) {
                        result.set(new MapFrame(job, REUSED_FRAME));
                        update(job, "Повтор кадра · карта сохранена");
                    }
                    return;
                }
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
                    if (!isZipGpu() || job != epoch) throw error;
                    Log.w("AniartDepth", "ZipDepth GPU fallback", error);
                    release();
                    backend = loadCpu(model);
                    loaded = model;
                    values = backend.infer(rgba);
                }
                byte[] map = normalize(values, backend.width, backend.height - 2 * backend.contentPadding(),
                        model == 2, reset, model == 3);
                lastFrame = rgba.clone();
                byte[] packet = Arrays.copyOf(map, map.length + 1);
                packet[map.length] = (byte) (reset ? 1 : 0);
                if (job == epoch && !closed) {
                    result.set(new MapFrame(job, packet));
                    update(job, (isZipGpu() ? "GPU " : "CPU ")
                            + Math.round((System.nanoTime() - started) / 1_000_000.0) + " мс");
                }
            } catch (Exception | LinkageError error) {
                if (job == epoch && !closed) {
                    failed = true;
                    update(job, error instanceof MissingWeights ? "Нужен файл Limbo V3"
                            : error instanceof ai.onnxruntime.OrtException ortError ? "ONNX: " + ortError.getCode()
                            : "Модель: " + error.getClass().getSimpleName());
                    Log.w("AniartDepth", label(model), error);
                }
            } finally {
                busy = false;
            }
        });
    }

    private boolean isZipGpu() { return backend instanceof ZipLiteBackend zip && zip.gpu; }

    private void update(int job, String text) {
        if (job == epoch && !closed) status = text;
    }

    synchronized void shutdown() {
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
                return new ZipLiteBackend(modelAsset(ZIP_GPU), true);
            } catch (Exception | LinkageError error) {
                if (job != epoch || closed) throw error;
                Log.w("AniartDepth", "ZipDepth GPU unavailable", error);
            }
        }
        return loadCpu(model);
    }

    private Backend loadCpu(int model) throws Exception {
        if (model == 1) return new ZipLiteBackend(modelAsset(ZIP_CPU), false);
        OrtEnvironment ort = OrtEnvironment.getEnvironment();
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            if (model == 2) {
                File file = new File(context.getFilesDir(), LIMBO_FILE);
                if (!validLimbo(file)) {
                    throw new MissingWeights();
                }
                return new Backend(ort, options, 504, 280, true, "image", file);
            }
            // Extended optimizations fuse the encoder's FP16 graph into com.microsoft.Gelu.
            // The ARM64 CPU provider in our Android build only has an FP32 kernel for that fusion.
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            return new VideoBackend(ort, options, modelAsset("encoder_fp16.onnx"),
                    modelAsset("bootstrap_fp16.onnx"), modelAsset("stream_fp16.onnx"));
        }
    }

    private ByteBuffer modelAsset(String file) throws IOException {
        try (var descriptor = context.getAssets().openFd("depth/" + file);
             var stream = descriptor.createInputStream()) {
            return stream.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    descriptor.getStartOffset(), descriptor.getLength());
        }
    }

    private static final class MissingWeights extends IOException {}

    private static boolean validLimbo(File file) throws Exception {
        return file.isFile() && file.length() == LIMBO_SIZE && LIMBO_HASH.equals(sha256(file));
    }

    void downloadLimbo(IntConsumer progress) throws Exception {
        if (validLimbo(new File(context.getFilesDir(), LIMBO_FILE))) return;
        var connection = (HttpURLConnection) URI.create(LIMBO_URL).toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("GitHub HTTP " + code);
            try (var input = connection.getInputStream()) {
                installLimbo(input, progress);
            }
        } finally {
            connection.disconnect();
        }
    }

    void importLimbo(InputStream input) throws Exception {
        installLimbo(input, ignored -> {});
    }

    private void installLimbo(InputStream input, IntConsumer progress) throws Exception {
        File file = new File(context.getFilesDir(), LIMBO_FILE);
        File temp = File.createTempFile("limbo", ".onnx", context.getFilesDir());
        try {
            try (var output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int lastPercent = -1;
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (closed) throw new IOException("Player closed");
                    total += count;
                    if (total > LIMBO_SIZE) throw new MissingWeights();
                    output.write(buffer, 0, count);
                    int percent = (int) (100 * total / LIMBO_SIZE);
                    if (percent != lastPercent) {
                        progress.accept(percent);
                        lastPercent = percent;
                    }
                }
            }
            if (!validLimbo(temp)) throw new MissingWeights();
            Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
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
            this(ort, width, height, normalize, inputName, file == null ? null : ort.createSession(file.getAbsolutePath(), options));
        }

        Backend(OrtEnvironment ort, int width, int height, boolean normalize, String inputName, OrtSession encoder) {
            this.ort = ort;
            this.width = width;
            this.height = height;
            this.normalize = normalize;
            this.inputName = inputName;
            this.encoder = encoder;
        }

        float[] rgb(byte[] rgba, boolean planar) {
            int pixels = width * height;
            float[] rgb = new float[pixels * 3];
            int padding = contentPadding();
            int contentHeight = height - 2 * padding;
            for (int y = 0; y < height; y++) {
                // GL readback is bottom-up. Models expect top-down RGB.
                int row = clamp(y - padding, contentHeight);
                float srcY = (contentHeight - row - .5f) * CAPTURE_H / contentHeight - .5f;
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

        int contentPadding() { return 0; }
        void reset() {}
        void close() { closeSession(encoder); }
    }

    private static final class ZipLiteBackend extends Backend {
        private final boolean gpu;
        private final GpuDelegate delegate;
        private final Interpreter interpreter;
        private final ByteBuffer inputBuffer, outputBuffer;

        ZipLiteBackend(ByteBuffer file, boolean gpu) throws Exception {
            super(null, null, gpu ? 320 : 256, gpu ? 192 : 256, false, "image", null);
            this.gpu = gpu;
            inputBuffer = ByteBuffer.allocateDirect(width * height * 3 * Float.BYTES).order(ByteOrder.nativeOrder());
            outputBuffer = ByteBuffer.allocateDirect(width * height * Float.BYTES).order(ByteOrder.nativeOrder());
            delegate = gpu ? new GpuDelegate(new GpuDelegate.Options().setPrecisionLossAllowed(true)) : null;
            try {
                var options = new Interpreter.Options().setNumThreads(2);
                if (delegate != null) options.addDelegate(delegate);
                interpreter = new Interpreter(file, options);
            } catch (RuntimeException | LinkageError error) {
                if (delegate != null) delegate.close();
                throw error;
            }
        }

        @Override int contentPadding() { return gpu ? 6 : 0; }

        @Override float[] infer(byte[] rgba) {
            inputBuffer.clear();
            inputBuffer.asFloatBuffer().put(rgb(rgba, false));
            outputBuffer.clear();
            interpreter.run(inputBuffer, outputBuffer);
            // LiteRT may advance the destination to its end when it copies a tensor into it.
            outputBuffer.clear();
            var packed = outputBuffer.asFloatBuffer();
            float[] depth = new float[width * (height - 2 * contentPadding())];
            if (!gpu) {
                packed.get(depth);
                return depth;
            }
            // Each packed value quartet contains a 2x2 block. Skip six padded rows on each side.
            packed.position(width * contentPadding());
            for (int y = 0; y < height - 2 * contentPadding(); y += 2) {
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
            finally { if (delegate != null) delegate.close(); }
        }
    }

    private static final class VideoBackend extends Backend {
        private final OrtSession bootstrap, stream;
        private final List<float[][]> history = new ArrayList<>();
        private static final int[] SPATIAL = {209, 209, 60, 60, 209, 209, 836, 836};
        private static final int[] CHANNELS = {192, 192, 384, 384, 64, 64, 64, 64};

        VideoBackend(OrtEnvironment ort, OrtSession.SessionOptions options, ByteBuffer first,
                     ByteBuffer initial, ByteBuffer recurrent) throws Exception {
            super(ort, 266, 154, true, "pixels", ort.createSession(first, options));
            OrtSession start = null;
            try {
                start = ort.createSession(initial, options);
                stream = ort.createSession(recurrent, options);
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

    private byte[] normalize(float[] values, int width, int height, boolean inverse, boolean reset, boolean sharedScale) throws IOException {
        if (values.length != width * height) throw new IOException("Unexpected depth map size " + values.length);
        float[] samples = new float[(values.length + 15) / 16];
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            float d = values[i];
            if (inverse) d = Float.isFinite(d) && d > 0 ? 1 / Math.max(d, .000001f) : Float.NaN;
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
        float median = samples[count / 2];
        if (reset || high - low <= .000001f) {
            low = bottom;
            high = top;
            shotMedian = median;
        } else {
            low += .045f * (bottom - low);
            high += .045f * (top - high);
            shotMedian += .045f * (median - shotMedian);
        }
        float scale = sharedScale ? 1 : Math.min(1.7f, Math.max(.6f, (high - low) / Math.max(top - bottom, .000001f)));
        float scaleMix = sharedScale ? 1 : .4f + .6f * scale;
        if (high - low <= .000001f) {
            Arrays.fill(map, (byte) 128);
            return map;
        }
        for (int y = 0; y < MAP_H; y++) {
            int sy = clamp((MAP_H - y - 1) * height / MAP_H, height);
            for (int x = 0; x < MAP_W; x++) {
                float v = values[sy * width + x * width / MAP_W];
                if (!sharedScale) v = median + (v - median) * scaleMix + .6f * (shotMedian - median);
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
