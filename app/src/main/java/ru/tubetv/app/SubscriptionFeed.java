package ru.tubetv.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Limits each subscribed channel to its three newest distinct uploads. */
final class SubscriptionFeed {
    static List<VideoItem> limit(List<VideoItem> candidates, List<VideoItem> history) {
        List<VideoItem> newest = new ArrayList<>(candidates);
        Collections.sort(newest, (a, b) -> Long.compare(b.publishedAtMs, a.publishedAtMs));
        List<VideoItem> result = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        Set<String> seen = new HashSet<>();
        Set<String> watched = new HashSet<>();
        for (VideoItem item : history) watched.add(item.stableKey());
        for (VideoItem item : newest) {
            if (watched.contains(item.stableKey())) continue;
            if (!seen.add(item.stableKey())) continue;
            String channel = !item.channelId.isEmpty() ? item.channelId
                    : !item.channelUrl.isEmpty() ? item.channelUrl
                    : !item.channelName.isEmpty() ? item.channelName : item.pageUrl;
            String key = item.source + '|' + channel;
            int count = counts.containsKey(key) ? counts.get(key) : 0;
            if (count < 3) {
                result.add(item);
                counts.put(key, count + 1);
            }
        }
        return result;
    }

    private SubscriptionFeed() { }
}
