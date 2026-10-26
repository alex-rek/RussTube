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
        return preferences(context).getBoolean("recommend_subscriptions", true);
    }

    static void setRecommendHistory(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("recommend_history", enabled).apply();
    }

    static void setRecommendSubscriptions(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("recommend_subscriptions", enabled).apply();
    }

    static boolean shortsAutoplay(Context context) {
        return preferences(context).getBoolean("shorts_autoplay", true);
    }

    static void setShortsAutoplay(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("shorts_autoplay", enabled).apply();
    }

    static boolean shortsShowInfo(Context context) {
        return preferences(context).getBoolean("shorts_show_info", true);
    }

    static void setShortsShowInfo(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("shorts_show_info", enabled).apply();
    }

    static boolean shortsLoop(Context context) {
        return preferences(context).getBoolean("shorts_loop", true);
    }

    static void setShortsLoop(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("shorts_loop", enabled).apply();
    }

    static int shortsPreloadCount(Context context) {
        return Math.max(0, Math.min(5, preferences(context).getInt("shorts_preload_count", 5)));
    }

    static void setShortsPreloadCount(Context context, int count) {
        preferences(context).edit().putInt("shorts_preload_count", Math.max(0, Math.min(5, count))).apply();
    }

    /** 0: 9:16, 1: 4:5, 2: available feed height. */
    static int shortsFrameRatio(Context context) {
        return Math.max(0, Math.min(2, preferences(context).getInt("shorts_frame_ratio", 0)));
    }

    static void setShortsFrameRatio(Context context, int ratio) {
        preferences(context).edit().putInt("shorts_frame_ratio", Math.max(0, Math.min(2, ratio))).apply();
    }

    static boolean shortsFill(Context context) {
        return preferences(context).getBoolean("shorts_fill", true);
    }

    static void setShortsFill(Context context, boolean fill) {
        preferences(context).edit().putBoolean("shorts_fill", fill).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private MobileBrowsePreferences() { }
}
