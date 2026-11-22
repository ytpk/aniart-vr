package dev.aniart.vr;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CinemaPanel {
    private static final int WIDTH = 1280;
    private static final int FULL_HEIGHT = 760;
    private static final int WATCH_HEIGHT = 300;
    private static final int BACK = 0xff111522;
    private static final int TILE = 0xff242a3c;
    private static final int FOCUS = 0xff414078;
    private static final int INK = 0xfff0effc;
    private static final int MUTED = 0xffafb8cf;
    private static final int ACCENT = 0xffc2afff;
    private static final int HIDDEN = 0, LIBRARY = 1, WATCH = 2, SETTINGS = 3;
    private static final int PAGE_SIZE = 8;
    private static final int DETAIL_SIZE = 5;

    interface Click { void at(float x); }
    record Target(RectF rect, Click click) {}

    private final CinemaActivity activity;
    private final AniCliBrowser anicli;
    private boolean remote;
    private final SharedPreferences preferences;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<Target> buttons = new ArrayList<>();
    private final RectF bounds = new RectF();
    private Canvas canvas;
    private Bitmap bitmap;
    private List<Catalog.Series> lastCatalog;
    private CinemaActivity.Snapshot lastSnapshot;
    private String lastNotice = "";
    private Catalog.Episode currentEpisode;
    private Catalog.Series details;
    private String section;
    private int page = LIBRARY, libraryPage, sectionsPage, variantsPage;
    private boolean favoritesOnly, keyboard, cyrillic = true;
    private String search = "";
    private int stereo, depth, screenSize;
    private int depthModel;
    private String depthStatus = "";
    private boolean fast, comparison, reverse, autoNext, roomSound;
    private int hovered = -1;
    private boolean dirty = true, allocated;
    private float fps, gpuMs = -1;
    private int filterStatus, frameWidth, frameHeight;
    private boolean depthReady;
    private Catalog.Episode lastShared;
    private long lastRender, lastUse;

    CinemaPanel(CinemaActivity activity, SharedPreferences preferences) {
        this.activity = activity;
        this.anicli = new AniCliBrowser(activity, this::playOnline);
        remote = preferences.getBoolean("anicli_tab", true);
        this.preferences = preferences;
        fast = preferences.getBoolean("anime4k", false);
        comparison = preferences.getBoolean("comparison", false);
        reverse = preferences.getBoolean("reverse_eyes", false);
        depth = Math.max(0, Math.min(10, preferences.getInt("depth", 4)));
        depthModel = Math.max(0, Math.min(3, preferences.getInt("depth_model", 1)));
        screenSize = Math.max(0, Math.min(2, preferences.getInt("screen_size", 1)));
        autoNext = preferences.getBoolean("auto_next", true);
        roomSound = preferences.getBoolean("room_sound", false);
    }

    void shutdown() { anicli.shutdown(); }

    int renderConfig() {
        if (anicli.pump()) dirty = true;
        String modelStatus = activity.depthStatus();
        if (!modelStatus.equals(depthStatus)) {
            depthStatus = modelStatus;
            dirty = true;
        }
        var shared = activity.sharedEpisode();
        if (shared != lastShared) {
            lastShared = shared;
            if (shared != null) {
                show(LIBRARY);
                keyboard = false;
                remote = false;
                details = Catalog.group(List.of(shared)).get(0);
                section = shared.episode();
                sectionsPage = variantsPage = 0;
                buttons.clear();
                dirty = true;
            }
        }
        if (remote && page == LIBRARY) anicli.start();
        var state = activity.snapshot();
        if (state.episode() != currentEpisode) {
            currentEpisode = state.episode();
            if (currentEpisode != null) {
                stereo = preferences.getInt("stereo:" + currentEpisode.title(), currentEpisode.stereo());
            }
            dirty = true;
        }
        if (page == HIDDEN && !state.status().isEmpty() && !state.playing() && currentEpisode != null) {
            show(WATCH);
        }
        if (page == WATCH && !comparison && hovered < 0 && state.playing() && state.status().isEmpty()
                && SystemClock.uptimeMillis() - lastUse > 11000) {
            show(HIDDEN);
        }
        return page | (fast ? 8 : 0) | (comparison ? 16 : 0) | (stereo << 5)
                | (reverse ? 256 : 0) | (depth << 9) | (screenSize << 13) | (roomSound ? 1 << 15 : 0)
                | (depthModel << 16);
    }

    private void changeStereo(int mode) {
        stereo = mode;
        if (currentEpisode != null) preferences.edit().putInt("stereo:" + currentEpisode.title(), mode).apply();
        dirty = true;
    }

    private void toggleComparison() {
        comparison = !comparison;
        if (comparison) fast = true;
        settingsChanged();
    }

    private void settingsChanged() {
        preferences.edit().putBoolean("anime4k", fast).putBoolean("comparison", comparison)
                .putBoolean("reverse_eyes", reverse).putBoolean("auto_next", autoNext)
                .putInt("depth", depth).putInt("depth_model", depthModel).putInt("screen_size", screenSize).apply();
        dirty = true;
    }

    private void show(int destination) {
        if (destination == WATCH && activity.snapshot().episode() == null) destination = LIBRARY;
        if (page != destination) {
            page = destination;
            keyboard = false;
            buttons.clear();
            hovered = -1;
            dirty = true;
        }
        lastUse = SystemClock.uptimeMillis();
    }

    private void back() {
        if (keyboard) {
            keyboard = false;
            dirty = true;
        } else if (page == SETTINGS) {
            show(WATCH);
        } else if (remote && page == LIBRARY && anicli.back()) {
            dirty = true;
        } else if (details != null && page == LIBRARY) {
            details = null;
            dirty = true;
        } else if (page == WATCH) {
            show(HIDDEN);
        } else {
            show(WATCH);
        }
        buttons.clear();
        hovered = -1;
    }

    void command(int command) {
        switch (command) {
            case 0 -> activity.toggle();
            case 1 -> back();
            case 2 -> show(LIBRARY);
            case 3 -> show(page == WATCH ? HIDDEN : WATCH);
            case 5, 6 -> {
                if (page != LIBRARY || keyboard) return;
                int step = command == 5 ? -1 : 1;
                if (remote) {
                    var screen = anicli.screen();
                    if (screen == null || screen.kind == AniCliBrowser.Kind.PROVIDERS) return;
                    int count = screen.kind == AniCliBrowser.Kind.TITLES ? screen.titles.size() : screen.rows.size();
                    int size = screen.kind == AniCliBrowser.Kind.TITLES ? PAGE_SIZE : DETAIL_SIZE;
                    screen.page = pageIndex(screen.page + step, count, size);
                } else if (details == null) {
                    libraryPage = pageIndex(libraryPage + step, visibleSeries().size(), PAGE_SIZE);
                } else {
                    sectionsPage = pageIndex(sectionsPage + step, details.episodes().size(), DETAIL_SIZE);
                }
                buttons.clear();
                dirty = true;
            }
        }
    }

    void click(float x, float y) {
        int index = hit(x, y);
        if (index < 0) return;
        lastUse = SystemClock.uptimeMillis();
        Target target = buttons.get(index);
        target.click().at(x * WIDTH);
        dirty = true;
        buttons.clear();
        hovered = -1;
    }

    private int hit(float x, float y) {
        if (x < 0 || y < 0) return -1;
        float px = x * WIDTH, py = y * (page == WATCH ? WATCH_HEIGHT : FULL_HEIGHT);
        for (int i = buttons.size() - 1; i >= 0; --i) {
            if (buttons.get(i).rect().contains(px, py)) return i;
        }
        return -1;
    }

    void upload(float x, float y, int filterStatus, int width, int height,
                boolean depthReady, float fps, float gpuMs) {
        if (page == HIDDEN) return;
        var catalog = activity.catalog();
        var state = activity.snapshot();
        String notice = activity.notice();
        long now = SystemClock.uptimeMillis();
        int target = hit(x, y);
        if (target != hovered) {
            hovered = target;
            dirty = true;
        }
        if (catalog != lastCatalog) {
            if (lastCatalog != null) {
                details = null;
                libraryPage = 0;
                buttons.clear();
                hovered = -1;
            }
            lastCatalog = catalog;
            dirty = true;
        }
        if (!notice.equals(lastNotice) || this.filterStatus != filterStatus || frameWidth != width
                || frameHeight != height || this.depthReady != depthReady) dirty = true;
        if (page == WATCH && (!state.equals(lastSnapshot) || now - lastRender >= 2000)) dirty = true;
        if (page == SETTINGS && now - lastRender >= 700) dirty = true;
        if (!dirty) return;

        lastNotice = notice;
        lastSnapshot = state;
        lastRender = now;
        this.filterStatus = filterStatus;
        frameWidth = width;
        frameHeight = height;
        this.depthReady = depthReady;
        this.gpuMs = gpuMs;
        this.fps = fps;
        int h = page == WATCH ? WATCH_HEIGHT : FULL_HEIGHT;
        if (bitmap == null || bitmap.getHeight() != h) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
            allocated = false;
        }
        canvas.drawColor(BACK);
        buttons.clear();
        if (page == WATCH) watch(state);
        else {
            header(state);
            if (page == SETTINGS) settings(notice);
            else if (keyboard) keyboard();
            else if (remote) remoteCatalog(notice);
            else if (details != null) detail();
            else library(notice);
        }
        if (allocated) GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
        else GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        allocated = true;
        dirty = false;
    }

    private void header(CinemaActivity.Snapshot state) {
        box(0, 0, WIDTH, 96, 0, 0xff191e2f);
        button("ani-cli", 32, 22, 176, 53, page == LIBRARY && remote, () -> switchCatalog(true));
        button("Локальное", 224, 22, 202, 53, page == LIBRARY && !remote, () -> switchCatalog(false));
        if (state.episode() != null) button("Плеер", 551, 22, 190, 53, false, () -> show(WATCH));
        button("Настройки", 757, 22, 215, 53, page == SETTINGS, () -> show(SETTINGS));
        if (state.episode() != null) button("Скрыть", 1035, 22, 213, 53, false, () -> show(HIDDEN));
    }

    private void switchCatalog(boolean useRemote) {
        if (remote != useRemote) search = "";
        remote = useRemote;
        preferences.edit().putBoolean("anicli_tab", remote).apply();
        details = null;
        keyboard = false;
        show(LIBRARY);
    }

    private void playEpisode(Catalog.Episode episode) {
        activity.open(episode);
        page = WATCH;
        lastUse = SystemClock.uptimeMillis();
    }

    private void playOnline(CinemaActivity.OnlineEpisode selection) {
        activity.openOnline(selection);
        page = WATCH;
        lastUse = SystemClock.uptimeMillis();
    }

    private void remoteCatalog(String notice) {
        var screen = anicli.screen();
        if (screen == null) return;
        if (screen.kind == AniCliBrowser.Kind.TITLES) {
            button("‹  Источники", 32, 113, 206, 61, false, anicli::back);
            text(screen.title, 257, 155, 33, INK, 307, true);
            button(screen.subtitle.isEmpty() ? "Поиск  ·  " + screen.title : "Поиск  ·  " + screen.subtitle,
                    580, 113, 668, 61, false, () -> { search = screen.subtitle; keyboard = true; });
            screen.page = pageIndex(screen.page, screen.titles.size(), PAGE_SIZE);
            int start = screen.page * PAGE_SIZE;
            for (int i = start; i < Math.min(start + PAGE_SIZE, screen.titles.size()); i++) {
                int cell = i - start;
                remoteCard(screen.titles.get(i), 32 + (cell % 4) * 308, 196 + (cell / 4) * 233);
            }
            pageButtons(screen.page, screen.titles.size(), PAGE_SIZE, 32, 689, 445, value -> screen.page = value);
            if (!screen.status.isEmpty()) {
                float x = screen.titles.isEmpty() ? 32 : 505;
                float y = screen.titles.isEmpty() ? 270 : 721;
                text(screen.status, x, y, 25, MUTED, 735, false);
            }
            if (screen.retry != null) button("Повторить", 32, 302, 266, 62, true, screen.retry);
        } else {
            if (screen.kind == AniCliBrowser.Kind.PROVIDERS) {
                text(screen.title, 32, 157, 39, INK, 520, true);
                button("Поиск по всем источникам", 580, 113, 668, 61, false, () -> {
                    search = "";
                    keyboard = true;
                });
            } else {
                button("‹  Назад", 32, 115, 168, 56, false, anicli::back);
                text(screen.title, 224, 156, 39, INK, 1024, true);
            }
            text(screen.subtitle, 32, 226, 27, MUTED, 1216, false);
            boolean sources = screen.kind == AniCliBrowser.Kind.PROVIDERS;
            screen.page = sources ? 0 : pageIndex(screen.page, screen.rows.size(), DETAIL_SIZE);
            int start = screen.page * DETAIL_SIZE;
            int end = sources ? screen.rows.size() : Math.min(start + DETAIL_SIZE, screen.rows.size());
            if (!screen.loading && screen.retry == null) {
                for (int i = start; i < end; i++) {
                    var row = screen.rows.get(i);
                    if (sources) button(row.label(), 32 + (i % 2) * 616, 246 + (i / 2) * 82, 600, 65, false, row.open());
                    else button(row.label(), 32, 246 + (i - start) * 82, 1216, 65, false, row.open());
                }
            }
            if (screen.retry != null) button("Повторить", 32, 246, 266, 62, true, screen.retry);
            if (!sources) pageButtons(screen.page, screen.rows.size(), DETAIL_SIZE, 32, 689, 445, value -> screen.page = value);
            if (!screen.status.isEmpty()) text(screen.status, 32, screen.rows.isEmpty() && screen.retry == null ? 298 : 670, 24, MUTED, 1160, false);
        }
        if (!notice.isEmpty()) text(notice, 882, 746, 17, MUTED, 366, false);
    }

    private void remoteCard(AniCliBrowser.Title title, float x, float y) {
        boolean focus = hovered == buttons.size();
        box(x, y, 292, 217, 14, focus ? FOCUS : TILE);
        box(x + 9, y + 8, 100, 149, 10, 0xff3a345b);
        Bitmap poster = anicli.poster(title.poster());
        if (poster != null) {
            bounds.set(x + 9, y + 8, x + 109, y + 157);
            paint.setFilterBitmap(true);
            canvas.drawBitmap(poster, null, bounds, paint);
        } else {
            int[] initials = title.name().codePoints().limit(2).toArray();
            text(new String(initials, 0, initials.length).toUpperCase(Locale.ROOT),
                    x + 19, y + 94, 35, ACCENT, 84, true);
        }
        text(title.provider().name(), x + 123, y + 56, 23, ACCENT, 155, true);
        text(title.name(), x + 12, y + 194, 23, INK, 268, true);
        buttons.add(new Target(new RectF(x, y, x + 292, y + 217), ignored -> anicli.select(title)));
    }

    private List<Catalog.Series> visibleSeries() {
        var titles = activity.catalog();
        String query = search.toLowerCase(Locale.ROOT).trim();
        if (!favoritesOnly && query.isEmpty()) return titles;
        List<Catalog.Series> matches = new ArrayList<>();
        for (var series : titles) {
            if (favoritesOnly && !isFavorite(series)) continue;
            if (series.title().toLowerCase(Locale.ROOT).contains(query)) matches.add(series);
        }
        return matches;
    }

    private boolean isFavorite(Catalog.Series series) {
        return preferences.getBoolean("favorite:" + series.title(), false);
    }

    private static int pageIndex(int page, int count, int size) {
        return Math.max(0, Math.min(page, Math.max(0, count - 1) / size));
    }

    private void library(String notice) {
        text(favoritesOnly ? "Избранное" : "Каталог", 32, 160, 44, INK, 495, true);
        button(search.isEmpty() ? "Поиск" : "Поиск  ·  " + search, 544, 113, 466, 61, false, () -> {
            keyboard = true;
        });
        button("Избранное", 1026, 113, 222, 61, favoritesOnly, () -> {
            favoritesOnly = !favoritesOnly;
            libraryPage = 0;
        });
        List<Catalog.Series> titles = visibleSeries();
        libraryPage = pageIndex(libraryPage, titles.size(), PAGE_SIZE);
        int start = libraryPage * PAGE_SIZE;
        for (int i = start; i < Math.min(start + PAGE_SIZE, titles.size()); ++i) {
            int cell = i - start;
            card(titles.get(i), 32 + (cell % 4) * 308, 196 + (cell / 4) * 233);
        }
        if (titles.isEmpty()) text("Здесь пока нет тайтлов", 32, 270, 33, MUTED, 900, false);
        pageButtons(libraryPage, titles.size(), PAGE_SIZE, 32, 689, 490, next -> libraryPage = next);
        button("Вставить ссылку", 546, 689, 248, 52, false, () -> activity.pasteVideoLink(null));
        if (!notice.isEmpty()) text(notice, 810, 723, 20, MUTED, 438, false);
    }

    private void card(Catalog.Series series, float x, float y) {
        float w = 292, h = 217;
        boolean focus = hovered == buttons.size();
        box(x, y, w, h, 14, focus ? FOCUS : TILE);
        int[] colors = {0xff534396, 0xff2b557b, 0xff376466, 0xff774b70};
        int index = Math.floorMod(series.title().hashCode(), colors.length);
        paint.setShader(new LinearGradient(x, y, x + w, y + 126,
                colors[index], focus ? 0xff7b5bbc : 0xff2a2845, Shader.TileMode.CLAMP));
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(x + 6, y + 6, x + w - 6, y + 132, 10, 10, paint);
        paint.setShader(null);
        paint.setColor(0x25ffffff);
        canvas.drawCircle(x + w - 57, y + 54, 32 + index * 7, paint);
        paint.setColor(0x17ffffff);
        canvas.drawCircle(x + w - 95, y + 98, 28 + index * 3, paint);
        int[] letters = series.title().codePoints().limit(2).toArray();
        text(new String(letters, 0, letters.length).toUpperCase(Locale.ROOT),
                x + 22, y + 92, 58, INK, w - 44, true);
        text(series.title(), x + 16, y + 167, 23, INK, w - 32, true);
        text("Серий: " + series.episodes().size() + (isFavorite(series) ? "  ·  Избранное" : ""),
                x + 16, y + 200, 18, MUTED, w - 32, false);
        buttons.add(new Target(new RectF(x, y, x + w, y + h), ignored -> {
            details = series;
            section = series.episodes().keySet().iterator().next();
            sectionsPage = variantsPage = 0;
        }));
    }

    private void detail() {
        button("‹  К каталогу", 32, 115, 225, 56, false, () -> details = null);
        text(details.title(), 282, 158, 43, INK, 720, true);
        var series = details;
        button(isFavorite(series) ? "В избранном" : "В избранное", 1026, 115, 222, 56, isFavorite(series),
                () -> preferences.edit().putBoolean("favorite:" + series.title(), !isFavorite(series)).apply());
        text("Серии", 32, 226, 27, MUTED, 500, true);
        text("Озвучка и качество", 650, 226, 27, MUTED, 590, true);
        List<String> sections = List.copyOf(series.episodes().keySet());
        sectionsPage = pageIndex(sectionsPage, sections.size(), DETAIL_SIZE);
        int start = sectionsPage * DETAIL_SIZE;
        for (int i = start; i < Math.min(start + DETAIL_SIZE, sections.size()); ++i) {
            String name = sections.get(i);
            button(name, 32, 246 + (i - start) * 82, 590, 66, name.equals(section), () -> {
                section = name;
                variantsPage = 0;
            });
        }
        List<Catalog.Episode> variants = series.episodes().get(section);
        variantsPage = pageIndex(variantsPage, variants.size(), DETAIL_SIZE);
        start = variantsPage * DETAIL_SIZE;
        for (int i = start; i < Math.min(start + DETAIL_SIZE, variants.size()); ++i) {
            Catalog.Episode variant = variants.get(i);
            long resume = activity.positionFor(variant);
            String label = (resume > 5000 ? "Продолжить " + time(resume) + "   ·   " : "▶   ") + variant.variant();
            button(label, 650, 246 + (i - start) * 82, 598, 66, variant.equals(currentEpisode),
                    () -> playEpisode(variant));
        }
        pageButtons(sectionsPage, sections.size(), DETAIL_SIZE, 32, 689, 590, value -> sectionsPage = value);
        pageButtons(variantsPage, variants.size(), DETAIL_SIZE, 650, 689, 598, value -> variantsPage = value);
    }

    private void keyboard() {
        text("Поиск по названию", 32, 146, 30, INK, 790, true);
        box(32, 168, 1216, 83, 13, TILE);
        text(search.isEmpty() ? "Введите название" : search, 50, 222, 37, search.isEmpty() ? MUTED : INK, 1170, false);
        String[] rows = cyrillic
                ? new String[]{"ЙЦУКЕНГШЩЗХЪ", "ФЫВАПРОЛДЖЭ", "ЯЧСМИТЬБЮЁ"}
                : new String[]{"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"};
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            float keyWidth = 88;
            float x = (WIDTH - row.length() * (keyWidth + 10) + 10) / 2;
            for (int k = 0; k < row.length(); k++) {
                String letter = row.substring(k, k + 1);
                button(letter, x + k * (keyWidth + 10), 274 + r * 83, keyWidth, 69, false,
                        () -> append(letter.toLowerCase(Locale.ROOT)));
            }
        }
        for (int i = 0; i < 10; ++i) {
            String key = Integer.toString(i);
            button(key, 150 + i * 98, 523, 88, 56, false, () -> append(key));
        }
        button(cyrillic ? "RU / EN" : "EN / RU", 32, 609, 170, 77, false, () -> cyrillic = !cyrillic);
        button("Пробел", 218, 609, 330, 77, false, () -> append(" "));
        button("⌫", 564, 609, 145, 77, false, () -> {
            if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
            libraryPage = 0;
        });
        button("Очистить", 725, 609, 230, 77, false, () -> {
            search = "";
            libraryPage = 0;
        });
        button(remote ? "Искать" : "Показать (" + visibleSeries().size() + ")", 971, 609, 277, 77, true, () -> {
            keyboard = false;
            if (remote) anicli.search(search);
        });
    }

    private void append(String value) {
        if (search.length() < 64) search += value;
        libraryPage = 0;
    }

    private void watch(CinemaActivity.Snapshot state) {
        var episode = state.episode();
        text(episode == null ? "Загрузка" : episode.title() + "  ·  " + episode.episode(), 32, 48, 30, INK, 775, true);
        button(stereo == 0 ? "2D → 3D" : stereoLabel(), 824, 16, 162, 56, stereo != 0, () -> {
            if (stereo == 0) {
                if (depth == 0) {
                    depth = 4;
                    settingsChanged();
                }
                changeStereo(3);
            } else if (stereo == 3) {
                changeStereo(0);
            } else {
                show(SETTINGS);
            }
        });
        button("A/B", 1002, 16, 148, 56, comparison, this::toggleComparison);
        button("×", 1166, 16, 82, 56, false, () -> show(HIDDEN));
        String status = state.status();
        if (episode != null && status.isEmpty()) status = episode.variant();
        text(status, 32, 79, 20, MUTED, 800, false);
        String picture = frameSize() + "  ·  " + filterLabel() + "  ·  " + stereoStatus();
        text(picture, 332, 111, 20, ACCENT, 916, false);
        text(time(state.position()) + " / " + time(state.duration()), 32, 111, 20, MUTED, 320, false);
        if (state.seekable() && state.duration() > 0) {
            float amount = (float) state.position() / state.duration();
            float width = 1216;
            box(32, 142, width, 10, 5, hovered == buttons.size() ? FOCUS : TILE);
            box(32, 142, Math.min(width, Math.max(0, amount * width)), 10, 5, 0xffaa91ff);
            buttons.add(new Target(new RectF(32, 117, 1248, 172), x -> activity.seek((x - 32) / width)));
        }
        if (state.seekable()) button("−10 c", 32, 193, 108, 75, false, () -> activity.step(-10000));
        button(state.playing() ? "Пауза" : "Смотреть", 152, 193, 158, 75, true, activity::toggle);
        if (state.seekable()) button("+10 c", 322, 193, 108, 75, false, () -> activity.step(10000));
        if (activity.canPlayNext(episode)) button("След. серия", 442, 193, 170, 75, false, () -> activity.next(episode));
        button(String.format(Locale.ROOT, "%.2f×", state.speed()), 624, 193, 109, 75, false, activity::changeSpeed);
        button("−", 745, 193, 63, 75, false, () -> activity.changeVolume(-.1f));
        text(Math.round(state.volume() * 100) + "%", 821, 241, 22, MUTED, 62, false);
        button("+", 891, 193, 63, 75, false, () -> activity.changeVolume(.1f));
        button("Каталог", 966, 193, 129, 75, false, () -> show(LIBRARY));
        button("Настройки", 1107, 193, 141, 75, false, () -> show(SETTINGS));
    }

    private void settings(String notice) {
        text("Изображение", 32, 152, 36, INK, 600, true);
        button(fast ? "Anime4K: вкл." : "Anime4K: выкл.", 32, 173, 274, 62, fast, () -> {
            fast = !fast;
            if (!fast) comparison = false;
            settingsChanged();
        });
        button(comparison ? "A/B: вкл." : "A/B: выкл.", 320, 173, 250, 62, comparison, this::toggleComparison);
        text(frameSize() + "  ·  " + filterLabel(), 590, 213, 24, MUTED, 656, false);
        text("Стерео", 32, 291, 36, INK, 600, true);
        String[] options = {"2D", "SBS · рядом", "OU · сверху/снизу", "2D → 3D"};
        for (int i = 0; i < options.length; i++) {
            int value = i;
            button(options[i], 32 + i * 306, 308, 290, 62, stereo == i, () -> changeStereo(value));
        }
        if (stereo != 0) {
            button(reverse ? "Глаза: R / L" : "Глаза: L / R", 32, 385, 274, 58, reverse, () -> {
                reverse = !reverse;
                settingsChanged();
            });
        }
        if (stereo == 3) {
            button("−", 320, 385, 74, 58, false, () -> {
                depth = Math.max(0, depth - 1);
                settingsChanged();
            });
            text("Глубина " + depth + "/10", 414, 422, 24, MUTED, 206, true);
            button("+", 620, 385, 74, 58, false, () -> {
                depth = Math.min(10, depth + 1);
                settingsChanged();
            });
            button(DepthModels.label(depthModel) + "  ›", 716, 385, depthModel == 2 ? 354 : 532, 58, true, () -> {
                depthModel = (depthModel + 1) % 4;
                settingsChanged();
            });
            if (depthModel == 2) button("Файл Limbo", 1080, 385, 168, 58, false, activity::pickLimbo);
        }
        text("Экран и просмотр", 32, 500, 36, INK, 680, true);
        String[] sizes = {"Малый", "Средний", "Большой"};
        for (int i = 0; i < sizes.length; i++) {
            int value = i;
            button(sizes[i], 32 + i * 156, 521, 144, 60, screenSize == i, () -> {
                screenSize = value;
                settingsChanged();
            });
        }
        button(autoNext ? "Автосерия: вкл." : "Автосерия: выкл.", 520, 521, 268, 60, autoNext, () -> {
            autoNext = !autoNext;
            activity.setAutoNext(autoNext);
            settingsChanged();
        });
        button("Импорт каталога", 808, 521, 440, 60, false, activity::pickCatalog);
        button(roomSound ? "Звук зала: вкл." : "Звук зала: выкл.", 32, 599, 268, 55, roomSound, () -> {
            roomSound = !roomSound;
            activity.setRoomSound(roomSound);
        });
        String audio = notice;
        if (audio.isEmpty() && roomSound) {
            audio = switch (activity.roomInputChannels()) {
                case 1 -> "Моно → виртуальный зал";
                case 2 -> "Стерео → виртуальный зал";
                case 6 -> "5.1 → виртуальные колонки";
                case 8 -> "7.1 → виртуальные колонки";
                case 0 -> "Виртуальные 7.1 с отражениями";
                default -> "Звук зала недоступен для формата дорожки";
            };
        }
        if (!audio.isEmpty()) text(audio, 320, 636, 22, MUTED, 905, false);
        String stats = String.format(Locale.ROOT, "%.0f FPS", fps);
        if (filterStatus == 1 && gpuMs >= 0) stats += String.format(Locale.ROOT, "  ·  GPU %.1f мс", gpuMs);
        stats += "  ·  " + stereoStatus();
        text(stats, 32, 704, 23, ACCENT, 940, false);
        if (activity.snapshot().episode() != null) button("К просмотру", 1012, 655, 236, 66, true, () -> show(WATCH));
    }

    private String frameSize() {
        return frameWidth > 0 ? frameWidth + "×" + frameHeight : "Нет кадра";
    }

    private String filterLabel() {
        return switch (filterStatus) {
            case 1 -> comparison ? "Слева оригинал / справа Anime4K" : "Anime4K ×2";
            case 2 -> "Anime4K: ожидание кадра";
            case 3 -> "Anime4K: кадр выше лимита";
            case 4 -> "Anime4K: GPU недоступен";
            default -> "Оригинал";
        };
    }

    private String stereoStatus() {
        if (stereo == 3) {
            if (depth == 0) return "Псевдо-3D: глубина 0";
            String model = DepthModels.label(depthModel);
            if (depthModel != 0 && !depthStatus.isEmpty()) return model + "  ·  " + depthStatus;
            return depthReady ? model + " " + depth + "/10" : model + ": ожидание карты";
        }
        return stereoLabel();
    }

    private String stereoLabel() {
        return switch (stereo) { case 1 -> "SBS"; case 2 -> "OU"; case 3 -> "2D → 3D"; default -> "2D"; };
    }

    private void pageButtons(int page, int count, int size, float x, float y, float w,
                             java.util.function.IntConsumer change) {
        int total = Math.max(1, (count + size - 1) / size);
        if (total < 2) return;
        if (page > 0) button("‹", x, y, 86, 52, false, () -> change.accept(page - 1));
        text((page + 1) + " / " + total, x + 108, y + 34, 23, MUTED, w - 216, false);
        if (page + 1 < total) button("›", x + w - 86, y, 86, 52, false, () -> change.accept(page + 1));
    }

    private void button(String label, float x, float y, float w, float h, boolean selected, Runnable command) {
        boolean focus = hovered == buttons.size();
        box(x, y, w, h, 12, focus ? FOCUS : selected ? 0xff362b62 : TILE);
        float size = w < 150 ? 21 : 24;
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        String displayed = TextUtils.ellipsize(label, paint, w - 24, TextUtils.TruncateAt.END).toString();
        float textWidth = paint.measureText(displayed);
        text(displayed, x + (w - textWidth) / 2, y + h / 2 + size * .33f, size,
                selected || focus ? ACCENT : INK, w - 24, true);
        buttons.add(new Target(new RectF(x, y, x + w, y + h), ignored -> command.run()));
    }

    private void box(float x, float y, float w, float h, float radius, int color) {
        bounds.set(x, y, x + w, y + h);
        paint.setColor(color);
        canvas.drawRoundRect(bounds, radius, radius, paint);
    }

    private void text(String value, float x, float y, float size, int color, float width, boolean bold) {
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        paint.setColor(color);
        canvas.drawText(TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END).toString(), x, y, paint);
    }

    private static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
