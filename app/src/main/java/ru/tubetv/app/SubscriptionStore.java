package ru.tubetv.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class SubscriptionStore {
    private static final int MAX_CHANNELS = 100;

    static List<VideoItem> load(Context context) {
        List<VideoItem> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(context.getSharedPreferences("subscriptions", 0)
                    .getString("channels", "[]"));
            for (int i = 0; i < Math.min(array.length(), MAX_CHANNELS); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value != null) {
                    VideoItem channel = VideoJson.decode(value);
                    if (!channel.channelId.isEmpty()) result.add(channel);
                }
            }
        } catch (Exception ignored) { /* Keep a malformed record from blocking the TV UI. */ }
        return result;
    }

    static boolean contains(Context context, VideoItem channel) {
        for (VideoItem saved : load(context)) {
            if (saved.channelKey().equals(channel.channelKey())) return true;
        }
        return false;
    }

    static synchronized void set(Context context, VideoItem channel, boolean subscribed) throws Exception {
        if (channel.channelId.isEmpty()) throw new Exception("Платформа не передала канал этого видео");
        List<VideoItem> channels = load(context);
        for (int i = channels.size() - 1; i >= 0; i--) {
            if (channels.get(i).channelKey().equals(channel.channelKey())) channels.remove(i);
        }
        if (subscribed) {
            if (channels.size() >= MAX_CHANNELS) throw new Exception("Можно сохранить до 100 каналов");
            channels.add(0, channel);
        }
        JSONArray data = new JSONArray();
        for (VideoItem item : channels) data.put(VideoJson.encode(item));
        context.getSharedPreferences("subscriptions", 0).edit()
                .putString("channels", data.toString()).apply();
    }
}
