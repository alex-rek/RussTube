package ru.tubetv.app;

import java.util.ArrayList;
import java.util.List;

/** Process-local catalog snapshot so returning to Home never waits for network. */
final class TvHomeMemoryCache {
    private static final long FRESH_MS = 5 * 60 * 1000L;
    private static Snapshot snapshot;

    static synchronized Snapshot get(String signature) {
        return snapshot != null && snapshot.signature.equals(signature) ? snapshot : null;
    }

    static synchronized void save(String signature, List<VideoItem> candidates,
            List<VideoItem> subscriptions, long nowMs) {
        snapshot = new Snapshot(signature, candidates, subscriptions, nowMs);
    }

    static final class Snapshot {
        final String signature;
        final List<VideoItem> candidates;
        final List<VideoItem> subscriptions;
        final long updatedAtMs;

        Snapshot(String signature, List<VideoItem> candidates,
                List<VideoItem> subscriptions, long updatedAtMs) {
            this.signature = signature;
            this.candidates = new ArrayList<>(candidates);
            this.subscriptions = new ArrayList<>(subscriptions);
            this.updatedAtMs = updatedAtMs;
        }

        boolean isFresh(long nowMs) {
            return updatedAtMs > 0 && nowMs >= updatedAtMs
                    && nowMs - updatedAtMs < FRESH_MS;
        }
    }

    private TvHomeMemoryCache() { }
}
