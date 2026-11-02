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
}
