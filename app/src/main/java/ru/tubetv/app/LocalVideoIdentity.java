package ru.tubetv.app;

import org.json.JSONObject;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable public video identity for OPEN_VIDEO; stream URLs are never transmitted. */
final class LocalVideoIdentity {
    private static final Pattern RUTUBE = Pattern.compile("^/video/([a-fA-F0-9]{32})/?$");
    private static final Pattern VK = Pattern.compile("^/video(-?\\d+_\\d+)(?:/.*)?$");
    private static final Pattern OK = Pattern.compile("^/video/(\\d+)(?:/.*)?$");
    private static final Pattern DZEN = Pattern.compile("^/video/watch/([a-zA-Z0-9_-]+)(?:/.*)?$");
    private static final Pattern PEERTUBE = Pattern.compile("^/videos/watch/([a-zA-Z0-9_-]+)(?:/.*)?$");

    static JSONObject from(VideoItem item) throws Exception {
        String page = LocalLibraryCodec.safeVideoPage(item.source, item.pageUrl);
        if (page.isEmpty()) throw new Exception("У видео нет постоянной публичной страницы");
        URI uri = new URI(page);
        String id;
        switch (item.source) {
            case "RUTUBE": id = match(RUTUBE, uri.getPath()); break;
            case "VK VIDEO": id = match(VK, uri.getPath()); break;
            case "OK": id = match(OK, uri.getPath()); break;
            case "ДЗЕН": id = match(DZEN, uri.getPath()); break;
            case "PEERTUBE": id = uri.getHost() + "/" + match(PEERTUBE, uri.getPath()); break;
            default: throw new Exception("Площадка не поддерживается на ТВ");
        }
        if (id == null) throw new Exception("Не удалось определить ID видео");
        return new JSONObject().put("platform", item.source)
                .put("contentType", item.isLive ? "LIVE" : "VOD")
                .put("contentId", id)
                .put("title", item.title == null ? "Видео" : cut(item.title, 300))
                .put("channelName", cut(item.channelName, 150))
                .put("durationMs", Math.max(0, item.durationMs));
    }

    static VideoItem toVideo(JSONObject data) throws Exception {
        String source = data.optString("platform");
        String type = data.optString("contentType");
        String id = data.optString("contentId");
        if (!"LIVE".equals(type) && !"VOD".equals(type))
            throw new Exception("Неподдерживаемый тип видео");
        String page, resolver;
        switch (source) {
            case "RUTUBE":
                if (!id.matches("[a-fA-F0-9]{32}")) throw new Exception("Неверный ID RUTUBE");
                page = "https://rutube.ru/video/" + id + "/";
                resolver = "https://rutube.ru/play/embed/" + id;
                break;
            case "VK VIDEO":
                if (!id.matches("-?\\d+_\\d+")) throw new Exception("Неверный ID VK");
                page = resolver = "https://vkvideo.ru/video" + id;
                break;
            case "OK":
                if (!id.matches("\\d+")) throw new Exception("Неверный ID OK");
                page = "https://ok.ru/video/" + id;
                resolver = "https://ok.ru/videoembed/" + id;
                break;
            case "ДЗЕН":
                if (!id.matches("[a-zA-Z0-9_-]+")) throw new Exception("Неверный ID Дзен");
                page = resolver = "https://dzen.ru/video/watch/" + id;
                break;
            case "PEERTUBE":
                int slash = id.lastIndexOf('/');
                if (slash < 1) throw new Exception("Неверный ID PeerTube");
                String host = id.substring(0, slash);
                String videoId = id.substring(slash + 1);
                if (!host.matches("[a-zA-Z0-9.-]{1,253}")
                        || !videoId.matches("[a-zA-Z0-9_-]+"))
                    throw new Exception("Неверный ID PeerTube");
                page = "https://" + host + "/videos/watch/" + videoId;
                resolver = "https://" + host + "/api/v1/videos/" + videoId;
                break;
            default: throw new Exception("Площадка не поддерживается на ТВ");
        }
        String title = cut(data.optString("title", "Видео"), 300);
        return new VideoItem(source, title, "", "", resolver, page,
                Math.max(0, data.optLong("durationMs")))
                .withChannel("", cut(data.optString("channelName"), 150), "",
                        "LIVE".equals(type));
    }

    private static String match(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String cut(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }
}
