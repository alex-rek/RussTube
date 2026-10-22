package ru.tubetv.app;

import java.util.Collections;
import java.util.List;

/** Local ordering of already loaded results; never changes the platform query. */
final class VideoSort {
    enum Mode {
        NEWEST("Сначала новые"),
        OLDEST("Сначала старые"),
        VIEWS("По просмотрам"),
        ORIGINAL("По подборке");

        final String label;
        Mode(String label) { this.label = label; }
    }

    static Mode parse(String value) {
        try { return Mode.valueOf(value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return Mode.NEWEST; }
    }

    static void apply(List<VideoItem> items, Mode mode) {
        if (mode == Mode.ORIGINAL) return;
        if (mode == Mode.VIEWS) {
            Collections.sort(items, (a, b) -> compareKnownDescending(a.views, b.views));
        } else if (mode == Mode.OLDEST) {
            Collections.sort(items, (a, b) -> compareKnownAscending(a.publishedAtMs, b.publishedAtMs));
        } else {
            Collections.sort(items, (a, b) -> compareKnownDescending(a.publishedAtMs, b.publishedAtMs));
        }
    }

    private static int compareKnownDescending(long a, long b) {
        if (a <= 0) return b <= 0 ? 0 : 1;
        if (b <= 0) return -1;
        return Long.compare(b, a);
    }

    private static int compareKnownAscending(long a, long b) {
        if (a <= 0) return b <= 0 ? 0 : 1;
        if (b <= 0) return -1;
        return Long.compare(a, b);
    }

    private VideoSort() { }
}
