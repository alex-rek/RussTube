package ru.tubetv.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class HomeFeedTest {
    private static VideoItem video(String name, long published) {
        return new VideoItem("RUTUBE", name, "", "", "https://example.com/" + name,
                "https://example.com/" + name, 20 * 60_000L)
                .withMetrics(10, published);
    }

    @Test public void subscriptionsAppearAlongsideRecommendationsWithoutWatchedVideos() {
        VideoItem watched = video("история науки", 10);
        VideoItem subscribed = video("новая публикация", 30);
        VideoItem recommendation = video("новая наука", 20);
        List<VideoItem> result = HomeFeed.combine(
                Arrays.asList(watched, subscribed, subscribed),
                Arrays.asList(recommendation, subscribed),
                Collections.singletonList(watched), true);
        assertEquals(2, result.size());
        assertEquals(subscribed.stableKey(), result.get(0).stableKey());
        assertEquals(recommendation.stableKey(), result.get(1).stableKey());
    }

    @Test public void historyCanBeDisabledWithoutHidingSubscriptions() {
        VideoItem subscribed = video("свежий выпуск", 30);
        List<VideoItem> result = HomeFeed.combine(
                Collections.singletonList(subscribed),
                Collections.singletonList(video("другая тема", 20)),
                Collections.emptyList(), false);
        assertEquals(1, result.size());
        assertEquals(subscribed.stableKey(), result.get(0).stableKey());
    }
}
