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

    static boolean symbols(Context context) {
        return context.getSharedPreferences("tv_appearance", 0)
                .getBoolean("symbols", true);
    }

    static void setSymbols(Context context, boolean value) {
        context.getSharedPreferences("tv_appearance", 0).edit()
                .putBoolean("symbols", value).apply();
    }

    static boolean animations(Context context) {
        return context.getSharedPreferences("tv_appearance", 0)
                .getBoolean("animations", true);
    }

    static void setAnimations(Context context, boolean value) {
        context.getSharedPreferences("tv_appearance", 0).edit()
                .putBoolean("animations", value).apply();
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
