package ru.tubetv.app;

import android.content.Context;

/** Keeps the selected ordering independent for each catalogue section. */
final class FeedSortPreferences {
    static VideoSort.Mode get(Context context, String section) {
        if ("HISTORY".equals(section)) return VideoSort.Mode.ORIGINAL;
        VideoSort.Mode fallback = "POPULAR".equals(section)
                ? VideoSort.Mode.VIEWS : VideoSort.Mode.NEWEST;
        return VideoSort.parse(context.getSharedPreferences("browse", 0)
                .getString("video_sort_" + section, fallback.name()));
    }

    static void set(Context context, String section, VideoSort.Mode mode) {
        if ("HISTORY".equals(section)) return;
        context.getSharedPreferences("browse", 0).edit()
                .putString("video_sort_" + section, mode.name()).apply();
    }

    private FeedSortPreferences() { }
}
