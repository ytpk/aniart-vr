package dev.aniart.vr;

import android.app.NativeActivity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.opengl.GLUtils;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CinemaActivity extends NativeActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean frame = new AtomicBoolean();
    private List<Catalog.Episode> episodes = List.of();
    private volatile String status = "Выберите видео";
    private volatile long format;
    private int selection;
    private int playing = -1;
    private boolean foreground;
    private boolean playRequested;
    private ExoPlayer player;
    private SurfaceTexture texture;
    private Surface surface;
    private long timestamp;

    @Override public void onCreate(Bundle state) {
        try {
            episodes = new Catalog.Json().load(this);
            if (episodes.isEmpty()) status = "Каталог пуст";
        } catch (Exception error) {
            status = "Ошибка чтения каталога";
            Log.w("AniartVR", status, error);
        }
        super.onCreate(state);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (player != null && playRequested) player.play();
    }

    @Override protected void onPause() {
        foreground = false;
        if (player != null) player.pause();
        super.onPause();
    }

    // These methods are called from the OpenXR GL thread.
    public void openTexture(int name) {
        texture = new SurfaceTexture(name);
        texture.setOnFrameAvailableListener(ignored -> frame.set(true), ui);
        Surface target = new Surface(texture);
        ui.post(() -> surface = target);
    }

    public long latch(float[] transform) {
        if (texture == null || !frame.getAndSet(false)) return 0;
        texture.updateTexImage();
        long next = texture.getTimestamp();
        if (next == timestamp) return 0;
        timestamp = next;
        texture.getTransformMatrix(transform);
        return format;
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

    public void command(int command) {
        if (episodes.isEmpty()) return;
        if (command == -1 || command == 1) {
            selection = Math.floorMod(selection + command, episodes.size());
        } else if (command == 0) {
            int requested = selection;
            ui.post(() -> play(requested));
        }
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
        format = 0;
        playing = -1;
        playRequested = false;
    }

    private void play(int index) {
        if (surface == null) return;
        if (playing == index && player != null && player.getPlayerError() == null
                && player.getPlaybackState() != Player.STATE_ENDED) {
            playRequested = !playRequested;
            player.setPlayWhenReady(foreground && playRequested);
            status = playRequested ? "Воспроизведение" : "Пауза";
            return;
        }
        releasePlayer();
        Catalog.Episode episode = episodes.get(index);
        var http = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(episode.headers());
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http)).build();
        player.setVideoSurface(surface);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize size) {
                int width = Math.round(size.width * size.pixelWidthHeightRatio);
                format = ((long) width << 32) | (size.height & 0xffffffffL);
            }

            @Override public void onPlaybackStateChanged(int state) {
                status = switch (state) {
                    case Player.STATE_BUFFERING -> "Буферизация";
                    case Player.STATE_READY -> playRequested ? "Воспроизведение" : "Пауза";
                    case Player.STATE_ENDED -> "Видео завершено";
                    default -> "Ожидание";
                };
            }

            @Override public void onPlayerError(PlaybackException error) {
                status = "Ошибка воспроизведения • " + error.getErrorCodeName();
                Log.w("AniartVR", status);
            }
        });
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime event, String name,
                    long initializedTimestampMs, long durationMs) {
                Log.i("AniartVR", "Video decoder " + name);
            }
        });
        player.setMediaItem(new MediaItem.Builder().setUri(episode.url())
                .setMimeType(episode.hls() ? MimeTypes.APPLICATION_M3U8 : MimeTypes.VIDEO_MP4).build());
        playing = index;
        playRequested = true;
        status = "Загрузка";
        player.prepare();
        player.setPlayWhenReady(foreground);
    }

    public void uploadPanel(boolean fast, float fps, float gpuMs) {
        Bitmap panel = Bitmap.createBitmap(1024, 320, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(panel);
        canvas.drawColor(Color.rgb(21, 26, 43));
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        line(canvas, paint, "Aniart VR  /  Каталог", 51, 26, 0xffb2a3ff);
        if (!episodes.isEmpty()) {
            Catalog.Episode episode = episodes.get(selection);
            line(canvas, paint, episode.title(), 113, 46, Color.WHITE);
            line(canvas, paint, episode.episode() + "   ·   " + episode.voice() + "   ·   "
                    + episode.quality() + "                         " + (selection + 1) + "/" + episodes.size(),
                    157, 26, 0xffd7dcef);
        }
        String timing = gpuMs < 0 ? "" : String.format(java.util.Locale.ROOT, "   GPU Anime4K %.1f ms", gpuMs);
        line(canvas, paint, status + "   /   " + (fast ? "Anime4K CNN x2 S" : "2D")
                + String.format(java.util.Locale.ROOT, "   %.0f FPS", fps) + timing, 227, 24, 0xffb2a3ff);
        line(canvas, paint, "Курки  ← →     A  Смотреть / пауза     B  Anime4K     X  Каталог     Y  Экран",
                286, 24, Color.WHITE);
        GLUtils.texImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, panel, 0);
        panel.recycle();
    }

    private static void line(Canvas canvas, TextPaint paint, String value, int y, int size, int color) {
        paint.setTextSize(size);
        paint.setColor(color);
        canvas.drawText(TextUtils.ellipsize(value, paint, 960, TextUtils.TruncateAt.END).toString(), 32, y, paint);
    }
}
