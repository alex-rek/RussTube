package ru.tubetv.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps the TV home rows independent while applying the same watched-video rule. */
final class TvHomeFeed {
    static final String RECOMMENDED = "recommended";
    static final String RECENT = "recent";
    static final String RUTUBE = "RUTUBE";
    static final String VK = "VK VIDEO";
    static final String OK = "OK";
    static final String DZEN = "ДЗЕН";
    static final String PEERTUBE = PeerTubeClient.SOURCE;
    static final List<String> ORDER = Arrays.asList(
            RECOMMENDED, RECENT, RUTUBE, VK, OK, DZEN, PEERTUBE);

    static Map<String, List<VideoItem>> build(List<VideoItem> subscriptions,
            List<VideoItem> candidates, List<VideoItem> history,
            Set<String> unavailable, Set<String> enabledSources,
            int limit, boolean recommendHistory) {
        Set<String> watched = new HashSet<>();
        for (VideoItem item : history) watched.add(item.stableKey());
        Map<String, List<VideoItem>> rows = new LinkedHashMap<>();
        rows.put(RECOMMENDED, select(HomeFeed.combine(subscriptions, candidates, history,
                recommendHistory), watched, unavailable, limit));
        rows.put(RECENT, select(SubscriptionFeed.limit(subscriptions, history),
                watched, unavailable, limit));
        for (String source : ORDER.subList(2, ORDER.size())) {
            List<VideoItem> fromSource = new ArrayList<>();
            if (enabledSources.contains(source)) for (VideoItem item : candidates)
                if (source.equals(item.source)) fromSource.add(item);
            List<VideoItem> ranked = RecommendationEngine.rank(fromSource, history);
            // Keep recommendations for equally dated videos, but place newer uploads first.
            Collections.sort(ranked, (a, b) -> Long.compare(b.publishedAtMs, a.publishedAtMs));
            rows.put(source, select(ranked,
                    watched, unavailable, limit));
        }
        return rows;
    }

    /** Keep visible positions fixed while other sources finish; remove newly watched items. */
    static Map<String, List<VideoItem>> freezeVisible(Map<String, List<VideoItem>> visible,
            Map<String, List<VideoItem>> latest, Set<String> hidden) {
        Map<String, List<VideoItem>> result = new LinkedHashMap<>();
        for (String section : ORDER) {
            List<VideoItem> existing = visible.get(section);
            List<VideoItem> kept = new ArrayList<>();
            if (existing != null) for (VideoItem item : existing)
                if (!hidden.contains(item.stableKey())) kept.add(item);
            List<VideoItem> replacement = latest.get(section);
            if (kept.isEmpty() && replacement != null)
                for (VideoItem item : replacement)
                    if (!hidden.contains(item.stableKey())) kept.add(item);
            result.put(section, kept);
        }
        return result;
    }

    private static List<VideoItem> select(List<VideoItem> candidates, Set<String> watched,
            Set<String> unavailable, int limit) {
        LinkedHashMap<String, VideoItem> unique = new LinkedHashMap<>();
        for (VideoItem item : candidates) {
            if (unique.size() >= limit) break;
            if (item == null || item.isLive || watched.contains(item.stableKey())
                    || unavailable.contains(item.stableKey())
                    || !VideoVisibility.showInDiscovery(item)) continue;
            unique.put(item.stableKey(), item);
        }
        return new ArrayList<>(unique.values());
    }

    private TvHomeFeed() { }
}
