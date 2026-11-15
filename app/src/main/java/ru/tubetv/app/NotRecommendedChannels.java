package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local channel exclusions. A video URL is never used as a channel identity. */
final class NotRecommendedChannels {
    private static final String PREFS = "not_recommended_channels";
    private static final String KEY = "channels";

    static boolean canExclude(VideoItem item) {
        return item != null && item.source != null && !item.source.isEmpty()
                && ((item.channelId != null && !item.channelId.isEmpty())
                || (item.channelUrl != null && !item.channelUrl.isEmpty()));
    }

    static boolean contains(Context context, VideoItem item) {
        return canExclude(item) && context.getSharedPreferences(PREFS, 0)
                .getStringSet(KEY, new HashSet<>()).contains(key(item));
    }

    static void set(Context context, VideoItem item, boolean excluded) {
        if (!canExclude(item)) return;
        SharedPreferences preferences = context.getSharedPreferences(PREFS, 0);
        Set<String> channels = new HashSet<>(preferences.getStringSet(KEY, new HashSet<>()));
        if (excluded ? channels.add(key(item)) : channels.remove(key(item)))
            preferences.edit().putStringSet(KEY, channels).apply();
    }

    static List<VideoItem> filter(Context context, List<VideoItem> items) {
        List<VideoItem> result = new ArrayList<>(items.size());
        Set<String> excluded = context.getSharedPreferences(PREFS, 0)
                .getStringSet(KEY, new HashSet<>());
        for (VideoItem item : items)
            if (item != null && (!canExclude(item) || !excluded.contains(key(item)))) result.add(item);
        return result;
    }

    private static String key(VideoItem item) {
        return item.source + "\n" + (item.channelId.isEmpty() ? item.channelUrl : item.channelId);
    }

    private NotRecommendedChannels() { }
}
