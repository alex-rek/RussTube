package ru.tubetv.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WatchHistoryStoreTest {
    @Test public void deletesOnlyEntriesWatchedWithinSelectedPeriod() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.edit().putString("order", "recent,older")
                .putString("video_recent", "{\"l\":200}")
                .putString("video_older", "{\"l\":50}").apply();

        assertEquals(Collections.singletonList("recent"),
                WatchHistoryStore.deleteSince(preferences, 100));
        assertEquals("older", preferences.getString("order", ""));
        assertFalse(preferences.contains("video_recent"));
        assertTrue(preferences.contains("video_older"));

        preferences.edit().putString("video_orphan", "{\"l\":20}").apply();
        assertEquals(Arrays.asList("older", "orphan"),
                WatchHistoryStore.deleteSince(preferences, 0));
        assertEquals("", preferences.getString("order", ""));
        assertFalse(preferences.contains("video_older"));
        assertFalse(preferences.contains("video_orphan"));
    }
}
