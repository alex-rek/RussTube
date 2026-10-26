package ru.tubetv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Discovers public videos via SepiaSearch and loads subscribed channels from their origin. */
final class PeerTubeFeedClient {
    private static final String SEPIA = "https://sepiasearch.org/api/v1/search/videos";
    private static final String PEERSEEK = "https://peerseek.video/v1/search";
    private static final int LIMIT = 20;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final ExpiringCache<String, List<VideoItem>> SEARCH_CACHE =
            new ExpiringCache<>(32, 3 * 60_000L);

    interface Reader { String read(String url) throws Exception; }

    static List<VideoItem> search(String query) throws Exception {
        String key = query == null ? "" : query.trim();
        List<VideoItem> cached = SEARCH_CACHE.get(key);
        if (cached != null) return new ArrayList<>(cached);
        List<VideoItem> result = search(key, PeerTubeFeedClient::get);
        if (!result.isEmpty()) SEARCH_CACHE.put(key, new ArrayList<>(result));
        return result;
    }

    static List<VideoItem> search(String query, Reader reader) throws Exception {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty()) return new ArrayList<>();
        String sepiaUrl = SEPIA + "?search=" + encode(value)
                + "&count=" + LIMIT + "&start=0&nsfw=false&isLive=false";
        String peerSeekUrl = PEERSEEK + "?q=" + encode(value)
                + "&size=" + LIMIT + "&page=1&include_nsfw=false&include_live=false";
        LinkedHashMap<String, VideoItem> unique = new LinkedHashMap<>();
        for (List<VideoItem> found : ParallelTasks.map(Arrays.asList(sepiaUrl, peerSeekUrl),
                2, url -> url.startsWith(PEERSEEK)
                        ? parsePeerSeek(new JSONObject(reader.read(url)))
                        : parse(new JSONObject(reader.read(url))))) {
            for (VideoItem item : found)
                if (!unique.containsKey(item.stableKey())) unique.put(item.stableKey(), item);
        }
        return new ArrayList<>(unique.values());
    }

    static List<VideoItem> parsePeerSeek(JSONObject response) {
        List<VideoItem> result = new ArrayList<>();
        JSONArray videos = response.optJSONArray("videos");
        if (videos == null) return result;
        for (int i = 0; i < Math.min(videos.length(), LIMIT); i++) {
            JSONObject entry = videos.optJSONObject(i);
            PeerTubeSearch.Candidate candidate = PeerTubeSearch.Candidate.from(entry, true);
            if (candidate == null) continue;
            String channelUrl = "";
            String handle = entry.optString("channelHandle", "");
            if (handle.matches("[A-Za-z0-9._@-]+")) {
                URI page = URI.create(candidate.pageUrl);
                channelUrl = page.getScheme() + "://" + page.getRawAuthority()
                        + "/video-channels/" + handle;
            }
            VideoItem item = new VideoItem(PeerTubeClient.SOURCE, candidate.title, "",
                    candidate.thumbnail == null ? "" : candidate.thumbnail,
                    candidate.apiUrl, candidate.pageUrl, candidate.durationSeconds * 1000L);
            if (!channelUrl.isEmpty()) item = item.withChannel(channelUrl,
                    entry.optString("channel", ""), channelUrl, false);
            result.add(item.withMetrics(entry.optLong("views", -1),
                    VideoPresentation.timestamp(entry.optString("publishedAt"))));
        }
        return result;
    }

    static List<VideoItem> live(String query) throws Exception {
        return live(query, PeerTubeFeedClient::get);
    }

    static List<VideoItem> live(String query, Reader reader) throws Exception {
        String value = query == null ? "" : query.trim();
        String url = SEPIA + (value.isEmpty() ? "?" : "?search=" + encode(value) + "&")
                + "count=" + LIMIT
                + "&start=0&nsfw=false&isLive=true&includeScheduledLive=false";
        return parse(new JSONObject(reader.read(url)), true);
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
        return parse(response, false);
    }

    private static List<VideoItem> parse(JSONObject response, boolean liveOnly) {
        List<VideoItem> result = new ArrayList<>();
        JSONArray data = response.optJSONArray("data");
        if (data == null) return result;
        for (int i = 0; i < Math.min(data.length(), LIMIT); i++) {
            JSONObject entry = data.optJSONObject(i);
            if (liveOnly && (entry == null || !entry.optBoolean("isLive"))) continue;
            PeerTubeSearch.Candidate candidate = PeerTubeSearch.Candidate.from(entry, false, liveOnly);
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
                        item = item.withChannel(channelUrl, name, channelUrl, liveOnly);
                    }
                }
            }
            if (liveOnly && !item.isLive) item = item.withChannel("", "", "", true);
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
