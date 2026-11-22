package dev.aniart.vr;

import android.app.NativeActivity;
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
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CinemaActivity extends NativeActivity {
    private static final int IMPORT_CATALOG = 42;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean frame = new AtomicBoolean();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile List<Catalog.Series> catalog = List.of();
    private List<Catalog.Episode> demos = List.of();
    private volatile Catalog.Episode sharedEpisode;
    private volatile String notice = "";
    private volatile Snapshot snapshot = new Snapshot(null, "Выберите видео", 0, 0, false, false, 1f, 1f);
    private volatile long format;
    private CinemaPanel panel;
    private SharedPreferences preferences;
    private boolean foreground;
    private boolean playRequested;
    private boolean autoNext;
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

    private final Runnable tick = new Runnable() {
        private int saves;
        @Override public void run() {
            publish();
            if (++saves % 10 == 0) savePosition();
            ui.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        preferences = getSharedPreferences("cinema", MODE_PRIVATE);
        autoNext = preferences.getBoolean("auto_next", true);
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
        String raw;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            raw = intent.getDataString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence value = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            raw = value == null ? null : value.toString().trim();
        } else {
            return;
        }
        if (raw != null && raw.length() <= 8192 && raw.chars().noneMatch(Character::isWhitespace)) {
            Uri uri = Uri.parse(raw);
            String path = uri.getPath();
            if ("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null && path != null) {
                String lower = path.toLowerCase(Locale.ROOT);
                boolean hls = lower.endsWith(".m3u8");
                if (hls || lower.endsWith(".mp4")) {
                    sharedEpisode = new Catalog.Episode("Открытая ссылка", uri.getHost(), "Оригинал",
                            hls ? "HLS" : "MP4", uri, hls, Map.of(), 0);
                    return;
                }
            }
        }
        notice = "Нужна прямая HTTPS-ссылка на .m3u8 или .mp4";
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
    }

    void openEmbedded(String url) {
        ui.post(() -> {
            try {
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                view.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(view);
                notice = "";
            } catch (android.content.ActivityNotFoundException error) {
                notice = "На устройстве нет приложения для открытия веб-плеера";
            }
        });
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
    public void command(int command) { panel.command(command); }
    public void clickPanel(float x, float y) { panel.click(x, y); }
    public void uploadPanel(float x, float y, int filterStatus, int width, int height,
                            boolean depthReady, float fps, float gpuMs) {
        panel.upload(x, y, filterStatus, width, height, depthReady, fps, gpuMs);
    }

    void open(Catalog.Episode episode) { ui.post(() -> play(episode, true)); }
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
        ui.post(() -> {
            Catalog.Episode next = nextAfter(current);
            if (next != null) play(next, true);
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

    boolean canPlayNext(Catalog.Episode current) { return nextAfter(current) != null; }

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
        playing = null;
        playingShared = false;
        playRequested = false;
    }

    private void togglePlayback() {
        if (player == null || playing == null) return;
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
        releasePlayer();
        var http = new DefaultHttpDataSource.Factory().setDefaultRequestProperties(episode.headers());
        var data = new DefaultDataSource.Factory(this, http);
        ExoPlayer active = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();
        player = active;
        playing = episode;
        playingShared = episode == sharedEpisode;
        playRequested = true;
        status = "Загрузка";
        active.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
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
                    Catalog.Episode next = nextAfter(episode);
                    if (next != null) ui.post(() -> {
                        if (active == player && active.getPlaybackState() == Player.STATE_ENDED) play(next, true);
                    });
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (active != player) return;
                status = "Ошибка воспроизведения";
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
        MediaItem item = new MediaItem.Builder().setUri(episode.url())
                .setMimeType(episode.hls() ? MimeTypes.APPLICATION_M3U8 : MimeTypes.VIDEO_MP4).build();
        long position = resume ? positionFor(episode) : 0;
        active.setMediaItem(item, position > 0 ? position : C.TIME_UNSET);
        active.prepare();
        active.setPlayWhenReady(foreground);
        publish();
    }

    private void publish() {
        snapshot = new Snapshot(playing, status, player == null ? 0 : player.getCurrentPosition(),
                player == null ? 0 : Math.max(0, player.getDuration()),
                player != null && playRequested && player.getPlaybackState() != Player.STATE_ENDED
                        && player.getPlayerError() == null,
                player != null && player.isCurrentMediaItemSeekable(), volume, speed);
    }

    void pickCatalog() {
        ui.post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, IMPORT_CATALOG);
            } catch (RuntimeException error) {
                notice = "Нет файлового менеджера. Загрузите catalog.json через ADB";
            }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_CATALOG || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        var uri = data.getData();
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
