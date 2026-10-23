package ru.tubetv.app;

import java.util.Locale;

/** Conservative catalog filter for full-length videos returned by the public sources. */
final class FilmCatalog {
    static final long MIN_DURATION_MS = 40 * 60 * 1000L;

    static boolean includes(VideoItem item) {
        if (item == null || item.isLive || item.durationMs < MIN_DURATION_MS) return false;
        String title = item.title == null ? "" : item.title.toLowerCase(Locale.ROOT);
        for (String excluded : new String[]{"трейлер", "тизер", "анонс", "обзор", "реакция",
                "разбор", "фрагмент", "trailer", "teaser", "review", "reaction", "recap"}) {
            if (title.contains(excluded)) return false;
        }
        return true;
    }

    private FilmCatalog() { }
}
