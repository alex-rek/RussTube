package ru.tubetv.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class VideoSortTest {
    private static VideoItem item(String title, long date, long views) {
        return new VideoItem("OK", title, "", "", title, title).withMetrics(views, date);
    }

    @Test public void newestPlacesUnknownDatesLastAndKeepsTiesStable() {
        List<VideoItem> items = new ArrayList<>(Arrays.asList(
                item("unknown", 0, 10), item("old", 100, 5),
                item("newA", 200, 1), item("newB", 200, 2)));
        VideoSort.apply(items, VideoSort.Mode.NEWEST);
        assertEquals(Arrays.asList("newA", "newB", "old", "unknown"), titles(items));
    }

    @Test public void oldestAndViewsKeepMissingMetadataLast() {
        List<VideoItem> items = new ArrayList<>(Arrays.asList(
                item("unknown", 0, -1), item("new", 200, 20), item("old", 100, 10)));
        VideoSort.apply(items, VideoSort.Mode.OLDEST);
        assertEquals(Arrays.asList("old", "new", "unknown"), titles(items));
        VideoSort.apply(items, VideoSort.Mode.VIEWS);
        assertEquals(Arrays.asList("new", "old", "unknown"), titles(items));
    }

    @Test public void invalidSavedChoiceUsesNewest() {
        assertEquals(VideoSort.Mode.NEWEST, VideoSort.parse("missing"));
        assertEquals(VideoSort.Mode.NEWEST, VideoSort.parse(null));
    }

    private static List<String> titles(List<VideoItem> items) {
        List<String> result = new ArrayList<>();
        for (VideoItem item : items) result.add(item.title);
        return result;
    }
}
