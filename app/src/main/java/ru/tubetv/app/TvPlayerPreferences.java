package ru.tubetv.app;

import android.content.Context;

final class TvPlayerPreferences {
    static final String[] CLOCK_SIZES = {"16 sp", "20 sp", "24 sp", "28 sp", "32 sp"};
    static final int[] CLOCK_SIZE_SP = {16, 20, 24, 28, 32};

    static boolean showClock(Context context) {
        return context.getSharedPreferences("tv_player", 0).getBoolean("show_clock", true);
    }

    static void setShowClock(Context context, boolean enabled) {
        context.getSharedPreferences("tv_player", 0).edit().putBoolean("show_clock", enabled).apply();
    }

    static int clockSize(Context context) {
        return Math.max(0, Math.min(CLOCK_SIZES.length - 1,
                context.getSharedPreferences("tv_player", 0).getInt("clock_size", 2)));
    }

    static void setClockSize(Context context, int index) {
        context.getSharedPreferences("tv_player", 0).edit().putInt("clock_size", index).apply();
    }

    private TvPlayerPreferences() { }
}
