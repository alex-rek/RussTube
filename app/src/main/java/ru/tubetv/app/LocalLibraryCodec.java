package ru.tubetv.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Safe network projection of the existing v1 subscriptions/favorites backup. */
final class LocalLibraryCodec {
    static JSONObject export(Context context) throws Exception {
        return new JSONObject().put("format", "russtube-library").put("version", 1)
                .put("subscriptions", encode(SubscriptionStore.load(context), true))
                .put("favorites", encode(FavoriteStore.load(context), false));
    }

    static MobileLibraryTransfer.ImportData importData(JSONObject payload) throws Exception {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LocalLinkProtocol.MAX_FRAME_BYTES - 1024)
            throw new Exception("Библиотека слишком большая для обмена");
        MobileLibraryTransfer.ImportData parsed = MobileLibraryTransfer.read(
                new ByteArrayInputStream(bytes));
        java.util.List<VideoItem> channels = new java.util.ArrayList<>();
        java.util.List<VideoItem> favorites = new java.util.ArrayList<>();
        for (VideoItem item : parsed.channels) channels.add(sanitize(item, true));
        for (VideoItem item : parsed.favorites) favorites.add(sanitize(item, false));
        return new MobileLibraryTransfer.ImportData(channels, favorites);
    }

    private static JSONArray encode(java.util.List<VideoItem> items, boolean channels)
            throws Exception {
        JSONArray data = new JSONArray();
        for (VideoItem item : items) {
            String page = safeVideoPage(item.source, item.pageUrl);
            String play = "PEERTUBE".equals(item.source)
                    ? safeVideoPage(item.source, item.playUrl)
                    : (page.isEmpty() ? safeVideoPage(item.source, item.playUrl) : page);
            if (!channels && play.isEmpty()) continue;
            if (channels && item.channelId.isEmpty()) continue;
            JSONObject value = VideoJson.encode(item);
            value.put("play", play).put("page", page).put("thumbnail", "")
                    .put("channelUrl", safePage(item.channelUrl));
            data.put(value);
        }
        return data;
    }

    private static VideoItem sanitize(VideoItem item, boolean channel) throws Exception {
        String page = safeVideoPage(item.source, item.pageUrl);
        String play = "PEERTUBE".equals(item.source)
                ? safeVideoPage(item.source, item.playUrl)
                : (page.isEmpty() ? safeVideoPage(item.source, item.playUrl) : page);
        if (!channel && play.isEmpty()) throw new Exception("Некорректная ссылка видео в обмене");
        if (item.title.length() > 500 || item.channelId.length() > 200
                || item.channelName.length() > 300)
            throw new Exception("Слишком длинная запись в обмене");
        return new VideoItem(item.source, item.title, item.subtitle, "", play, page,
                item.durationMs).withQuality(item.maxWidth, item.maxHeight)
                .withChannel(item.channelId, item.channelName, safePage(item.channelUrl),
                        item.isLive).withMetrics(item.views, item.publishedAtMs);
    }

    /** Never send signed media URLs, query tokens, cookies or URL userinfo. */
    static String safePage(String value) {
        if (value == null || value.length() > 2048) return "";
        try {
            URI uri = new URI(value);
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getHost() == null || path == null || path.isEmpty()
                    || uri.getPort() != -1 || path.matches("(?i).*\\.(m3u8|mpd|mp4|m4v|ts)$"))
                return "";
            return new URI("https", null, uri.getHost().toLowerCase(java.util.Locale.ROOT),
                    -1, path, null, null).toASCIIString();
        } catch (Exception ignored) { return ""; }
    }

    static String safeVideoPage(String source, String value) {
        String cleaned = safePage(value);
        if (cleaned.isEmpty()) return "";
        try {
            URI uri = new URI(cleaned);
            String host = uri.getHost();
            String path = uri.getPath();
            switch (source) {
                case "RUTUBE": return "rutube.ru".equals(host)
                        && (path.startsWith("/video/") || path.startsWith("/play/embed/"))
                        ? cleaned : "";
                case "VK VIDEO":
                    if (!"vkvideo.ru".equals(host) && !"vk.com".equals(host)) return "";
                    java.util.regex.Matcher vk = java.util.regex.Pattern.compile(
                            "^/video(-?\\d+_\\d+)(?:_[a-zA-Z0-9]+)?/?$").matcher(path);
                    return vk.matches() ? "https://vkvideo.ru/video" + vk.group(1) : "";
                case "OK": return "ok.ru".equals(host) && path.startsWith("/video")
                        ? cleaned : "";
                case "ДЗЕН": return "dzen.ru".equals(host)
                        && path.startsWith("/video/") ? cleaned : "";
                case "PEERTUBE": return path.startsWith("/api/v1/videos/")
                        || path.startsWith("/videos/watch/") ? cleaned : "";
                default: return "";
            }
        } catch (Exception ignored) { return ""; }
    }
}
