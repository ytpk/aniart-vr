package dev.aniart.vr;

import android.app.NativeActivity;
import android.content.Context;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;
import android.view.Surface;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CinemaActivity extends NativeActivity {
    private static final int IMPORT_CATALOG = 42;
    private static final int IMPORT_LIMBO = 43;
    private DepthModels depthModels;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean frame = new AtomicBoolean();
    private final AtomicBoolean limboDownloading = new AtomicBoolean();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile List<Catalog.Series> catalog = List.of();
    private List<Catalog.Episode> demos = List.of();
    private volatile Catalog.Episode sharedEpisode;
    private volatile OnlineEpisode onlineEpisode;
    private OnlineEpisode loadingNext;
    private final ExecutorService onlinePlayback = Executors.newSingleThreadExecutor();
    private volatile PcProcessing pcProcessing;
    private volatile boolean playingFromPc;
    private final ExecutorService pcRequests = Executors.newSingleThreadExecutor();
    private volatile String notice = "";
    private volatile Snapshot snapshot = new Snapshot(null, "Выберите видео", 0, 0, false, false, 1f, 1f);
    private volatile long format;
    private CinemaPanel panel;
    private SharedPreferences preferences;
    private boolean foreground;
    private boolean playRequested;
    private boolean autoNext;
    private boolean roomSound;
    private volatile float headYaw;
    private volatile int roomInputChannels;
    private float volume;
    private float speed;
    private ExoPlayer player;
    private Catalog.Episode playing;
    private boolean playingShared;
    private String status = "";
    private SurfaceTexture texture;
    private Surface surface;
    private long timestamp;
    private int seenGeneration;

    record Snapshot(Catalog.Episode episode, String status, long position, long duration,
                    boolean playing, boolean seekable, float volume, float speed) {}
    record OnlineEpisode(Catalog.Episode episode, Callable<OnlineEpisode> next) {}

    private final Runnable tick = new Runnable() {
        private int saves;
        @Override public void run() {
            publish();
            if (++saves % 10 == 0) savePosition();
            ui.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        depthModels = new DepthModels(getApplicationContext());
        preferences = getSharedPreferences("cinema", MODE_PRIVATE);
        String pc = preferences.getString("pc_processing", "");
        if (!pc.isEmpty()) {
            try { pcProcessing = PcProcessing.parse(pc); }
            catch (IllegalArgumentException ignored) { }
        }
        autoNext = preferences.getBoolean("auto_next", true);
        roomSound = preferences.getBoolean("room_sound", false);
        volume = preferences.getFloat("volume", 1f);
        speed = preferences.getFloat("speed", 1f);
        try (var input = getAssets().open("catalog.json")) {
            demos = Catalog.Json.parse(Catalog.Json.read(input), true);
            File imported = new File(getFilesDir(), "catalog.json");
            if (imported.isFile()) {
                try {
                    var all = new ArrayList<>(new Catalog.Json().load(this));
                    all.addAll(demos);
                    catalog = Catalog.group(all);
                } catch (Exception error) {
                    catalog = Catalog.group(demos);
                    notice = "Проверьте формат catalog.json";
                }
            } else {
                catalog = Catalog.group(demos);
            }
        } catch (Exception error) {
            notice = "Ошибка загрузки каталога";
            Log.w("AniartVR", notice, error);
        }
        acceptVideoLink(getIntent());
        panel = new CinemaPanel(this, preferences);
        publish();
        super.onCreate(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        acceptVideoLink(intent);
    }

    private void acceptVideoLink(Intent intent) {
        if (intent == null) return;
        try {
            if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                acceptDirectLink(intent.getDataString(), intent.getStringExtra("title"),
                        intent.getStringArrayExtra("headers"));
            } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
                CharSequence value = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                acceptDirectLink(value == null ? null : value.toString(), null, null);
            }
        } catch (android.os.BadParcelableException | ClassCastException error) {
            notice = "Не удалось прочитать отправленную ссылку";
        }
    }

    private void acceptDirectLink(String value, String title, String[] headers) {
        var link = IncomingVideo.parse(value, headers);
        if (link == null) {
            notice = value != null && value.length() <= 8192 && KodikStreams.isEmbed(value.trim())
                    ? "Это страница Kodik. Откройте серию через каталог ani-cli или вставьте ссылку на поток"
                    : "Нужна прямая HTTPS-ссылка на .m3u8 или .mp4";
            return;
        }
        sharedEpisode = new Catalog.Episode(IncomingVideo.title(title, "Открытая ссылка"), link.host(),
                "Прямой поток", link.quality(), Uri.parse(link.url()), link.hls(), link.headers(), 0);
        notice = "";
    }

    void pasteVideoLink(String title) {
        ui.post(() -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            var clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                notice = "Буфер обмена пуст или недоступен";
                return;
            }
            var item = clip.getItemAt(0);
            String value = item.getText() != null ? item.getText().toString()
                    : item.getUri() == null ? null : item.getUri().toString();
            acceptDirectLink(value, title, null);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (player != null && playRequested) player.play();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override protected void onPause() {
        foreground = false;
        ui.removeCallbacks(tick);
        savePosition();
        if (player != null) player.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        panel.shutdown();
        pcRequests.shutdownNow();
        depthModels.shutdown();
        onlineEpisode = null;
        onlinePlayback.shutdownNow();
    }

    List<Catalog.Series> catalog() { return catalog; }
    Catalog.Episode sharedEpisode() { return sharedEpisode; }
    Snapshot snapshot() { return snapshot; }
    String notice() { return notice; }
    long positionFor(Catalog.Episode episode) { return episode == sharedEpisode ? 0 : preferences.getLong("resume:" + episode.key(), 0); }

    // Called only on the OpenXR GL thread.
    public void openTexture(int name) {
        texture = new SurfaceTexture(name);
        texture.setOnFrameAvailableListener(ignored -> frame.set(true), ui);
        Surface target = new Surface(texture);
        ui.post(() -> surface = target);
    }

    public long latch(float[] transform) {
        int currentGeneration = generation.get();
        if (currentGeneration != seenGeneration) {
            seenGeneration = currentGeneration;
            timestamp = 0;
            return -1;
        }
        long currentFormat = format;
        if (currentFormat == 0 || texture == null || !frame.getAndSet(false)) return 0;
        texture.updateTexImage();
        long next = texture.getTimestamp();
        if (next == timestamp || currentGeneration != generation.get()) return 0;
        timestamp = next;
        texture.getTransformMatrix(transform);
        return currentFormat;
    }

    public void closeTexture() {
        SurfaceTexture old = texture;
        texture = null;
        ui.post(() -> {
            releasePlayer();
            if (surface != null) surface.release();
            surface = null;
            old.release();
        });
    }

    public int renderConfig() { return panel.renderConfig(); }
    public void selectDepthModel(int model) { depthModels.select(model); }
    public boolean wantsDepthFrame() { return depthModels.wantsFrame(); }
    public void submitDepthFrame(byte[] rgba) { depthModels.submit(rgba); }
    public byte[] takeDepthMap() { return depthModels.takeMap(); }
    String depthStatus() { return depthModels.status(); }
    public boolean wantsDiagnosticDepthFrame() { return depthModels.wantsDiagnosticFrame(); }
    public void submitDiagnosticDepthFrame(byte[] frame) { depthModels.submitDiagnosticFrame(frame); }
    void testDepth(String renderer, boolean hasVideoFrame) {
        Runnable timeout = depthModels.runDiagnostics("Aniart VR " + BuildConfig.VERSION_NAME + " | " + renderer,
                hasVideoFrame, message -> notice = message);
        if (timeout != null) ui.postDelayed(timeout, 2500);
    }
    public void setHeadYaw(float yaw) { headYaw = yaw; }
    int roomInputChannels() { return roomInputChannels; }

    void setRoomSound(boolean enabled) {
        ui.post(() -> {
            if (roomSound == enabled) return;
            roomSound = enabled;
            preferences.edit().putBoolean("room_sound", enabled).apply();
            if (player != null && playing != null && player.getPlayerError() == null
                    && player.getPlaybackState() != Player.STATE_ENDED) {
                long position = player.isCurrentMediaItemSeekable() ? player.getCurrentPosition() : C.TIME_UNSET;
                startPlayer(playing, position, playRequested);
            }
        });
    }
    public void command(int command) { panel.command(command); }
    public void clickPanel(float x, float y) { panel.click(x, y); }
    public boolean uploadPanel(float x, float y, int filterStatus, int width, int height,
                            boolean depthReady, float fps, float gpuMs, float displayHz, boolean eyeMsaa) {
        return panel.upload(x, y, filterStatus, width, height, depthReady, fps, gpuMs, displayHz, eyeMsaa);
    }

    void open(Catalog.Episode episode) {
        ui.post(() -> {
            onlineEpisode = null;
            play(episode, true);
        });
    }
    void openOnline(OnlineEpisode selection) {
        ui.post(() -> playOnline(selection));
    }
    private void playOnline(OnlineEpisode selection) {
        onlineEpisode = selection;
        play(selection.episode(), true);
    }
    void toggle() { ui.post(this::togglePlayback); }
    void step(long delta) {
        ui.post(() -> {
            if (player != null) seekTo(player.getCurrentPosition() + delta);
        });
    }
    void seek(float fraction) {
        ui.post(() -> {
            if (player != null && player.getDuration() > 0) seekTo((long) (fraction * player.getDuration()));
        });
    }
    void next(Catalog.Episode current) {
        ui.post(() -> advance(current));
    }

    private void advance(Catalog.Episode current) {
        if (current == null || playing != current) return;
        OnlineEpisode selection = onlineEpisode;
        if (selection == null || !selection.episode().equals(current)) {
            Catalog.Episode next = nextAfter(current);
            if (next != null) play(next, true);
            return;
        }
        if (selection.next() == null || loadingNext == selection) return;
        loadingNext = selection;
        status = "Загрузка следующей серии…";
        publish();
        onlinePlayback.execute(() -> {
            OnlineEpisode result = null;
            String failure = "Следующая серия недоступна в этой озвучке";
            try {
                result = selection.next().call();
            } catch (Exception error) {
                failure = AniCliBrowser.errorStatus(error);
            }
            OnlineEpisode resolved = result;
            String message = failure;
            ui.post(() -> {
                if (loadingNext == selection) loadingNext = null;
                if (onlineEpisode != selection || playing != current) return;
                if (resolved != null) playOnline(resolved);
                else {
                    status = message;
                    publish();
                }
            });
        });
    }
    void setAutoNext(boolean enabled) { ui.post(() -> autoNext = enabled); }
    void changeVolume(float delta) {
        ui.post(() -> {
            volume = Math.max(0f, Math.min(1f, volume + delta));
            if (player != null) player.setVolume(volume);
            preferences.edit().putFloat("volume", volume).apply();
            publish();
        });
    }
    void changeSpeed() {
        ui.post(() -> {
            speed = speed < 1.24f ? 1.25f : speed < 1.49f ? 1.5f : speed < 1.99f ? 2f : 1f;
            if (player != null) player.setPlaybackSpeed(speed);
            preferences.edit().putFloat("speed", speed).apply();
            publish();
        });
    }

    boolean canPlayNext(Catalog.Episode current) {
        OnlineEpisode selection = onlineEpisode;
        return selection != null && selection.episode().equals(current)
                ? selection.next() != null : nextAfter(current) != null;
    }

    private Catalog.Episode nextAfter(Catalog.Episode current) {
        if (current == null) return null;
        for (Catalog.Series series : catalog) {
            if (!series.title().equals(current.title())) continue;
            boolean found = false;
            for (var section : series.episodes().entrySet()) {
                if (found) {
                    var variants = section.getValue();
                    for (Catalog.Episode variant : variants) {
                        if (variant.voice().equals(current.voice()) && variant.quality().equals(current.quality())) return variant;
                    }
                    for (Catalog.Episode variant : variants) {
                        if (variant.voice().equals(current.voice())) return variant;
                    }
                    return variants.get(0);
                }
                found = section.getKey().equals(current.episode());
            }
            break;
        }
        return null;
    }

    private void seekTo(long position) {
        if (player != null && player.isCurrentMediaItemSeekable()) {
            generation.incrementAndGet();
            player.seekTo(Math.max(0, Math.min(position, player.getDuration())));
            publish();
        }
    }

    private void savePosition() {
        if (player == null || playing == null || playingShared
                || !player.isCurrentMediaItemSeekable()) return;
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        long resume = duration > 0 && duration - position <= 5000 ? 0 : position;
        preferences.edit().putLong("resume:" + playing.key(), resume).apply();
    }

    private void releasePlayer() {
        if (player != null) {
            savePosition();
            player.release();
            player = null;
        }
        frame.set(false);
        format = 0;
        generation.incrementAndGet();
        roomInputChannels = 0;
        playing = null;
        playingShared = false;
        playingFromPc = false;
        playRequested = false;
    }

    private void togglePlayback() {
        if (playing == null) return;
        if (player == null) {
            if (!status.equals("Подготовка потока на ПК")) play(playing, false);
            return;
        }
        if (player.getPlayerError() != null || player.getPlaybackState() == Player.STATE_ENDED) {
            play(playing, false);
            return;
        }
        playRequested = !playRequested;
        player.setPlayWhenReady(foreground && playRequested);
        publish();
    }

    private void play(Catalog.Episode episode, boolean resume) {
        if (surface == null) return;
        if (resume && player != null && playing != null && playing.equals(episode)
                && player.getPlaybackState() != Player.STATE_ENDED && player.getPlayerError() == null) {
            playRequested = true;
            player.setPlayWhenReady(foreground);
            publish();
            return;
        }
        startPlayer(episode, resume ? positionFor(episode) : C.TIME_UNSET, true);
    }

    private void startPlayer(Catalog.Episode episode, long position, boolean autoPlay) {
        if (surface == null) return;
        releasePlayer();
        PcProcessing pc = pcProcessing;
        String scheme = episode.url().getScheme();
        if (pc == null || episode.stereo() != 0 || !("https".equals(scheme) || "http".equals(scheme))) {
            openPlayer(episode, position, autoPlay, episode.url().toString(), episode.mimeType(), episode.headers(), false);
            return;
        }
        playing = episode;
        playingFromPc = true;
        status = "Подготовка потока на ПК";
        publish();
        int request = generation.get();
        pcRequests.execute(() -> {
            try {
                String stream = pc.prepare(episode);
                ui.post(() -> {
                    if (request == generation.get() && surface != null && !isDestroyed()) {
                        openPlayer(episode, C.TIME_UNSET, autoPlay, stream, "video/mp2t", pc.headers(), true);
                    }
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (request != generation.get() || isDestroyed()) return;
                    status = "ПК: " + (error instanceof java.io.IOException && error.getMessage() != null
                            && error.getMessage().matches("HTTP [0-9]+") ? error.getMessage() : "сервер недоступен");
                    publish();
                });
            }
        });
    }

    private void openPlayer(Catalog.Episode episode, long position, boolean autoPlay, String url, String mime,
                            Map<String, String> headers, boolean fromPc) {
        var http = new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers);
        if (fromPc) http.setReadTimeoutMs(60000);
        playingFromPc = fromPc;
        var data = new DefaultDataSource.Factory(this, http);
        ExoPlayer.Builder builder = roomSound ? new ExoPlayer.Builder(this, roomRenderers()) : new ExoPlayer.Builder(this);
        ExoPlayer active = builder.setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();
        player = active;
        playing = episode;
        playingShared = episode == sharedEpisode;
        playRequested = autoPlay;
        status = "Загрузка";
        active.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setSpatializationBehavior(roomSound ? C.SPATIALIZATION_BEHAVIOR_NEVER : C.SPATIALIZATION_BEHAVIOR_AUTO)
                .build(), true);
        active.setVideoSurface(surface);
        active.setVolume(volume);
        active.setPlaybackSpeed(speed);
        active.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize size) {
                if (active != player || size.width <= 0 || size.height <= 0) return;
                long scaledWidth = Math.max(1, Math.round(size.width * size.pixelWidthHeightRatio));
                format = ((long) Math.min(size.width, 32767) << 48) | ((long) Math.min(size.height, 65535) << 32)
                        | ((long) Math.min(scaledWidth, 65535) << 16) | Math.min(size.height, 65535);
            }

            @Override public void onEvents(Player source, Player.Events events) {
                if (active == player) publish();
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (active != player) return;
                status = switch (state) {
                    case Player.STATE_BUFFERING -> "Буферизация";
                    case Player.STATE_READY -> "";
                    case Player.STATE_ENDED -> "Видео завершено";
                    default -> "Загрузка";
                };
                if (state == Player.STATE_ENDED && autoNext && foreground) {
                    ui.post(() -> {
                        if (active == player && active.getPlaybackState() == Player.STATE_ENDED) advance(episode);
                    });
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (active != player) return;
                status = "Ошибка воспроизведения";
                for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
                    if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException http) {
                        status += " · HTTP " + http.responseCode;
                        break;
                    }
                }
                Log.w("AniartVR", error.getErrorCodeName());
                publish();
            }
        });
        active.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime event, String name,
                    long initializedTimestampMs, long durationMs) {
                Log.i("AniartVR", "Video decoder " + name);
            }
        });
        MediaItem item = new MediaItem.Builder().setUri(url).setMimeType(mime).build();
        active.setMediaItem(item, position > 0 ? position : C.TIME_UNSET);
        active.prepare();
        active.setPlayWhenReady(foreground && autoPlay);
        publish();
    }

    private DefaultRenderersFactory roomRenderers() {
        return new DefaultRenderersFactory(this) {
            @Override protected AudioSink buildAudioSink(Context context, boolean floatOutput, boolean speedParams) {
                return new DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(speedParams)
                        .setAudioProcessors(new AudioProcessor[]{
                                new RoomAudioProcessor(() -> headYaw, channels -> roomInputChannels = channels)
                        }).build();
            }
        };
    }

    private void publish() {
        snapshot = new Snapshot(playing, status, player == null ? 0 : player.getCurrentPosition(),
                player == null ? 0 : Math.max(0, player.getDuration()),
                player != null && playRequested && player.getPlaybackState() != Player.STATE_ENDED
                        && player.getPlayerError() == null,
                player != null && player.isCurrentMediaItemSeekable(), volume, speed);
    }

    boolean pcEnabled() { return pcProcessing != null; }
    boolean playingFromPc() { return playingFromPc; }

    void togglePcProcessing() {
        ui.post(() -> {
            if (pcProcessing != null) {
                pcProcessing = null;
                preferences.edit().remove("pc_processing").apply();
                notice = "Следующая серия откроется на Quest";
                return;
            }
            try {
                ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                var clip = clipboard == null ? null : clipboard.getPrimaryClip();
                String value = clip == null ? "" : clip.getItemAt(0).coerceToText(this).toString().trim();
                pcProcessing = PcProcessing.parse(value);
                preferences.edit().putString("pc_processing", value).apply();
                notice = "Следующая серия откроется через ПК";
            } catch (RuntimeException error) {
                notice = "Скопируйте http://IP:8765#ТОКЕН";
            }
        });
    }

    void pickCatalog() {
        pickFile(IMPORT_CATALOG, "Нет файлового менеджера. Загрузите catalog.json через ADB");
    }

    void pickLimbo() {
        pickFile(IMPORT_LIMBO, "Загрузите " + DepthModels.LIMBO_FILE + " через ADB");
    }

    void downloadLimbo() {
        if (!limboDownloading.compareAndSet(false, true)) return;
        notice = "Подключение к GitHub…";
        new Thread(() -> {
            try {
                depthModels.downloadLimbo(percent -> notice = "Загрузка Limbo V3 · " + percent + "%");
                generation.incrementAndGet();
                notice = "Limbo V3 готов";
            } catch (Exception error) {
                notice = error instanceof java.net.SocketTimeoutException
                        ? "GitHub не ответил вовремя. Повторите или выберите файл"
                        : "Не удалось загрузить Limbo V3. Повторите или выберите файл";
                Log.w("AniartDepth", "Download Limbo V3", error);
            } finally {
                limboDownloading.set(false);
            }
        }, "Limbo model download").start();
    }

    private void pickFile(int request, String errorText) {
        ui.post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, request);
            } catch (RuntimeException error) {
                notice = errorText;
            }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        var uri = data.getData();
        if (requestCode == IMPORT_LIMBO) {
            new Thread(() -> {
                try (var stream = getContentResolver().openInputStream(uri)) {
                    depthModels.importLimbo(stream);
                    generation.incrementAndGet();
                    notice = "Limbo V3 импортирован";
                } catch (Exception error) {
                    notice = "Нужен оригинал " + DepthModels.LIMBO_FILE;
                    Log.w("AniartDepth", "Import Limbo model", error);
                }
            }, "Depth model import").start();
            return;
        }
        if (requestCode != IMPORT_CATALOG) return;
        new Thread(() -> {
            try (var stream = getContentResolver().openInputStream(uri)) {
                byte[] bytes = Catalog.Json.read(stream);
                var all = new ArrayList<>(Catalog.Json.parse(bytes, false));
                int imported = all.size();
                all.addAll(demos);
                List<Catalog.Series> groups = Catalog.group(all);
                AtomicFile file = new AtomicFile(new File(getFilesDir(), "catalog.json"));
                FileOutputStream output = file.startWrite();
                try {
                    output.write(bytes);
                    file.finishWrite(output);
                } catch (Exception error) {
                    file.failWrite(output);
                    throw error;
                }
                catalog = groups;
                notice = "Импортировано записей: " + imported;
            } catch (Exception error) {
                notice = "Проверьте формат catalog.json";
                Log.w("AniartVR", notice, error);
            }
        }, "Catalog import").start();
    }
}
