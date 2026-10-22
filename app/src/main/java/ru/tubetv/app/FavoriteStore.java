package ru.tubetv.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class FavoriteStore {
    static List<VideoItem> load(Context context) {
        List<VideoItem> items = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(context.getSharedPreferences("favorites", 0).getString("videos", "[]"));
            for (int i = 0; i < Math.min(data.length(), 1000); i++) {
                JSONObject value = data.optJSONObject(i);
                if (value != null) items.add(VideoJson.decode(value));
            }
        } catch (Exception ignored) { }
        return items;
    }

    static boolean contains(Context context, VideoItem item) {
        for (VideoItem saved : load(context)) if (saved.stableKey().equals(item.stableKey())) return true;
        return false;
    }

    static synchronized void set(Context context, VideoItem item, boolean enabled) throws Exception {
        List<VideoItem> values = load(context);
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i).stableKey().equals(item.stableKey())) values.remove(i);
        }
        if (enabled) {
            if (values.size() >= 1000) throw new Exception("Можно сохранить до 1000 видео");
            values.add(0, item);
        }
        JSONArray data = new JSONArray();
        for (VideoItem value : values) data.put(VideoJson.encode(value));
        context.getSharedPreferences("favorites", 0).edit().putString("videos", data.toString()).apply();
    }
}
