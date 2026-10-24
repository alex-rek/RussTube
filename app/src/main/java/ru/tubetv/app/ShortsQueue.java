package ru.tubetv.app;

import java.util.ArrayList;
import java.util.List;

/** A small in-memory snapshot for swiping within the open Shorts feed. */
final class ShortsQueue {
    private static final List<VideoItem> items = new ArrayList<>();

    static synchronized void set(List<VideoItem> feed) {
        items.clear();
        items.addAll(feed);
    }

    static synchronized VideoItem adjacent(VideoItem current, int direction) {
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).stableKey().equals(current.stableKey())) continue;
            int next = i + direction;
            return next >= 0 && next < items.size() ? items.get(next) : null;
        }
        return null;
    }

    private ShortsQueue() { }
}
