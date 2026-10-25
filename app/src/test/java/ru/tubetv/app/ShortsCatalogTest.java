package ru.tubetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class ShortsCatalogTest {
    private VideoItem video(String title, String page, long duration, int width, int height) {
        return new VideoItem("VK VIDEO", title, "", "", page, page, duration)
                .withQuality(width, height);
    }

    @Test public void acceptsVerticalAndExplicitShortsOnly() {
        assertTrue(ShortsCatalog.accepts(video("Прогулка", "https://vk.com/video1", 70_000, 720, 1280)));
        assertTrue(ShortsCatalog.accepts(video("Кот #shorts", "https://vk.com/video2", 30_000, 0, 0)));
        assertTrue(ShortsCatalog.accepts(video("Кот", "https://vk.com/clip-123", 30_000, 0, 0)));
        assertFalse(ShortsCatalog.accepts(video("Кот #shorts", "https://vk.com/video3", 30_000, 1280, 720)));
        assertFalse(ShortsCatalog.accepts(video("Кот", "https://vk.com/video4", 30_000, 0, 0)));
        assertFalse(ShortsCatalog.accepts(video("Кот #shorts", "https://vk.com/video5", 181_000, 0, 0)));
        assertTrue(ShortsCatalog.accepts(video("Длинный вертикальный ролик", "https://vk.com/video7",
                3_600_000, 720, 1280)));
        assertFalse(ShortsCatalog.accepts(video("Длинный горизонтальный ролик #shorts",
                "https://vk.com/video8", 3_600_000, 1280, 720)));
        assertFalse(ShortsCatalog.accepts(video("Кот #shorts", "https://vk.com/video6", 30_000, 0, 0)
                .withChannel("", "", "", true)));
    }

    @Test public void queueKeepsFeedOrderAndEnds() {
        VideoItem first = video("Первое #shorts", "https://vk.com/video1", 30_000, 0, 0);
        VideoItem second = video("Второе #shorts", "https://vk.com/video2", 40_000, 0, 0);
        ShortsQueue.set(Arrays.asList(first, second));
        assertEquals(second.stableKey(), ShortsQueue.adjacent(first, 1).stableKey());
        assertEquals(first.stableKey(), ShortsQueue.adjacent(second, -1).stableKey());
        assertNull(ShortsQueue.adjacent(first, -1));
        assertNull(ShortsQueue.adjacent(second, 1));
    }
}
