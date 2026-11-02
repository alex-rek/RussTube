package ru.tubetv.app;

import android.content.Context;

final class TvHomePreferences {
    static final int[] COUNTS = {10, 20, 30};
    private static final String PREFS = "browse";
    private static final String KEY = "tv_home_row_count";

    static int count(Context context) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 20);
        return value == 10 || value == 20 || value == 30 ? value : 20;
    }

    static void setCount(Context context, int value) {
        if (value != 10 && value != 20 && value != 30) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, value).apply();
    }

    private TvHomePreferences() { }
}
