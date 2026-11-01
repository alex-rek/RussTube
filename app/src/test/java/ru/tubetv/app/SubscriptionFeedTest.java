package ru.tubetv.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SubscriptionFeedTest {
    private static VideoItem video(String id, String channel, long published) {
        return new VideoItem("RUTUBE", id, "", "", "https://example.com/" + id,
                "https://example.com/" + id, 60_000)
                .withMetrics(10, published)
                .withChannel(channel, channel, "https://example.com/channel/" + channel, false);
    }

    @Test public void keepsThreeNewestUnwatchedPerChannel() {
        VideoItem watched = video("a5", "a", 5);
        List<VideoItem> result = SubscriptionFeed.limit(Arrays.asList(
                video("a1", "a", 1), video("b1", "b", 1),
                video("a2", "a", 2), video("a3", "a", 3),
                video("a4", "a", 4), watched, video("a4", "a", 4)),
                Collections.singletonList(watched));
        assertEquals(4, result.size());
        assertEquals("a4", result.get(0).title);
        assertEquals("a3", result.get(1).title);
        assertEquals("a2", result.get(2).title);
        assertEquals("b1", result.get(3).title);
    }
}
