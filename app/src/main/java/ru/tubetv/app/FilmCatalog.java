package ru.tubetv.app;

import java.util.Locale;

/** Conservative catalog filter for full-length videos returned by the public sources. */
final class FilmCatalog {
    static final long MIN_DURATION_MS = 40 * 60 * 1000L;

    static boolean includes(VideoItem item) {
        return includes(item, false);
    }

    static boolean includes(VideoItem item, boolean trustedFilmCollection) {
        if (item == null || item.isLive || item.durationMs < MIN_DURATION_MS) return false;
        String title = item.title == null ? "" : item.title.toLowerCase(Locale.ROOT);
        for (String excluded : new String[]{"трейлер", "тизер", "анонс", "обзор", "реакция",
                "разбор", "фрагмент", "trailer", "teaser", "review", "reaction", "recap",
                "прямой эфир", "новости", "телепередач", "ток-шоу", "полный контакт",
                "интервью", "подкаст", "стрим", "репортаж", "сводка",
                "сериал", "серия", "серии", "серий", "сезон", "эпизод", "episode", "season"}) {
            if (title.contains(excluded)) return false;
        }
        if (trustedFilmCollection) return true;
        String channel = item.channelName == null ? "" : item.channelName.toLowerCase(Locale.ROOT);
        for (String filmWord : new String[]{"фильм", "кино", "мультфильм",
                "документальн", "полнометраж", "movie", "film", "cinema", "documentary"}) {
            if (title.contains(filmWord) || channel.contains(filmWord)) return true;
        }
        return false;
    }

    private FilmCatalog() { }
}
