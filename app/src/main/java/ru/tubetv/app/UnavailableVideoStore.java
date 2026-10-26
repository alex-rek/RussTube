package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Videos that failed to resolve or play, kept out of discovery feeds. */
final class UnavailableVideoStore {
    private static final String PREFS = "unavailable_videos";
    private static final String KEY = "video_keys";

    static boolean contains(Context context, VideoItem item) {
        return context.getSharedPreferences(PREFS, 0).getStringSet(KEY, new HashSet<>())
                .contains(item.stableKey());
    }

    static Set<String> keys(Context context) {
        return context.getSharedPreferences(PREFS, 0).getStringSet(KEY, new HashSet<>());
    }

    static void add(Context context, VideoItem item) {
        if (item == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        Set<String> keys = new HashSet<>(prefs.getStringSet(KEY, new HashSet<>()));
        if (keys.add(item.stableKey())) prefs.edit().putStringSet(KEY, keys).apply();
    }

    static int count(Context context) {
        return context.getSharedPreferences(PREFS, 0)
                .getStringSet(KEY, new HashSet<>()).size();
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, 0).edit().remove(KEY).apply();
    }

    private UnavailableVideoStore() { }
}
