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

    @Test public void thematicRowsFollowSelectedTopicsAndHideWatchedVideos() {
        VideoItem watched = video("RUTUBE", "watched-science", 30);
        VideoItem older = video("VK VIDEO", "older-science", 10);
        VideoItem newer = video("RUTUBE", "newer-science", 20);
        VideoItem cooking = video("OK", "cooking", 15);
        Map<String, List<VideoItem>> found = new java.util.LinkedHashMap<>();
        found.put("Наука", Arrays.asList(watched, older, newer));
        found.put("Кулинария", Collections.singletonList(cooking));
        List<String> selected = Arrays.asList("Наука", "Кулинария");
        Map<String, List<VideoItem>> rows = TvHomeFeed.buildThematic(found, selected,
                Collections.singletonList(watched), Collections.emptySet(), 10);
        assertEquals(TvHomeFeed.topicOrder(selected), new ArrayList<>(rows.keySet()));
        assertEquals(Arrays.asList(newer, older), rows.get(TvHomeFeed.topicKey("Наука")));
        assertEquals(Collections.singletonList(cooking),
                rows.get(TvHomeFeed.topicKey("Кулинария")));
        assertFalse(rows.containsKey(TvHomeFeed.RUTUBE));
    }

    @Test public void thematicRowLimitsEachChannelToThreeVideos() {
        List<VideoItem> news = new ArrayList<>();
        for (int i = 0; i < 5; i++) news.add(video("VK VIDEO", "news" + i, 20 + i)
                .withChannel("rt", "Новости", "", false));
        VideoItem another = video("RUTUBE", "another", 10)
                .withChannel("other", "Другой канал", "", false);
        news.add(another);
        Map<String, List<VideoItem>> rows = TvHomeFeed.buildThematic(
                Collections.singletonMap("Новости", news),
                Collections.singletonList("Новости"), Collections.emptyList(),
                Collections.emptySet(), 10);
        List<VideoItem> selected = rows.get(TvHomeFeed.topicKey("Новости"));
        assertEquals(4, selected.size());
        assertEquals("news4", selected.get(0).title);
        assertEquals(another, selected.get(3));
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

    @Test public void refreshedRowAddsOnlyNewVideosBeforeSessionOrder() {
        VideoItem first = video("RUTUBE", "first", 10);
        VideoItem second = video("RUTUBE", "second", 20);
        VideoItem fresh = video("RUTUBE", "fresh", 30);
        Map<String, List<VideoItem>> shown = Collections.singletonMap(TvHomeFeed.RUTUBE,
                Arrays.asList(second, first));
        Map<String, List<VideoItem>> latest = Collections.singletonMap(TvHomeFeed.RUTUBE,
                Arrays.asList(fresh, first, second));
        Map<String, List<VideoItem>> merged = TvHomeFeed.mergeNew(shown, latest,
                Collections.emptySet(), Collections.singletonList(TvHomeFeed.RUTUBE), 3);
        assertEquals(Arrays.asList(fresh, second, first), merged.get(TvHomeFeed.RUTUBE));
        merged = TvHomeFeed.mergeNew(merged, latest, Collections.singleton(second.stableKey()),
                Collections.singletonList(TvHomeFeed.RUTUBE), 3);
        assertEquals(Arrays.asList(fresh, first), merged.get(TvHomeFeed.RUTUBE));
    }
}
