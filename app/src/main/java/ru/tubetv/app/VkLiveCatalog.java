package ru.tubetv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public VK Live listings and fresh playback URLs. Signed media URLs are never stored. */
final class VkLiveCatalog {
    private static final String API = "https://api.live.vkvideo.ru/v1";
    // VK Live signs public media URLs for srcAg=UNKNOWN. A browser User-Agent changes
    // the CDN's client classification and makes those manifests return HTTP 403.
    static final String USER_AGENT = "RussTube/1.0";
    private static final String RECOMMENDATIONS = API
            + "/catalog/public_video_streams/recommendations/";
    private static final Pattern BLOG = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    static List<VideoItem> load() throws Exception {
        return parse(new JSONObject(read(RECOMMENDATIONS)));
    }

    static List<VideoItem> parse(JSONObject root) {
        List<VideoItem> result = new ArrayList<>();
        JSONObject data = root == null ? null : root.optJSONObject("data");
        JSONArray streams = data == null ? null : data.optJSONArray("streamBlogs");
        if (streams == null) return result;
        for (int i = 0; i < streams.length() && result.size() < 30; i++) {
            JSONObject entry = streams.optJSONObject(i);
            JSONObject stream = entry == null ? null : entry.optJSONObject("stream");
            JSONObject blog = entry == null ? null : entry.optJSONObject("blog");
            if (stream == null || blog == null || !active(stream)) continue;
            String blogUrl = blog.optString("blogUrl");
            if (!BLOG.matcher(blogUrl).matches()) continue;
            JSONObject owner = blog.optJSONObject("owner");
            String channelName = owner == null ? "" : owner.optString("displayName");
            if (channelName.isEmpty() && owner != null) channelName = owner.optString("name");
            if (channelName.isEmpty()) channelName = blogUrl;
            String page = "https://live.vkvideo.ru/" + blogUrl;
            String preview = https(stream.optString("previewUrl"));
            if (preview == null) preview = "";
            JSONObject count = stream.optJSONObject("count");
            long views = count == null ? -1 : count.optLong("viewers", -1);
            long started = Math.max(0, stream.optLong("startTime")) * 1000L;
            result.add(new VideoItem("VK VIDEO", stream.optString("title", channelName),
                    "", preview, page, page)
                    .withChannel(owner == null ? "" : owner.optString("id"),
                            channelName, page, true)
                    .withMetrics(views, started));
        }
        return result;
    }

    static PlaybackInfo resolve(String pageUrl) throws Exception {
        String blogUrl = blogUrl(pageUrl);
        JSONObject stream = new JSONObject(read(API + "/blog/" + blogUrl
                + "/public_video_stream"));
        if (!active(stream)) throw new Exception("Трансляция VK Live сейчас недоступна");
        PlaybackInfo playback = selectPlayback(stream);
        if (playback == null) throw new Exception("VK Live не отдал публичный поток");
        return playback;
    }

    static PlaybackInfo selectPlayback(JSONObject stream) {
        JSONArray data = stream == null ? null : stream.optJSONArray("data");
        String dash = null;
        if (data == null) return null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject option = data.optJSONObject(i);
            JSONArray urls = option == null ? null : option.optJSONArray("sharedPlayerUrls");
            if (urls == null) continue;
            for (int j = 0; j < urls.length(); j++) {
                JSONObject candidate = urls.optJSONObject(j);
                if (candidate == null) continue;
                String url = https(candidate.optString("url"));
                if (url == null) continue;
                String type = candidate.optString("type");
                if ("live_hls".equals(type))
                    return new PlaybackInfo(url, "application/x-mpegURL", 0, 0);
                if ("live_cmaf".equals(type) && dash == null) dash = url;
            }
        }
        return dash == null ? null : new PlaybackInfo(dash, "application/dash+xml", 0, 0);
    }

    static boolean isLivePage(String address) {
        try { blogUrl(address); return true; }
        catch (Exception ignored) { return false; }
    }

    private static String blogUrl(String address) throws Exception {
        URI uri = new URI(address);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"live.vkvideo.ru".equalsIgnoreCase(uri.getHost()))
            throw new Exception("Некорректная ссылка VK Live");
        String path = uri.getPath();
        Matcher matcher = Pattern.compile("^/([A-Za-z0-9_-]{1,80})(?:/.*)?$")
                .matcher(path == null ? "" : path);
        if (!matcher.matches()) throw new Exception("Некорректная ссылка VK Live");
        return matcher.group(1);
    }

    private static boolean active(JSONObject stream) {
        return stream.optBoolean("isOnline") && !stream.optBoolean("isEnded")
                && stream.optBoolean("hasAccess", true)
                && !stream.optBoolean("isPlaybackDisabled");
    }

    private static String https(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? value : null;
        } catch (Exception ignored) { return null; }
    }

    private static String read(String url) throws Exception {
        HttpURLConnection connection = NetworkProxy.openConnection("VK VIDEO", url);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", "https://live.vkvideo.ru/");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("VK Live: HTTP " + code);
            return BoundedHttpReader.readText(connection.getInputStream(),
                    BoundedHttpReader.JSON_LIMIT);
        } finally {
            connection.disconnect();
        }
    }

    private VkLiveCatalog() { }
}
