package ru.tubetv.app;

import android.content.Context;

import java.util.Locale;

/** Integer steps avoid floating-point drift in the TV scale selector. */
final class TvScalePreferences {
    static final String INTERFACE = "interface_scale_step";
    static final String GRID = "grid_scale_step";
    static final int DEFAULT_STEP = 10;
    static final int LAST_STEP = 20;

    static int getStep(Context context, String key) {
        return Math.max(0, Math.min(LAST_STEP, context.getSharedPreferences("tv_scale", 0)
                .getInt(key, DEFAULT_STEP)));
    }

    static float get(Context context, String key) {
        return (10 + getStep(context, key)) / 20f;
    }

    static String label(int step) {
        return String.format(Locale.ROOT, "%.2fx", (10 + step) / 20f);
    }

    static void setStep(Context context, String key, int step) {
        context.getSharedPreferences("tv_scale", 0).edit()
                .putInt(key, Math.max(0, Math.min(LAST_STEP, step))).apply();
    }

    private TvScalePreferences() { }
}
