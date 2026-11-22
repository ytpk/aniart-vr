package dev.aniart.vr;

import java.io.IOException;
import java.util.List;

final class AnixartPlayback {
    @FunctionalInterface
    interface KodikResolver {
        List<KodikStreams.Stream> resolve(String url) throws Exception;
    }

    record Result(AnixartCatalog.Link link, List<KodikStreams.Stream> streams, String status) {}

    private final AnixartCatalog catalog;
    private final KodikResolver kodik;

    AnixartPlayback(AnixartCatalog catalog, KodikResolver kodik) {
        this.catalog = catalog;
        this.kodik = kodik;
    }

    Result resolve(AnixartCatalog.Title title, AnixartCatalog.Option source, AnixartCatalog.Episode episode)
            throws Exception {
        if (title.blocked()) throw new IOException("Anixart ограничил просмотр этого тайтла");
        var link = catalog.resolve(title, source, episode);
        if (!link.embedded()) return new Result(link, List.of(), "");
        if (!KodikStreams.isEmbed(link.url())) return new Result(link, List.of(), "Источник предоставляет веб-плеер");
        try {
            return new Result(link, kodik.resolve(link.url()), "");
        } catch (IOException error) {
            String message = error.getMessage();
            return new Result(link, List.of(), message != null && message.startsWith("Kodik")
                    ? message : "Не удалось получить поток Kodik");
        } catch (Exception error) {
            return new Result(link, List.of(), "Не удалось прочитать потоки Kodik");
        }
    }
}
