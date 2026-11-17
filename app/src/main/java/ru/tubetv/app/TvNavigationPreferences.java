package ru.tubetv.app;

import android.content.Context;

/** Preferences for the TV navigation rail. HOME and SETTINGS always remain available. */
final class TvNavigationPreferences {
    private static final String FILE = "tv_navigation";
    private static final String START = "start_page";

    private TvNavigationPreferences() { }

    static String startPage(Context context) {
        return context.getSharedPreferences(FILE, 0).getString(START, "HOME");
    }

    static void setStartPage(Context context, String page) {
        context.getSharedPreferences(FILE, 0).edit().putString(START, page)
                .putBoolean("show_" + page, true).apply();
    }

    static boolean visible(Context context, String page) {
        return "HOME".equals(page) || page.equals(startPage(context))
                || context.getSharedPreferences(FILE, 0).getBoolean("show_" + page, true);
    }

    static void setVisible(Context context, String page, boolean visible) {
        if ("HOME".equals(page) || page.equals(startPage(context))) return;
        context.getSharedPreferences(FILE, 0).edit()
                .putBoolean("show_" + page, visible).apply();
    }
}
