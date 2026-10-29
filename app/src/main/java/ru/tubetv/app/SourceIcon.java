package ru.tubetv.app;

/** Transparent platform artwork shown on catalog cards. */
final class SourceIcon {
    static int drawable(VideoItem item) {
        if (item.isLive && "VK VIDEO".equals(item.source))
            return R.drawable.source_vk_video_live;
        return drawable(item.source);
    }

    static int drawable(String source) {
        if ("VK VIDEO".equals(source)) return R.drawable.source_vk_video;
        if ("RUTUBE".equals(source)) return R.drawable.source_rutube;
        if ("OK".equals(source)) return R.drawable.source_ok;
        if ("ДЗЕН".equals(source)) return R.drawable.source_dzen;
        if (PeerTubeClient.SOURCE.equals(source)) return R.drawable.source_peertube;
        return android.R.drawable.ic_media_play;
    }

    private SourceIcon() { }
}
