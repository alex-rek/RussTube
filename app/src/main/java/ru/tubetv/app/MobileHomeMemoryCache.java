package ru.tubetv.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps the mobile Home selection and tablet rows for the current app process. */
final class MobileHomeMemoryCache {
    private static Snapshot snapshot;

    static synchronized Snapshot get(String signature) {
        return snapshot != null && snapshot.signature.equals(signature) ? snapshot : null;
    }

    static synchronized void save(String signature, List<VideoItem> candidates,
            List<VideoItem> subscriptions, List<VideoItem> visible,
            Map<String, List<VideoItem>> rows, long updatedAtMs) {
        snapshot = new Snapshot(signature, candidates, subscriptions, visible, rows, updatedAtMs);
    }

    static final class Snapshot {
        final String signature;
        final List<VideoItem> candidates;
        final List<VideoItem> subscriptions;
        final List<VideoItem> visible;
        final Map<String, List<VideoItem>> rows;
        final long updatedAtMs;

        Snapshot(String signature, List<VideoItem> candidates,
                List<VideoItem> subscriptions, List<VideoItem> visible,
                Map<String, List<VideoItem>> rows, long updatedAtMs) {
            this.signature = signature;
            this.candidates = new ArrayList<>(candidates);
            this.subscriptions = new ArrayList<>(subscriptions);
            this.visible = new ArrayList<>(visible);
            this.rows = new LinkedHashMap<>();
            for (Map.Entry<String, List<VideoItem>> entry : rows.entrySet())
                this.rows.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            this.updatedAtMs = updatedAtMs;
        }

        boolean isReady() { return updatedAtMs > 0; }
    }

    private MobileHomeMemoryCache() { }
}
