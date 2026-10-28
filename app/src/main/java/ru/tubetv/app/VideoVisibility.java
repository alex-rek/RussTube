package ru.tubetv.app;

import java.util.Locale;

/** Excludes known subscription-only channels from discovery without deleting user data. */
final class VideoVisibility {
    static boolean showInDiscovery(VideoItem item) {
        if (item == null || !"RUTUBE".equals(item.source)) return true;
        String channel = item.channelName == null ? "" : item.channelName.toLowerCase(Locale.ROOT)
                .replace('×', 'x').replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return !channel.equals("premier") && !channel.equals("rutube x premier")
                && !channel.equals("rutube premier") && !channel.equals("premier rutube");
    }

    private VideoVisibility() { }
}
