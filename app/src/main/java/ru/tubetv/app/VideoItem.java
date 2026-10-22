package ru.tubetv.app;

final class VideoItem {
    final String source;
    final String title;
    final String subtitle;
    final String thumbnail;
    final String playUrl;
    final String pageUrl;
    final long durationMs;
    final int maxWidth;
    final int maxHeight;
    final String channelId;
    final String channelName;
    final String channelUrl;
    final boolean isLive;
    final long views;
    final long publishedAtMs;

    VideoItem(String source, String title, String subtitle, String thumbnail,
              String playUrl, String pageUrl) {
        this(source, title, subtitle, thumbnail, playUrl, pageUrl, 0L);
    }

    VideoItem(String source, String title, String subtitle, String thumbnail,
              String playUrl, String pageUrl, long durationMs) {
        this.source = source;
        this.title = title;
        this.subtitle = subtitle;
        this.thumbnail = thumbnail;
        this.playUrl = playUrl;
        this.pageUrl = pageUrl;
        this.durationMs = Math.max(0L, durationMs);
        this.maxWidth = 0;
        this.maxHeight = 0;
        this.channelId = "";
        this.channelName = "";
        this.channelUrl = "";
        this.isLive = false;
        this.views = -1L;
        this.publishedAtMs = 0L;
    }

    private VideoItem(String source, String title, String subtitle, String thumbnail,
                      String playUrl, String pageUrl, long durationMs, int maxWidth, int maxHeight) {
        this(source, title, subtitle, thumbnail, playUrl, pageUrl, durationMs, maxWidth,
                maxHeight, "", "", "", false, -1L, 0L);
    }

    private VideoItem(String source, String title, String subtitle, String thumbnail,
                      String playUrl, String pageUrl, long durationMs, int maxWidth, int maxHeight,
                      String channelId, String channelName, String channelUrl, boolean isLive,
                      long views, long publishedAtMs) {
        this.source = source;
        this.title = title;
        this.subtitle = subtitle;
        this.thumbnail = thumbnail;
        this.playUrl = playUrl;
        this.pageUrl = pageUrl;
        this.durationMs = Math.max(0L, durationMs);
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.channelId = channelId == null ? "" : channelId;
        this.channelName = channelName == null ? "" : channelName;
        this.channelUrl = channelUrl == null ? "" : channelUrl;
        this.isLive = isLive;
        this.views = Math.max(-1L, views);
        this.publishedAtMs = Math.max(0L, publishedAtMs);
    }

    VideoItem withQuality(int width, int height) {
        return new VideoItem(source, title, subtitle, thumbnail, playUrl, pageUrl, durationMs,
                width, height, channelId, channelName, channelUrl, isLive, views, publishedAtMs);
    }

    VideoItem withDuration(long duration) {
        return new VideoItem(source, title, subtitle, thumbnail, playUrl, pageUrl,
                duration, maxWidth, maxHeight, channelId, channelName, channelUrl, isLive, views, publishedAtMs);
    }

    VideoItem withChannel(String id, String name, String url, boolean live) {
        return new VideoItem(source, title, subtitle, thumbnail, playUrl, pageUrl, durationMs,
                maxWidth, maxHeight, id, name, url, live, views, publishedAtMs);
    }

    VideoItem withMetrics(long count, long published) {
        return new VideoItem(source, title, subtitle, thumbnail, playUrl, pageUrl, durationMs,
                maxWidth, maxHeight, channelId, channelName, channelUrl, isLive, count, published);
    }

    String channelKey() { return source + "\n" + channelId; }

    String qualityLabel() { return qualityLabel(maxWidth); }

    private static String qualityLabel(int width) {
        return width >= 3840 ? "4K" : width >= 2560 ? "1440p"
                : width >= 1920 ? "1080p" : width >= 1280 ? "720p"
                : width > 0 ? width + "px" : "";
    }

    String stableKey() {
        if (pageUrl != null && !pageUrl.isEmpty()) return source + "\n" + pageUrl;
        return source + "\n" + playUrl;
    }
}
