package ru.tubetv.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Selects short-form videos using source metadata rather than thumbnail shape. */
final class ShortsCatalog {
    private static final long MAX_DURATION_MS = 180_000L;

    static boolean accepts(VideoItem item) {
        if (item == null || item.isLive || item.durationMs <= 0
                || item.durationMs > MAX_DURATION_MS) return false;
        if (item.maxWidth > 0 && item.maxHeight > 0)
            return item.maxHeight > item.maxWidth;
        String title = item.title == null ? "" : item.title.toLowerCase(Locale.ROOT);
        String page = item.pageUrl == null ? "" : item.pageUrl.toLowerCase(Locale.ROOT);
        return title.contains("#shorts") || title.contains("#шортс")
                || title.matches(".*(^|[^a-z])shorts([^a-z]|$).*")
                || title.matches(".*(^|[^а-яё])шортс([^а-яё]|$).*")
                || page.contains("/shorts/") || page.contains("/clip-")
                || page.contains("/clips/");
    }

    static List<VideoItem> filter(List<VideoItem> items) {
        List<VideoItem> result = new ArrayList<>();
        for (VideoItem item : items) if (accepts(item)) result.add(item);
        return result;
    }

    private ShortsCatalog() { }
}
