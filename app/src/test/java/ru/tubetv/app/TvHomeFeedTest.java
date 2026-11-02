package ru.tubetv.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TvHomeFeedTest {
    private static VideoItem video(String source, String id, long published) {
        return new VideoItem(source, id, "", "", "https://example.com/" + id,
                "https://example.com/" + id, 60_000).withMetrics(100, published);
    }

    @Test public void rowsRemainSourceSpecificAndExcludeWatchedAndUnavailable() {
        VideoItem watched = video("RUTUBE", "watched", 1);
        VideoItem unavailable = video("VK VIDEO", "unavailable", 2);
        VideoItem rutube = video("RUTUBE", "rutube", 3);
        VideoItem vk = video("VK VIDEO", "vk", 4);
        Map<String, List<VideoItem>> rows = TvHomeFeed.build(Collections.emptyList(),
                Arrays.asList(watched, unavailable, rutube, vk),
                Collections.singletonList(watched),
                Collections.singleton(unavailable.stableKey()),
                new HashSet<>(Arrays.asList("RUTUBE", "VK VIDEO")), 10, true);
        assertEquals(1, rows.get(TvHomeFeed.RUTUBE).size());
        assertEquals(rutube.stableKey(), rows.get(TvHomeFeed.RUTUBE).get(0).stableKey());
        assertEquals(1, rows.get(TvHomeFeed.VK).size());
        assertEquals(vk.stableKey(), rows.get(TvHomeFeed.VK).get(0).stableKey());
        assertTrue(rows.get(TvHomeFeed.OK).isEmpty());
        assertFalse(rows.get(TvHomeFeed.RECOMMENDED).contains(watched));
    }

    @Test public void eachRowHasItsOwnLimitAndSubscriptionsKeepNewestFirst() {
        List<VideoItem> uploads = new ArrayList<>();
        List<VideoItem> candidates = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            uploads.add(video("RUTUBE", "upload" + i, i)
                    .withChannel("channel" + i, "channel" + i, "", false));
            candidates.add(video("VK VIDEO", "result" + i, i));
        }
        Map<String, List<VideoItem>> rows = TvHomeFeed.build(uploads, candidates,
                Collections.emptyList(), Collections.emptySet(),
                new HashSet<>(Arrays.asList("RUTUBE", "VK VIDEO")), 20, true);
        assertEquals(20, rows.get(TvHomeFeed.RECENT).size());
        assertEquals("upload34", rows.get(TvHomeFeed.RECENT).get(0).title);
        assertEquals(20, rows.get(TvHomeFeed.VK).size());
        assertEquals(20, rows.get(TvHomeFeed.RECOMMENDED).size());
    }

    @Test public void newSourceVideosAreOnTheLeftRegardlessOfArrivalOrder() {
        VideoItem older = video("RUTUBE", "older", 10);
        VideoItem newer = video("RUTUBE", "newer", 20);
        Map<String, List<VideoItem>> rows = TvHomeFeed.build(Collections.emptyList(),
                Arrays.asList(older, newer), Collections.emptyList(), Collections.emptySet(),
                Collections.singleton("RUTUBE"), 10, true);
        assertEquals(newer.stableKey(), rows.get(TvHomeFeed.RUTUBE).get(0).stableKey());
        assertEquals(older.stableKey(), rows.get(TvHomeFeed.RUTUBE).get(1).stableKey());
    }

    @Test public void visibleRowKeepsItsOrderUntilNextVisitAndHidesWatched() {
        VideoItem older = video("RUTUBE", "older", 10);
        VideoItem newer = video("RUTUBE", "newer", 20);
        Map<String, List<VideoItem>> shown = Collections.singletonMap(TvHomeFeed.RUTUBE,
                Collections.singletonList(older));
        Map<String, List<VideoItem>> latest = Collections.singletonMap(TvHomeFeed.RUTUBE,
                Arrays.asList(newer, older));
        Map<String, List<VideoItem>> stable = TvHomeFeed.freezeVisible(shown, latest,
                Collections.emptySet());
        assertEquals(Collections.singletonList(older), stable.get(TvHomeFeed.RUTUBE));
        Map<String, List<VideoItem>> afterWatch = TvHomeFeed.freezeVisible(stable, latest,
                Collections.singleton(older.stableKey()));
        assertEquals(Collections.singletonList(newer), afterWatch.get(TvHomeFeed.RUTUBE));
    }

    @Test public void memorySnapshotExpiresAfterFiveMinutes() {
        TvHomeMemoryCache.Snapshot snapshot = new TvHomeMemoryCache.Snapshot("sources",
                Collections.singletonList(video("RUTUBE", "one", 1)),
                Collections.emptyList(), 1_000);
        assertTrue(snapshot.isFresh(1_000));
        assertTrue(snapshot.isFresh(300_999));
        assertFalse(snapshot.isFresh(301_000));
        TvHomeMemoryCache.Snapshot partial = new TvHomeMemoryCache.Snapshot("sources",
                Collections.singletonList(video("RUTUBE", "one", 1)),
                Collections.emptyList(), 0);
        assertFalse(partial.isFresh(60_000));
    }

    @Test public void nextReadyRowAppearsWithoutReorderingVisibleRow() {
        VideoItem rutube = video("RUTUBE", "rutube", 10);
        VideoItem vk = video("VK VIDEO", "vk", 20);
        Map<String, List<VideoItem>> first = Collections.singletonMap(TvHomeFeed.RUTUBE,
                Collections.singletonList(rutube));
        Map<String, List<VideoItem>> latest = new java.util.LinkedHashMap<>();
        latest.put(TvHomeFeed.RUTUBE, Collections.singletonList(rutube));
        latest.put(TvHomeFeed.VK, Collections.singletonList(vk));
        Map<String, List<VideoItem>> shown = TvHomeFeed.freezeVisible(first, latest,
                Collections.emptySet());
        assertEquals(Collections.singletonList(rutube), shown.get(TvHomeFeed.RUTUBE));
        assertEquals(Collections.singletonList(vk), shown.get(TvHomeFeed.VK));
    }
}
