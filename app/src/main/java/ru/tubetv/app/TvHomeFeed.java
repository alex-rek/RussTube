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
    static final String INTERESTS = "interests";
    static final String RUTUBE = "RUTUBE";
    static final String VK = "VK VIDEO";
    static final String OK = "OK";
    static final String DZEN = "ДЗЕН";
    static final String PEERTUBE = PeerTubeClient.SOURCE;
    static final List<String> ORDER = Arrays.asList(
            RECOMMENDED, RECENT, RUTUBE, VK, OK, DZEN, PEERTUBE);
    static final List<String> TV_ORDER = Arrays.asList(
            RECOMMENDED, RECENT, INTERESTS, RUTUBE, VK, OK, DZEN, PEERTUBE);

    static String topicKey(String topic) { return "topic:" + topic; }

    static List<String> topicOrder(List<String> topics) {
        List<String> order = new ArrayList<>();
        for (String topic : topics) order.add(topicKey(topic));
        return order;
    }

    static Map<String, List<VideoItem>> buildThematic(
            Map<String, List<VideoItem>> byTopic, List<String> topics,
            List<VideoItem> history, Set<String> unavailable, int limit) {
        Set<String> watched = new HashSet<>();
        for (VideoItem item : history) watched.add(item.stableKey());
        Map<String, List<VideoItem>> rows = new LinkedHashMap<>();
        for (String topic : topics) {
            List<VideoItem> found = byTopic.get(topic);
            List<VideoItem> ranked = RecommendationEngine.rank(
                    found == null ? Collections.emptyList() : found, history);
            Collections.sort(ranked, (a, b) -> Long.compare(b.publishedAtMs, a.publishedAtMs));
            rows.put(topicKey(topic), selectThematic(ranked, watched, unavailable, limit));
        }
        return rows;
    }

    static Map<String, List<VideoItem>> build(List<VideoItem> subscriptions,
            List<VideoItem> candidates, List<VideoItem> history,
            Set<String> unavailable, Set<String> enabledSources,
            int limit, boolean recommendHistory) {
        return buildRows(subscriptions, candidates, history, unavailable,
                enabledSources, Collections.emptySet(), limit, recommendHistory, false);
    }

    /** TV Home keeps subscribed uploads in "New" and discovery in "Recommended". */
    static Map<String, List<VideoItem>> buildDistinct(List<VideoItem> subscriptions,
            List<VideoItem> candidates, List<VideoItem> history,
            Set<String> unavailable, Set<String> enabledSources,
            Set<String> subscribedChannels, int limit, boolean recommendHistory) {
        return buildRows(subscriptions, candidates, history, unavailable,
                enabledSources, subscribedChannels, limit, recommendHistory, true);
    }

    static Map<String, List<VideoItem>> buildCompact(List<VideoItem> subscriptions,
            List<VideoItem> candidates, Map<String, List<VideoItem>> byTopic,
            List<String> topics, List<VideoItem> history, Set<String> unavailable,
            Set<String> enabledSources, Set<String> subscribedChannels,
            int limit, boolean recommendHistory) {
        Map<String, List<VideoItem>> rows = buildDistinct(subscriptions, candidates,
                history, unavailable, enabledSources, subscribedChannels,
                limit, recommendHistory);
        Set<String> excluded = new HashSet<>();
        for (VideoItem item : rows.get(RECOMMENDED)) excluded.add(item.stableKey());
        for (VideoItem item : rows.get(RECENT)) excluded.add(item.stableKey());
        List<VideoItem> found = new ArrayList<>();
        for (String topic : topics) {
            List<VideoItem> videos = byTopic.get(topic);
            if (videos != null) found.addAll(videos);
        }
        List<VideoItem> ranked = RecommendationEngine.rank(found, history);
        Collections.sort(ranked, (a, b) -> Long.compare(b.publishedAtMs, a.publishedAtMs));
        Set<String> watched = new HashSet<>(excluded);
        for (VideoItem item : history) watched.add(item.stableKey());
        rows.put(INTERESTS, selectThematic(ranked, watched, unavailable, limit));
        return rows;
    }

    private static Map<String, List<VideoItem>> buildRows(List<VideoItem> subscriptions,
            List<VideoItem> candidates, List<VideoItem> history,
            Set<String> unavailable, Set<String> enabledSources,
            Set<String> subscribedChannels, int limit, boolean recommendHistory,
            boolean distinctRecommendations) {
        Set<String> watched = new HashSet<>();
        for (VideoItem item : history) watched.add(item.stableKey());
        Map<String, List<VideoItem>> rows = new LinkedHashMap<>();
        if (distinctRecommendations) {
            Set<String> subscriptionKeys = new HashSet<>();
            for (VideoItem item : subscriptions) subscriptionKeys.add(item.stableKey());
            List<VideoItem> discovery = new ArrayList<>();
            for (VideoItem item : candidates) {
                if (subscriptionKeys.contains(item.stableKey())) continue;
                if (!item.channelId.isEmpty()
                        && subscribedChannels.contains(item.channelKey())) continue;
                if (!item.channelUrl.isEmpty()
                        && subscribedChannels.contains(item.source + "\n" + item.channelUrl)) continue;
                discovery.add(item);
            }
            List<VideoItem> ranked = recommendHistory
                    ? RecommendationEngine.rank(discovery, history) : discovery;
            if (!recommendHistory) Collections.sort(ranked,
                    (a, b) -> Long.compare(b.publishedAtMs, a.publishedAtMs));
            rows.put(RECOMMENDED, select(ranked, watched, unavailable, limit));
        } else rows.put(RECOMMENDED, select(HomeFeed.combine(subscriptions, candidates,
                history, recommendHistory), watched, unavailable, limit));
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
        return freezeVisible(visible, latest, hidden, ORDER);
    }

    static Map<String, List<VideoItem>> freezeVisible(Map<String, List<VideoItem>> visible,
            Map<String, List<VideoItem>> latest, Set<String> hidden, List<String> order) {
        Map<String, List<VideoItem>> result = new LinkedHashMap<>();
        for (String section : order) {
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

    /** Keep the session's card order and place newly discovered uploads at the left. */
    static Map<String, List<VideoItem>> mergeNew(Map<String, List<VideoItem>> shown,
            Map<String, List<VideoItem>> latest, Set<String> hidden,
            List<String> order, int limit) {
        Map<String, List<VideoItem>> result = new LinkedHashMap<>();
        for (String section : order) {
            LinkedHashMap<String, VideoItem> old = new LinkedHashMap<>();
            List<VideoItem> previous = shown.get(section);
            if (previous != null) for (VideoItem item : previous)
                if (!hidden.contains(item.stableKey())) old.put(item.stableKey(), item);
            LinkedHashMap<String, VideoItem> merged = new LinkedHashMap<>();
            List<VideoItem> incoming = latest.get(section);
            if (incoming != null) for (VideoItem item : incoming)
                if (!hidden.contains(item.stableKey()) && !old.containsKey(item.stableKey()))
                    merged.put(item.stableKey(), item);
            merged.putAll(old);
            List<VideoItem> values = new ArrayList<>(merged.values());
            result.put(section, new ArrayList<>(values.subList(0,
                    Math.min(Math.max(0, limit), values.size()))));
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

    private static List<VideoItem> selectThematic(List<VideoItem> candidates,
            Set<String> watched, Set<String> unavailable, int limit) {
        Map<String, Integer> perChannel = new LinkedHashMap<>();
        List<VideoItem> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (VideoItem item : candidates) {
            if (selected.size() >= limit) break;
            if (item == null || item.isLive || watched.contains(item.stableKey())
                    || unavailable.contains(item.stableKey()) || !VideoVisibility.showInDiscovery(item)
                    || !seen.add(item.stableKey())) continue;
            String channel = !item.channelId.isEmpty() ? item.channelId : item.channelName;
            String channelKey = channel.isEmpty() ? item.stableKey() : item.source + "\n" + channel;
            int count = perChannel.containsKey(channelKey) ? perChannel.get(channelKey) : 0;
            if (count >= 3) continue;
            perChannel.put(channelKey, count + 1);
            selected.add(item);
        }
        return selected;
    }

    private TvHomeFeed() { }
}
