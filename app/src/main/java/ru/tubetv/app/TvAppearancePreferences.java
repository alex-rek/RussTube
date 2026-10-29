package ru.tubetv.app;

import android.content.Context;

/** Visual options for the TV shell; stored independently of mobile Aurora UI. */
final class TvAppearancePreferences {
    static final String[] THEMES = {"Кино", "Триколор", "Светлая"};

    static int theme(Context context) {
        return Math.max(0, Math.min(2, context.getSharedPreferences("tv_appearance", 0)
                .getInt("theme", 0)));
    }

    static void setTheme(Context context, int value) {
        context.getSharedPreferences("tv_appearance", 0).edit()
                .putInt("theme", Math.max(0, Math.min(2, value))).apply();
    }

    static final String[] ANIMATION_SPEEDS = {
            "0 — Выключена", "1 — Очень медленно", "2 — Медленно",
            "3 — Обычно", "4 — Быстро", "5 — Очень быстро"
    };
    static final String[] PANEL_TRANSPARENCIES = {
            "0%", "10%", "20%", "30%", "40%", "50%", "60%"
    };
    private static final int[] ANIMATION_DURATIONS_MS = {0, 700, 500, 340, 240, 160};

    static int animationSpeed(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("tv_appearance", 0);
        int fallback = preferences.getBoolean("animations", true) ? 3 : 0;
        return Math.max(0, Math.min(5, preferences.getInt("animation_speed", fallback)));
    }

    static void setAnimationSpeed(Context context, int speed) {
        context.getSharedPreferences("tv_appearance", 0).edit()
                .putInt("animation_speed", Math.max(0, Math.min(5, speed))).apply();
    }

    static int animationDurationMs(Context context) {
        return ANIMATION_DURATIONS_MS[animationSpeed(context)];
    }

    static int panelTransparency(Context context) {
        return Math.max(0, Math.min(PANEL_TRANSPARENCIES.length - 1,
                context.getSharedPreferences("tv_appearance", 0).getInt("panel_transparency", 0)));
    }

    static void setPanelTransparency(Context context, int level) {
        context.getSharedPreferences("tv_appearance", 0).edit()
                .putInt("panel_transparency", Math.max(0,
                        Math.min(PANEL_TRANSPARENCIES.length - 1, level))).apply();
    }

    static int panelSurface(Context context) {
        int alpha = 255 * (100 - panelTransparency(context) * 10) / 100;
        return (alpha << 24) | (surface(context) & 0x00ffffff);
    }

    static int background(Context context) {
        return theme(context) == 2 ? 0xffeaf2fa : theme(context) == 1 ? 0xff06152d : 0xff071426;
    }

    static int backgroundImage(Context context) {
        return theme(context) == 2 ? R.drawable.tv_theme_light
                : theme(context) == 1 ? R.drawable.tv_theme_tricolor : R.drawable.tv_theme_cinema;
    }

    static int surface(Context context) {
        return theme(context) == 2 ? 0xffd6e5f2 : theme(context) == 1 ? 0xff142d51 : 0xff10243b;
    }

    static int text(Context context) { return theme(context) == 2 ? 0xff10243b : 0xfff4f8ff; }
    static int secondary(Context context) { return theme(context) == 2 ? 0xff46566d : 0xffaeb9cc; }
    static int accent(Context context) {
        return theme(context) == 2 ? 0xff007e8d : theme(context) == 1 ? 0xff19c8ff : 0xff00d9c0;
    }

    static int focus(Context context) { return theme(context) == 2 ? 0xff9ee8e9 : 0xff07536a; }

    private TvAppearancePreferences() { }
}
