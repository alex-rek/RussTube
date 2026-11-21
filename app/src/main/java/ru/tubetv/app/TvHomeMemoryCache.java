package ru.tubetv.app;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Process-local catalog snapshot so returning to Home never waits for network. */
final class TvHomeMemoryCache {
    private static final long FRESH_MS = 5 * 60 * 1000L;
    private static Snapshot snapshot;

    static synchronized Snapshot get(String signature) {
        return snapshot != null && snapshot.signature.equals(signature) ? snapshot : null;
    }

    static synchronized void save(String signature, List<VideoItem> candidates,
            List<VideoItem> subscriptions, long nowMs) {
        save(signature, candidates, subscriptions, new LinkedHashMap<>(), nowMs);
    }

    static synchronized void save(String signature, List<VideoItem> candidates,
            List<VideoItem> subscriptions, Map<String, List<VideoItem>> topics, long nowMs) {
        save(signature, candidates, subscriptions, topics, new LinkedHashMap<>(), nowMs);
    }

    static synchronized void save(String signature, List<VideoItem> candidates,
            List<VideoItem> subscriptions, Map<String, List<VideoItem>> topics,
            Map<String, List<VideoItem>> rows, long nowMs) {
        snapshot = new Snapshot(signature, candidates, subscriptions, topics, rows, nowMs);
    }

    static final class Snapshot {
        final String signature;
        final List<VideoItem> candidates;
        final List<VideoItem> subscriptions;
        final Map<String, List<VideoItem>> topics;
        final Map<String, List<VideoItem>> rows;
        final long updatedAtMs;

        Snapshot(String signature, List<VideoItem> candidates,
                List<VideoItem> subscriptions, long updatedAtMs) {
            this(signature, candidates, subscriptions, new LinkedHashMap<>(), updatedAtMs);
        }

        Snapshot(String signature, List<VideoItem> candidates,
                List<VideoItem> subscriptions, Map<String, List<VideoItem>> topics,
                long updatedAtMs) {
            this(signature, candidates, subscriptions, topics, new LinkedHashMap<>(), updatedAtMs);
        }

        Snapshot(String signature, List<VideoItem> candidates,
                List<VideoItem> subscriptions, Map<String, List<VideoItem>> topics,
                Map<String, List<VideoItem>> rows, long updatedAtMs) {
            this.signature = signature;
            this.candidates = new ArrayList<>(candidates);
            this.subscriptions = new ArrayList<>(subscriptions);
            this.topics = new LinkedHashMap<>();
            for (Map.Entry<String, List<VideoItem>> entry : topics.entrySet())
                this.topics.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            this.rows = new LinkedHashMap<>();
            for (Map.Entry<String, List<VideoItem>> entry : rows.entrySet())
                this.rows.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            this.updatedAtMs = updatedAtMs;
        }

        boolean isFresh(long nowMs) {
            return updatedAtMs > 0 && nowMs >= updatedAtMs
                    && nowMs - updatedAtMs < FRESH_MS;
        }
    }

    private TvHomeMemoryCache() { }
}
