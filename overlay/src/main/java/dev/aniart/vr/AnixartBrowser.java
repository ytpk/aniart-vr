package dev.aniart.vr;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class AnixartBrowser {
    enum Kind { TITLES, OPTIONS }

    record Row(String label, Runnable open) {}

    static final class Screen {
        final Kind kind;
        final String title;
        final String subtitle;
        List<AnixartCatalog.Title> titles = List.of();
        List<Row> rows = List.of();
        Runnable retry;
        String status = "";
        int page;
        int remotePage;
        boolean more;
        boolean loading;

        Screen(Kind kind, String title, String subtitle) {
            this.kind = kind;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private final CinemaActivity activity;
    private final Consumer<Catalog.Episode> play;
    private final AnixartCatalog api = new AnixartCatalog();
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final ConcurrentLinkedQueue<Runnable> updates = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Screen> history = new ArrayDeque<>();
    private final LruCache<String, Bitmap> posters = new LruCache<>(32);
    private final Set<String> pending = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private Screen screen;
    private String query = "";

    AnixartBrowser(CinemaActivity activity, Consumer<Catalog.Episode> play) {
        this.activity = activity;
        this.play = play;
    }

    Screen screen() { return screen; }
    void start() { if (screen == null) search(""); }

    boolean pump() {
        boolean changed = false;
        for (Runnable update; (update = updates.poll()) != null;) {
            update.run();
            changed = true;
        }
        return changed;
    }

    void shutdown() {
        network.shutdownNow();
        images.shutdownNow();
    }

    void search(String value) {
        query = value.trim();
        history.clear();
        openTitlePage(0);
    }

    void openTitlePage(int page) {
        Screen next = new Screen(Kind.TITLES, query.isEmpty() ? "Смотрят сейчас" : "Поиск Anixart", query);
        next.remotePage = page;
        screen = next;
        load(next, () -> api.titles(next.subtitle, page), titles -> {
            next.titles = titles;
            next.more = titles.size() >= 25;
            if (titles.isEmpty()) next.status = "Тайтлы не найдены";
        });
    }

    boolean back() {
        if (history.isEmpty()) return false;
        screen = history.pop();
        return true;
    }

    private Screen push(String title, String breadcrumb) {
        Screen next = new Screen(Kind.OPTIONS, title, breadcrumb);
        history.push(screen);
        screen = next;
        return next;
    }

    void select(AnixartCatalog.Title title) {
        Screen next = push(title.name(), "Выберите озвучку");
        if (title.blocked()) {
            next.status = "Anixart ограничил просмотр этого тайтла";
            return;
        }
        load(next, () -> api.voices(title), voices -> {
            next.rows = voices.stream().map(voice -> new Row(option(voice), () -> sources(title, voice))).collect(java.util.stream.Collectors.toList());
            if (next.rows.isEmpty()) next.status = "Для этого тайтла пока нет озвучек";
        });
    }

    private void sources(AnixartCatalog.Title title, AnixartCatalog.Option voice) {
        Screen next = push(title.name(), voice.name() + "  ·  Выберите источник");
        load(next, () -> api.sources(title, voice), sources -> {
            next.rows = sources.stream().map(source -> new Row(option(source),
                    () -> episodes(title, voice, source))).collect(java.util.stream.Collectors.toList());
            if (next.rows.isEmpty()) next.status = "Источники недоступны";
        });
    }

    private void episodes(AnixartCatalog.Title title, AnixartCatalog.Option voice, AnixartCatalog.Option source) {
        Screen next = push(title.name(), voice.name() + "  ·  " + source.name() + "  ·  Выберите серию");
        load(next, () -> api.episodes(title, voice, source), episodes -> {
            next.rows = episodes.stream().map(episode -> new Row(episode.name(),
                    () -> episodeLink(title, voice, source, episode))).collect(java.util.stream.Collectors.toList());
            if (next.rows.isEmpty()) next.status = "Серии недоступны";
        });
    }

    private record Playback(AnixartCatalog.Link link, List<KodikStreams.Stream> streams, String status) {}

    private void episodeLink(AnixartCatalog.Title title, AnixartCatalog.Option voice,
                             AnixartCatalog.Option source, AnixartCatalog.Episode episode) {
        Screen next = push(title.name(), episode.name() + "  ·  " + voice.name() + "  ·  " + source.name());
        loadEpisode(next, title, voice, source, episode);
    }

    private void loadEpisode(Screen next, AnixartCatalog.Title title, AnixartCatalog.Option voice,
                             AnixartCatalog.Option source, AnixartCatalog.Episode episode) {
        load(next, () -> {
            var link = api.resolve(title, source, episode);
            if (!link.embedded()) return new Playback(link, List.of(), "");
            if (!KodikStreams.isEmbed(link.url())) return new Playback(link, List.of(), "Источник предоставляет веб-плеер");
            try {
                return new Playback(link, new KodikStreams().resolve(link.url()), "");
            } catch (java.io.IOException error) {
                String message = error.getMessage();
                return new Playback(link, List.of(), message != null && message.startsWith("Kodik")
                        ? message : "Не удалось получить поток Kodik");
            } catch (Exception error) {
                return new Playback(link, List.of(), "Не удалось прочитать потоки Kodik");
            }
        }, result -> {
            var link = result.link();
            if (!link.embedded()) {
                next.rows = List.of(new Row("Смотреть в VR", () -> play.accept(new Catalog.Episode(title.name(),
                        episode.name(), voice.name(), source.name(), Uri.parse(link.url()), link.hls(),
                        KodikStreams.headersForCdn(link.url()), 0))));
                return;
            }

            var rows = new java.util.ArrayList<Row>();
            for (var stream : result.streams()) {
                String quality = stream.height() + "p";
                rows.add(new Row("Смотреть в VR  ·  " + quality, () -> play.accept(new Catalog.Episode(
                        title.name(), episode.name(), voice.name(), quality, Uri.parse(stream.url()),
                        stream.hls(), stream.headers(), 0))));
            }
            if (KodikStreams.isEmbed(link.url())) {
                rows.add(new Row("Вставить прямой поток", () -> activity.pasteVideoLink(title.name())));
                rows.add(new Row("Обновить потоки Kodik", () -> loadEpisode(next, title, voice, source, episode)));
            }
            rows.add(new Row("Открыть веб-плеер " + source.name(), () -> openEmbedded(next, title, source, episode)));
            next.rows = List.copyOf(rows);
            next.page = 0;
            next.status = result.status();
        });
    }

    private void openEmbedded(Screen next, AnixartCatalog.Title title,
                              AnixartCatalog.Option source, AnixartCatalog.Episode episode) {
        load(next, () -> api.resolve(title, source, episode), link -> {
            next.status = "Источник предоставляет веб-плеер";
            if (screen == next) activity.openEmbedded(link.url());
        });
    }

    private static String option(AnixartCatalog.Option value) {
        return value.name() + (value.episodes() > 0 ? "  ·  серий " + value.episodes() : "");
    }

    private <T> void load(Screen target, Callable<T> task, Consumer<T> accept) {
        target.loading = true;
        target.status = "Загрузка Anixart…";
        target.retry = null;
        network.execute(() -> {
            try {
                T result = task.call();
                updates.add(() -> {
                    target.loading = false;
                    target.status = "";
                    accept.accept(result);
                });
            } catch (Exception error) {
                String message = error.getMessage();
                updates.add(() -> {
                    target.loading = false;
                    target.status = message != null && message.startsWith("Anixart HTTP ")
                            ? message : "Не удалось загрузить данные Anixart";
                    target.retry = () -> load(target, task, accept);
                });
            }
        });
    }

    Bitmap poster(String url) {
        if (url.isEmpty()) return null;
        Bitmap cached = posters.get(url);
        if (cached != null || failed.contains(url) || pending.size() >= 16 || !pending.add(url)) return cached;
        images.execute(() -> {
            Bitmap decoded = null;
            try {
                var connection = (HttpURLConnection) AnixartCatalog.posterUrl(url).openConnection();
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(false);
                try (var input = connection.getInputStream()) {
                    byte[] data = AnixartCatalog.read(input, 1024 * 1024);
                    var options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, options);
                    if (options.outWidth > 0 && options.outHeight > 0
                            && options.outWidth <= 4096 && options.outHeight <= 4096) {
                        while (Math.max(options.outWidth, options.outHeight) / Math.max(1, options.inSampleSize) > 384) {
                            options.inSampleSize = Math.max(1, options.inSampleSize) * 2;
                        }
                        options.inJustDecodeBounds = false;
                        decoded = BitmapFactory.decodeStream(new ByteArrayInputStream(data), null, options);
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Exception error) {
                // The card keeps its fallback artwork.
            }
            Bitmap result = decoded;
            updates.add(() -> {
                pending.remove(url);
                if (result != null) posters.put(url, result);
                else {
                    if (failed.size() >= 128) failed.clear();
                    failed.add(url);
                }
            });
        });
        return null;
    }
}
