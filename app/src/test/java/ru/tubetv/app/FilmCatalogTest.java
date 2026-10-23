package ru.tubetv.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FilmCatalogTest {
    private static VideoItem video(String title, long duration) {
        return new VideoItem("PEERTUBE", title, "", "", "https://video.example/play",
                "https://video.example/watch", duration);
    }

    @Test public void keepsFullLengthVideosFromAnySource() {
        assertTrue(FilmCatalog.includes(video("Кин-дза-дза", 130 * 60_000L)));
        assertTrue(FilmCatalog.includes(video("Документальный фильм о море", 40 * 60_000L)));
    }

    @Test public void excludesShortVideosAndPromos() {
        assertFalse(FilmCatalog.includes(video("Фильм", 15 * 60_000L)));
        assertFalse(FilmCatalog.includes(video("Трейлер фильма", 50 * 60_000L)));
        assertFalse(FilmCatalog.includes(video("Movie review", 90 * 60_000L)));
    }
}
