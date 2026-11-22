package dev.aniart.vr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

final class AniCliBrowser {
    enum Kind { PROVIDERS, TITLES, OPTIONS }

    record Row(String label, Runnable open) {}
    record Provider(String id, String name) {}
    record Title(String name, String poster, Provider provider, String key, PyObject item) {}
    record Episode(String number, String name, PyObject item) {}
    record Voice(String name, String host, PyObject item) {
        String label() { return host.isEmpty() ? name : name + "  ·  " + host; }
    }
    record Stream(int height, String mime, String url, Map<String, String> headers) {
        String quality() { return height > 0 ? height + "p" : "Авто"; }
    }

    static final class Screen {
        final Kind kind;
        final String title;
        final String subtitle;
        final Provider provider;
        List<Title> titles = List.of();
        List<Row> rows = List.of();
        Runnable retry;
        String status = "";
        int page;
        boolean loading;

        Screen(Kind kind, String title, String subtitle, Provider provider) {
            this.kind = kind;
            this.title = title;
            this.subtitle = subtitle;
            this.provider = provider;
        }
    }

    private final Context context;
    private final Consumer<CinemaActivity.OnlineEpisode> play;
    private final ExecutorService network = Executors.newFixedThreadPool(4);
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final ConcurrentLinkedQueue<Runnable> updates = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Screen> history = new ArrayDeque<>();
    private final LruCache<String, Bitmap> posters = new LruCache<>(32);
    private final Set<String> pending = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private Screen screen;

    AniCliBrowser(CinemaActivity activity, Consumer<CinemaActivity.OnlineEpisode> play) {
        context = activity.getApplicationContext();
        this.play = play;
    }

    Screen screen() { return screen; }
    boolean canGoBack() { return !history.isEmpty(); }

    void start() {
        if (screen != null) return;
        var next = new Screen(Kind.PROVIDERS, "Источники ani-cli", "Выберите каталог", null);
        screen = next;
        load(next, this::providers, items -> next.rows = items.stream()
                .map(item -> new Row(item.name(), () -> openTitles(item, ""))).collect(Collectors.toList()));
    }

    private PyObject bridge() {
        synchronized (Python.class) {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(context));
        }
        return Python.getInstance().getModule("anicli_bridge");
    }

    private List<Provider> providers() {
        return bridge().callAttr("providers").asList().stream().map(PyObject::asList).map(value ->
                new Provider(value.get(0).toString(), value.get(1).toString())).collect(Collectors.toList());
    }

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
        if (screen != null) openTitles(screen.provider, value.trim());
    }

    private void openTitles(Provider provider, String query) {
        if (provider == null && query.isEmpty()) {
            if (screen.kind == Kind.TITLES) back();
            return;
        }
        var next = new Screen(Kind.TITLES, provider == null ? "Все источники" : provider.name(), query, provider);
        if (screen.kind == Kind.TITLES) screen = next;
        else push(next);
        if (provider == null) searchAll(next);
        else load(next, () -> titles(provider, query), titles -> {
            next.titles = titles;
            if (titles.isEmpty()) next.status = "Тайтлы не найдены или каталог недоступен";
        });
    }

    private List<Title> titles(Provider provider, String query) {
        return bridge().callAttr("titles", provider.id(), query).asList().stream().map(PyObject::asList).map(value ->
                new Title(value.get(0).toString(), value.get(1).toString(), provider, value.get(2).toString(), value.get(3))).collect(Collectors.toList());
    }

    private void searchAll(Screen target) {
        target.titles = List.of();
        target.retry = null;
        load(target, this::providers, providers -> {
            int[] pendingProviders = {providers.size()};
            int[] failures = {0};
            target.loading = true;
            target.status = "Поиск по источникам…";
            for (Provider provider : providers) network.execute(() -> {
                try {
                    var matches = titles(provider, target.subtitle);
                    updates.add(() -> {
                        var combined = new ArrayList<>(target.titles);
                        combined.addAll(matches);
                        target.titles = List.copyOf(combined);
                        finishSearch(target, pendingProviders, failures[0]);
                    });
                } catch (Exception error) {
                    updates.add(() -> finishSearch(target, pendingProviders, ++failures[0]));
                }
            });
        });
    }

    private void finishSearch(Screen target, int[] pendingProviders, int failures) {
        if (--pendingProviders[0] > 0) return;
        target.loading = false;
        if (target.titles.isEmpty()) {
            target.status = failures > 0 ? "Нет результатов. Часть источников недоступна" : "Тайтлы не найдены";
            if (failures > 0) target.retry = () -> searchAll(target);
        } else {
            target.status = failures > 0 ? "Недоступно источников: " + failures : "";
        }
    }

    boolean back() {
        if (!canGoBack()) return false;
        screen = history.pop();
        return true;
    }

    private Screen push(Screen next) {
        history.push(screen);
        screen = next;
        return next;
    }

    private Screen options(Title title, String subtitle) {
        return push(new Screen(Kind.OPTIONS, title.name(), subtitle, title.provider()));
    }

    void select(Title title) {
        var next = options(title, title.provider().name() + "  ·  Выберите серию");
        load(next, () -> bridge().callAttr("episodes", title.item()).asList().stream().map(PyObject::asList).map(value ->
                new Episode(value.get(0).toString(), value.get(1).toString(), value.get(2))).collect(Collectors.toList()), episodes -> {
            var rows = new ArrayList<Row>();
            for (int index = 0; index < episodes.size(); index++) {
                int selected = index;
                Episode episode = episodes.get(index);
                String label = episode.number();
                if (!episode.name().trim().isEmpty() && !episode.name().equalsIgnoreCase("Episode")
                        && !episode.name().equalsIgnoreCase(episode.number())) label += "  ·  " + episode.name();
                rows.add(new Row(label, () -> dubbers(title, episodes, selected)));
            }
            next.rows = List.copyOf(rows);
            if (rows.isEmpty()) next.status = "Серии недоступны";
        });
    }

    private List<Voice> voices(Episode episode) {
        return bridge().callAttr("voices", episode.item()).asList().stream().map(PyObject::asList).map(value ->
                new Voice(value.get(0).toString(), value.get(1).toString(), value.get(2))).collect(Collectors.toList());
    }

    private void dubbers(Title title, List<Episode> episodes, int index) {
        var next = options(title, episodes.get(index).number() + "  ·  Выберите озвучку");
        load(next, () -> voices(episodes.get(index)), items -> {
            next.rows = items.stream().map(item -> new Row(item.label(),
                    () -> qualities(title, episodes, index, item))).collect(Collectors.toList());
            if (items.isEmpty()) next.status = "Источник не вернул озвучки для этой серии";
        });
    }

    private List<Stream> streams(Voice voice) {
        var items = new ArrayList<Stream>();
        for (PyObject item : bridge().callAttr("streams", voice.item()).asList()) {
            var value = item.asList();
            String url = value.get(2).toString();
            var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(KodikStreams.headersForCdn(url));
            value.get(3).asMap().forEach((name, data) -> headers.put(name.toString(), data.toString()));
            items.add(new Stream(value.get(0).toInt(), value.get(1).toString(), url, Map.copyOf(headers)));
        }
        if (items.stream().noneMatch(item -> item.height() == 720)) {
            var base = items.stream()
                    .filter(item -> item.height() == 480 && Kodik720.candidateUrl(item.url()) != null)
                    .findFirst();
            if (base.isPresent()) {
                Stream item = base.get();
                var variant = Kodik720.check(item.url(), item.headers(), Kodik720::isPlaylist);
                if (variant != null) {
                    items.add(0, new Stream(720, "application/x-mpegURL", variant.url(), variant.headers()));
                }
            }
        }
        return items;
    }

    private void qualities(Title title, List<Episode> episodes, int index, Voice voice) {
        var next = options(title, episodes.get(index).number() + "  ·  " + voice.label());
        loadQualities(next, title, episodes, index, voice);
    }

    private void loadQualities(Screen next, Title title, List<Episode> episodes, int index, Voice voice) {
        load(next, () -> streams(voice), items -> {
            var rows = new ArrayList<Row>();
            for (Stream item : items) {
                String format = switch (item.mime()) {
                    case "application/x-mpegURL" -> "HLS";
                    case "application/dash+xml" -> "DASH";
                    case "video/webm" -> "WebM";
                    default -> "MP4";
                };
                rows.add(new Row("Смотреть  ·  " + item.quality() + "  ·  " + format,
                        () -> play.accept(selection(title, episodes, index, voice, item))));
            }
            next.rows = List.copyOf(rows);
            if (items.isEmpty()) {
                next.status = "Озвучка не вернула доступный видеопоток";
                next.retry = () -> loadQualities(next, title, episodes, index, voice);
            }
        });
    }

    private CinemaActivity.OnlineEpisode selection(Title title, List<Episode> episodes, int index,
                                                  Voice voice, Stream stream) {
        String origin = title.provider().id() + ":" + title.key();
        var video = new Catalog.Episode(title.name(), episodes.get(index).number(), voice.name(),
                stream.quality(), Uri.parse(stream.url()), stream.mime(), stream.headers(), 0,
                origin + "\n" + voice.host());
        Callable<CinemaActivity.OnlineEpisode> next = index + 1 < episodes.size()
                ? () -> nextEpisode(title, episodes, index + 1, voice, stream)
                : null;
        return new CinemaActivity.OnlineEpisode(video, next);
    }

    private CinemaActivity.OnlineEpisode nextEpisode(Title title, List<Episode> episodes, int index,
                                                      Voice priorVoice, Stream priorStream) {
        var match = voices(episodes.get(index)).stream()
                .filter(voice -> voice.name().equals(priorVoice.name()) && voice.host().equals(priorVoice.host()))
                .findFirst();
        if (match.isEmpty()) return null;
        Voice voice = match.get();
        var items = streams(voice);
        var preferred = items.stream().filter(item -> item.height() == priorStream.height()
                && item.mime().equals(priorStream.mime())).findFirst();
        if (items.isEmpty()) return null;
        return selection(title, episodes, index, voice, preferred.orElse(items.get(0)));
    }

    private <T> void load(Screen target, Callable<T> task, Consumer<T> accept) {
        target.loading = true;
        target.status = "Загрузка…";
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
                updates.add(() -> {
                    target.loading = false;
                    target.status = errorStatus(error);
                    target.retry = () -> load(target, task, accept);
                });
            }
        });
    }

    static String errorStatus(Exception error) {
        String message = error.getMessage();
        if (error instanceof PyException && message != null && message.startsWith("CatalogError: ")) {
            return message.substring("CatalogError: ".length());
        }
        return "Не удалось загрузить данные ani-cli";
    }

    Bitmap poster(String url) {
        if (url.isEmpty()) return null;
        Bitmap cached = posters.get(url);
        if (cached != null || failed.contains(url) || pending.size() >= 16 || !pending.add(url)) return cached;
        images.execute(() -> {
            Bitmap decoded = null;
            try {
                var connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(false);
                try (var input = connection.getInputStream()) {
                    byte[] data = Catalog.Json.read(input);
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
