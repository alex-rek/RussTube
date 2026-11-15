package ru.tubetv.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/** Public, read-only comments. Authentication is not shared with video playback. */
final class VideoComments {
    static final class Entry {
        final String author;
        final String text;
        Entry(String author, String text) { this.author = author; this.text = text; }
    }

    static final class Page {
        final int total;
        final List<Entry> entries;
        Page(int total, List<Entry> entries) { this.total = total; this.entries = entries; }
    }

    static boolean supported(VideoItem video) {
        return video != null && ("RUTUBE".equals(video.source)
                || PeerTubeClient.SOURCE.equals(video.source));
    }

    static Page load(VideoItem video) throws Exception {
        if (!supported(video)) throw new Exception("Комментарии этой площадки доступны на её сайте");
        String url;
        if ("RUTUBE".equals(video.source)) {
            String id = Uri.parse(video.pageUrl).getLastPathSegment();
            if (id == null || !id.matches("[a-zA-Z0-9]{32}"))
                throw new Exception("Не удалось определить видео RUTUBE");
            url = "https://rutube.ru/api/v2/comments/video/" + id + "/";
        } else {
            Uri page = Uri.parse(video.pageUrl);
            String id = page.getLastPathSegment();
            if (id == null || !id.matches("[a-zA-Z0-9_-]{8,64}"))
                throw new Exception("Не удалось определить видео PeerTube");
            url = page.getScheme() + "://" + page.getAuthority()
                    + "/api/v1/videos/" + id + "/comment-threads?count=20";
        }
        HttpURLConnection connection = NetworkProxy.openConnection(video.source, url);
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 RussTube/" + BuildConfig.VERSION_NAME);
        try {
            JSONObject root = new JSONObject(BoundedHttpReader.readText(connection.getInputStream(), 512 * 1024));
            return parse(video.source, root);
        } finally {
            connection.disconnect();
        }
    }

    static Page parse(String source, JSONObject root) {
        boolean rutube = "RUTUBE".equals(source);
        int count = root.optInt(rutube ? "comments_count" : "totalNotDeletedComments",
                root.optInt("total", 0));
        JSONArray values = rutube ? root.optJSONArray("results") : root.optJSONArray("data");
        List<Entry> entries = new ArrayList<>();
        if (values != null) for (int i = 0; i < Math.min(20, values.length()); i++) {
            JSONObject comment = values.optJSONObject(i);
            if (comment == null || comment.optBoolean("is_deleted")) continue;
            String body = comment.optString("text").trim();
            if (body.isEmpty()) continue;
            JSONObject author = comment.optJSONObject(rutube ? "user" : "account");
            String name = author == null ? "Пользователь" : author.optString(
                    rutube ? "name" : "displayName", "Пользователь");
            entries.add(new Entry(name, body));
        }
        return new Page(count, entries);
    }

    private VideoComments() { }
}
