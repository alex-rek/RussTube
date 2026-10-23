package ru.tubetv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Discovers public videos via SepiaSearch and loads subscribed channels from their origin. */
final class PeerTubeFeedClient {
    private static final String SEPIA = "https://sepiasearch.org/api/v1/search/videos";
    private static final int LIMIT = 20;
    private static final int MAX_JSON_BYTES = 1024 * 1024;

    interface Reader { String read(String url) throws Exception; }

    static List<VideoItem> search(String query) throws Exception {
        return search(query, PeerTubeFeedClient::get);
    }

    static List<VideoItem> search(String query, Reader reader) throws Exception {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty()) return new ArrayList<>();
        String url = SEPIA + "?search=" + encode(value)
                + "&count=" + LIMIT + "&start=0&nsfw=false&isLive=false";
        return parse(new JSONObject(reader.read(url)));
    }

    static List<VideoItem> channelVideos(VideoItem channel) throws Exception {
        return channelVideos(channel, PeerTubeFeedClient::get);
    }

    static List<VideoItem> channelVideos(VideoItem channel, Reader reader) throws Exception {
        URI uri = URI.create(channel.channelId);
        String path = uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || path == null
                || !path.matches("/video-channels/[A-Za-z0-9._@-]+")) {
            throw new Exception("Некорректный канал PeerTube");
        }
        String origin = "https://" + uri.getHost()
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        String handle = path.substring("/video-channels/".length());
        String url = origin + "/api/v1/video-channels/" + handle
                + "/videos?count=" + LIMIT + "&start=0";
        return parse(new JSONObject(reader.read(url)));
    }

    static List<VideoItem> parse(JSONObject response) {
        List<VideoItem> result = new ArrayList<>();
        JSONArray data = response.optJSONArray("data");
        if (data == null) return result;
        for (int i = 0; i < Math.min(data.length(), LIMIT); i++) {
            JSONObject entry = data.optJSONObject(i);
            PeerTubeSearch.Candidate candidate = PeerTubeSearch.Candidate.from(entry, false);
            if (candidate == null) continue;
            URI page = URI.create(candidate.pageUrl);
            String origin = page.getScheme() + "://" + page.getRawAuthority() + "/";
            String thumbnail = firstHttps(origin, entry.optString("previewUrl"),
                    entry.optString("thumbnailUrl"), entry.optString("previewPath"),
                    entry.optString("thumbnailPath"));
            if (thumbnail == null) thumbnail = candidate.thumbnail;
            VideoItem item = new VideoItem(PeerTubeClient.SOURCE, candidate.title,
                    "", thumbnail == null ? "" : thumbnail, candidate.apiUrl,
                    candidate.pageUrl, candidate.durationSeconds * 1000L);
            JSONObject channel = entry.optJSONObject("channel");
            if (channel != null) {
                String channelUrl = firstHttps(origin, channel.optString("url"));
                if (channelUrl != null) {
                    URI uri = URI.create(channelUrl);
                    if (page.getHost().equalsIgnoreCase(uri.getHost()) && uri.getPath() != null
                            && uri.getPath().matches("/video-channels/[A-Za-z0-9._@-]+")) {
                        String name = channel.optString("displayName", channel.optString("name"));
                        item = item.withChannel(channelUrl, name, channelUrl, false);
                    }
                }
            }
            result.add(item.withMetrics(entry.optLong("views", -1),
                    VideoPresentation.timestamp(entry.optString("publishedAt"))));
        }
        return result;
    }

    private static String firstHttps(String origin, String... values) {
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            try {
                URI url = URI.create(origin).resolve(value);
                if ("https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null
                        && url.getRawUserInfo() == null) return url.toString();
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    private static String get(String url) throws Exception {
        return PeerTubeClient.getText(url, MAX_JSON_BYTES, Long.MAX_VALUE,
                PeerTubeClient.apiHeaders());
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private PeerTubeFeedClient() { }
}
