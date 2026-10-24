package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Options used only by the mobile catalog. */
final class MobileBrowsePreferences {
    private static final String PREFS = "mobile_browse";

    static boolean sourceEnabled(Context context, String source) {
        return preferences(context).getBoolean("source_" + source, true);
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
        return preferences(context).getBoolean("recommend_subscriptions", false);
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
