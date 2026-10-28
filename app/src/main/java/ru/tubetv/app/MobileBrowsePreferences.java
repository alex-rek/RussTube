package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Options used only by the mobile catalog. */
final class MobileBrowsePreferences {
    private static final String PREFS = "mobile_browse";

    static boolean sourceEnabled(Context context, String source) {
        return sourceEnabled(preferences(context), source);
    }

    static boolean sourceEnabled(SharedPreferences prefs, String source) {
        return prefs.getBoolean("source_" + source, defaultSourceEnabled(source));
    }

    static boolean defaultSourceEnabled(String source) {
        return !PeerTubeClient.SOURCE.equals(source);
    }

    static String[] enabledSources(Context context, boolean live) {
        boolean[] flags = new boolean[FeedRepository.SOURCES.length];
        for (int i = 0; i < flags.length; i++)
            flags[i] = sourceEnabled(context, FeedRepository.SOURCES[i]);
        return selectedSources(flags, live);
    }

    static String[] selectedSources(boolean[] enabled, boolean live) {
        if (enabled.length != FeedRepository.SOURCES.length)
            throw new IllegalArgumentException("Source count");
        java.util.ArrayList<String> selected = new java.util.ArrayList<>();
        for (int i = 0; i < enabled.length; i++) {
            String source = FeedRepository.SOURCES[i];
            if (enabled[i] && (!live || FeedRepository.supportsLive(source))) selected.add(source);
        }
        return selected.toArray(new String[0]);
    }

    static boolean setSources(Context context, boolean[] selected) {
        return setSources(preferences(context), selected);
    }

    static boolean setSources(SharedPreferences prefs, boolean[] selected) {
        if (selected.length != FeedRepository.SOURCES.length)
            throw new IllegalArgumentException("Source count");
        boolean any = false;
        for (boolean value : selected) any |= value;
        if (!any) return false;
        SharedPreferences.Editor edit = prefs.edit();
        for (int i = 0; i < selected.length; i++)
            edit.putBoolean("source_" + FeedRepository.SOURCES[i], selected[i]);
        edit.apply();
        return true;
    }

    static boolean setSourceEnabled(Context context, String source, boolean enabled) {
        if (!enabled) {
            int remaining = 0;
            for (String candidate : FeedRepository.SOURCES) {
                if (!candidate.equals(source) && sourceEnabled(context, candidate)) remaining++;
            }
            if (remaining == 0) return false;
        }
        preferences(context).edit().putBoolean("source_" + source, enabled).apply();
        return true;
    }

    static boolean recommendHistory(Context context) {
        return preferences(context).getBoolean("recommend_history",
                context.getSharedPreferences("browse", Context.MODE_PRIVATE)
                        .getBoolean("recommendations", true));
    }

    static boolean recommendSubscriptions(Context context) {
        return preferences(context).getBoolean("recommend_subscriptions", true);
    }

    static void setRecommendHistory(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("recommend_history", enabled).apply();
    }

    static void setRecommendSubscriptions(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("recommend_subscriptions", enabled).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private MobileBrowsePreferences() { }
}
