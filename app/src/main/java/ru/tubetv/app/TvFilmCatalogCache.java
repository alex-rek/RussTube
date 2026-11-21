package ru.tubetv.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Small, persistent cache of platform film catalogs; saved films remain separate. */
final class TvFilmCatalogCache {
    private static final String PREFS = "tv_film_catalog_cache";
    private static final long FRESH_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int MAX_ITEMS = 250;

    static Snapshot load(Context context, String source) {
        try {
            String encoded = context.getSharedPreferences(PREFS, 0).getString(source, "");
            if (encoded == null || encoded.isEmpty()) return null;
            JSONObject root = new JSONObject(encoded);
            JSONArray data = root.optJSONArray("videos");
            if (data == null) return null;
            List<VideoItem> items = new ArrayList<>();
            for (int i = 0; i < Math.min(data.length(), MAX_ITEMS); i++) {
                JSONObject value = data.optJSONObject(i);
                if (value != null) items.add(VideoJson.decode(value));
            }
            return new Snapshot(items, root.optLong("savedAt"));
        } catch (Exception ignored) { return null; }
    }

    static void save(Context context, String source, List<VideoItem> items) {
        if (items == null || items.isEmpty()) return;
        try {
            JSONArray data = new JSONArray();
            for (int i = 0; i < Math.min(items.size(), MAX_ITEMS); i++)
                data.put(VideoJson.encode(items.get(i)));
            JSONObject root = new JSONObject().put("savedAt", System.currentTimeMillis())
                    .put("videos", data);
            context.getSharedPreferences(PREFS, 0).edit()
                    .putString(source, root.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void invalidate(Context context, String source) {
        context.getSharedPreferences(PREFS, 0).edit().remove(source).apply();
    }

    static final class Snapshot {
        final List<VideoItem> items;
        final long savedAt;

        Snapshot(List<VideoItem> items, long savedAt) {
            this.items = items;
            this.savedAt = savedAt;
        }

        boolean isFresh(long now) {
            return savedAt > 0 && now >= savedAt && now - savedAt < FRESH_MS;
        }
    }

    private TvFilmCatalogCache() { }
}
