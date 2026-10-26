package ru.tubetv.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Combines unseen uploads from subscribed channels with history recommendations. */
final class HomeFeed {
    private static final int SUBSCRIPTION_LIMIT = 30;
    private static final int FEED_LIMIT = 60;

    static List<VideoItem> combine(List<VideoItem> subscriptions, List<VideoItem> candidates,
                                   List<VideoItem> history, boolean recommendHistory) {
        Set<String> watched = new HashSet<>();
        for (VideoItem item : history) watched.add(item.stableKey());
        List<VideoItem> newest = new ArrayList<>(subscriptions);
        Collections.sort(newest, (a, b) -> Long.compare(b.publishedAtMs, a.publishedAtMs));
        LinkedHashMap<String, VideoItem> result = new LinkedHashMap<>();
        for (VideoItem item : newest) {
            if (result.size() >= SUBSCRIPTION_LIMIT) break;
            if (eligible(item, watched)) result.put(item.stableKey(), item);
        }
        if (recommendHistory) {
            for (VideoItem item : RecommendationEngine.rank(candidates, history)) {
                if (result.size() >= FEED_LIMIT) break;
                if (eligible(item, watched) && !result.containsKey(item.stableKey()))
                    result.put(item.stableKey(), item);
            }
        }
        return new ArrayList<>(result.values());
    }

    private static boolean eligible(VideoItem item, Set<String> watched) {
        return !item.isLive && !ShortsCatalog.accepts(item)
                && !watched.contains(item.stableKey());
    }

    private HomeFeed() { }
}
