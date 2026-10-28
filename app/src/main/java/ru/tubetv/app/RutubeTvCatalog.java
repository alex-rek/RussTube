package ru.tubetv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads currently listed channels instead of keeping stale stream IDs in the APK. */
final class RutubeTvCatalog {
    private static final String ADDRESS = "https://rutube.ru/feeds/live/";
    private static final String FILMS_ADDRESS = "https://rutube.ru/feeds/movies-serials/";
    private static final Pattern LIVE_LINK = Pattern.compile(
            "/live/video/([0-9a-f]{32})/", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILM_LINK = Pattern.compile(
            "(?<!/live)/video/([0-9a-f]{32})/", Pattern.CASE_INSENSITIVE);

    static List<VideoItem> load() throws Exception {
        return loadPage(ADDRESS, true);
    }

    static List<VideoItem> movies() throws Exception {
        return loadPage(FILMS_ADDRESS, false);
    }

    private static List<VideoItem> loadPage(String address, boolean live) throws Exception {
        HttpURLConnection connection = NetworkProxy.openConnection("RUTUBE", address);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("User-Agent", OkClient.USER_AGENT);
        String html;
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("RUTUBE ТВ: HTTP " + code);
            html = BoundedHttpReader.readText(connection.getInputStream(),
                    BoundedHttpReader.PAGE_LIMIT);
        } finally {
            connection.disconnect();
        }
        List<String> ids = live ? videoIds(html) : filmIds(html);
        if (ids.isEmpty()) return new ArrayList<>();
        return ParallelTasks.map(ids, 4, id -> {
            JSONObject root = new JSONObject();
            JSONArray results = new JSONArray();
            results.put(SearchClient.getJson("https://rutube.ru/api/video/" + id + "/"));
            root.put("results", results);
            List<VideoItem> parsed = SearchClient.parseRutube(root);
            if (parsed.isEmpty()) return null;
            VideoItem item = parsed.get(0);
            return item.withChannel(item.channelId, item.channelName, item.channelUrl, live);
        });
    }

    static List<String> videoIds(String html) {
        return ids(html, LIVE_LINK);
    }

    static List<String> filmIds(String html) {
        return ids(html, FILM_LINK);
    }

    private static List<String> ids(String html, Pattern pattern) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(html == null ? "" : html.replace("\\/", "/"));
        while (matcher.find() && ids.size() < 24) ids.add(matcher.group(1));
        return new ArrayList<>(ids);
    }

    private RutubeTvCatalog() { }
}
