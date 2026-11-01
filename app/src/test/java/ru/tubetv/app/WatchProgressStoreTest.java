package ru.tubetv.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class WatchProgressStoreTest {
    private final MemoryPreferences preferences = new MemoryPreferences();
    private VideoItem video(String id) {
        return new VideoItem("RUTUBE", id, "", "", "https://rutube.ru/play/" + id,
                "https://rutube.ru/video/" + id, 900_000);
    }
    private void save(VideoItem item, long position) {
        WatchProgressStore.save(preferences, item.source, item.pageUrl, position, item.durationMs);
    }

    @Test public void viewBelowFiveSecondsDoesNotCreateProgress() {
        VideoItem item = video("short");
        save(item, 4_999);
        assertEquals(0, WatchProgressStore.get(preferences, item).positionMs);
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void fiveSecondsWorksForKnownAndUnknownDuration() {
        assertFalse(WatchProgressStore.isViewed(4_999, 30_000));
        assertTrue(WatchProgressStore.isViewed(5_000, 30_000));
        assertFalse(WatchProgressStore.isViewed(4_999, 900_000));
        assertTrue(WatchProgressStore.isViewed(5_000, 900_000));
        assertFalse(WatchProgressStore.isViewed(4_999, 0));
        assertTrue(WatchProgressStore.isViewed(5_000, 0));
    }

    @Test public void rewindToBeginningReplacesOldResumePosition() {
        VideoItem item = video("rewind");
        save(item, 600_000);
        save(item, 15_000);
        assertEquals(15_000, WatchProgressStore.get(preferences, item).positionMs);
        save(item, 0);
        assertEquals(0, WatchProgressStore.get(preferences, item).positionMs);
    }

    @Test public void unknownDurationDoesNotEraseKnownDuration() {
        VideoItem item = video("duration");
        save(item, 100_000);
        WatchProgressStore.save(preferences, item.source, item.pageUrl, 75_000, 0);
        assertEquals(item.durationMs, WatchProgressStore.get(preferences, item).durationMs);
    }

    @Test public void repeatedMirrorSaveDoesNotWriteSynchronouslyAgain() {
        VideoItem item = video("mirror");
        WatchProgressStore.setMirrored(preferences, item.source, item.pageUrl, false);
        assertEquals(0, preferences.commits);
        WatchProgressStore.setMirrored(preferences, item.source, item.pageUrl, true);
        WatchProgressStore.setMirrored(preferences, item.source, item.pageUrl, true);
        assertEquals(1, preferences.commits);
        save(item, 90_000);
        assertTrue(preferences.getBoolean(WatchProgressStore.key(item.source, item.pageUrl) + "_mirrored", false));
    }

    @Test public void evictionDropsWholeOldRecordAndKeepsRecentlyUsedVideo() {
        for (int i = 0; i < 1000; i++) save(video("v" + i), 100_000);
        VideoItem oldest = video("v0");
        WatchProgressStore.setMirrored(preferences, oldest.source, oldest.pageUrl, true);
        save(video("v1000"), 100_000);
        assertEquals(100_000, WatchProgressStore.get(preferences, oldest).positionMs);
        assertEquals(0, WatchProgressStore.get(preferences, video("v1")).positionMs);
        assertEquals(1000, preferences.getString("order", "").split(",").length);
    }

    @Test public void oldPreferenceFormatKeepsExistingProgressDuringMigration() {
        VideoItem item = video("legacy");
        String key = WatchProgressStore.key(item.source, item.pageUrl);
        preferences.edit().putLong(key + "_position", 600_000)
                .putBoolean(key + "_mirrored", true).apply();
        save(video("new"), 90_000);
        assertEquals(600_000, WatchProgressStore.get(preferences, item).positionMs);
        assertTrue(preferences.getBoolean(key + "_mirrored", false));
    }
}
