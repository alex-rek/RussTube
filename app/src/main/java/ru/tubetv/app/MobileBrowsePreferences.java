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
        return Math.max(0, Math.min(5, preferences(context).getInt("shorts_preload_count", 2)));
    }

    static void setShortsPreloadCount(Context context, int count) {
        preferences(context).edit().putInt("shorts_preload_count", Math.max(0, Math.min(5, count))).apply();
    }

    /** 0: save traffic, 1: balanced, 2: fastest switching. */
    static int shortsPreloadMode(Context context) {
        return Math.max(0, Math.min(2, preferences(context).getInt("shorts_preload_mode", 1)));
    }

    static void setShortsPreloadMode(Context context, int mode) {
        preferences(context).edit().putInt("shorts_preload_mode", Math.max(0, Math.min(2, mode))).apply();
    }

    static boolean shortsWifiOnly(Context context) {
        return preferences(context).getBoolean("shorts_preload_wifi_only", false);
    }

    static void setShortsWifiOnly(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("shorts_preload_wifi_only", enabled).apply();
    }

    static int shortsCacheMb(Context context) {
        int value = preferences(context).getInt("shorts_cache_mb", 64);
        return value == 32 || value == 128 || value == 256 ? value : 64;
    }

    static void setShortsCacheMb(Context context, int megabytes) {
        if (megabytes != 32 && megabytes != 64 && megabytes != 128 && megabytes != 256)
            throw new IllegalArgumentException("Shorts cache size");
        preferences(context).edit().putInt("shorts_cache_mb", megabytes).apply();
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
