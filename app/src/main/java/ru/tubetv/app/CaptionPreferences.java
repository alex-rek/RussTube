package ru.tubetv.app;

import android.content.Context;

/** Shared subtitle presentation options; each application flavor has its own data. */
final class CaptionPreferences {
    static final String[] LANGUAGES = {"Авто", "Русский", "English"};
    static final String[] LANGUAGE_CODES = {"", "ru", "en"};
    static final String[] SIZES = {"Маленький", "Обычный", "Крупный"};
    static final int[] SIZE_SP = {16, 22, 28};
    static final String[] COLORS = {"Белый", "Жёлтый", "Бирюзовый"};
    static final int[] COLOR_VALUES = {0xffffffff, 0xffffff66, 0xff00d9c0};

    static int language(Context context) { return get(context, "language", LANGUAGES.length, 0); }
    static int size(Context context) { return get(context, "size", SIZES.length, 1); }
    static int color(Context context) { return get(context, "color", COLORS.length, 0); }

    static void setLanguage(Context context, int value) { put(context, "language", value); }
    static void setSize(Context context, int value) { put(context, "size", value); }
    static void setColor(Context context, int value) { put(context, "color", value); }

    private static int get(Context context, String key, int count, int fallback) {
        int value = context.getSharedPreferences("captions", 0).getInt(key, fallback);
        return Math.max(0, Math.min(count - 1, value));
    }

    private static void put(Context context, String key, int value) {
        context.getSharedPreferences("captions", 0).edit().putInt(key, value).apply();
    }

    private CaptionPreferences() { }
}
