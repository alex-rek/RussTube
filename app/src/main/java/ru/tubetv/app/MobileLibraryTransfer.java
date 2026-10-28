package ru.tubetv.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Versioned Mobile/TV library format. Resolved and expiring stream URLs are never saved. */
final class MobileLibraryTransfer {
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CHANNELS = 100;
    private static final int MAX_FAVORITES = 1000;

    static final class ImportData {
        final List<VideoItem> channels;
        final List<VideoItem> favorites;

        ImportData(List<VideoItem> channels, List<VideoItem> favorites) {
            this.channels = channels;
            this.favorites = favorites;
        }
    }

    static final class Result {
        final int channels;
        final int favorites;

        Result(int channels, int favorites) {
            this.channels = channels;
            this.favorites = favorites;
        }
    }

    static void write(Context context, OutputStream output) throws Exception {
        JSONObject backup = new JSONObject().put("format", "russtube-library")
                .put("version", 1).put("subscriptions", encode(SubscriptionStore.load(context)))
                .put("favorites", encode(FavoriteStore.load(context)));
        output.write(backup.toString(2).getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    static ImportData read(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > MAX_FILE_BYTES) throw new Exception("Файл слишком большой");
            bytes.write(buffer, 0, count);
        }
        JSONObject root = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        if (!"russtube-library".equals(root.optString("format")) || root.optInt("version") != 1)
            throw new Exception("Это не файл подписок и избранного RussTube");
        JSONArray channels = root.optJSONArray("subscriptions");
        JSONArray favorites = root.optJSONArray("favorites");
        if (channels == null || favorites == null || channels.length() > MAX_CHANNELS
                || favorites.length() > MAX_FAVORITES)
            throw new Exception("Некорректный список подписок или избранного");
        return new ImportData(decode(channels, true), decode(favorites, false));
    }

    static synchronized Result merge(Context context, ImportData imported) throws Exception {
        List<VideoItem> existingChannels = SubscriptionStore.load(context);
        List<VideoItem> existingFavorites = FavoriteStore.load(context);
        LinkedHashMap<String, VideoItem> channels = new LinkedHashMap<>();
        LinkedHashMap<String, VideoItem> favorites = new LinkedHashMap<>();
        for (VideoItem item : existingChannels) channels.put(item.channelKey(), item);
        for (VideoItem item : existingFavorites) favorites.put(item.stableKey(), item);
        for (VideoItem item : imported.channels) {
            String key = item.channelKey();
            if (!channels.containsKey(key)) channels.put(key, item);
        }
        for (VideoItem item : imported.favorites) {
            String key = item.stableKey();
            if (!favorites.containsKey(key)) favorites.put(key, item);
        }
        if (channels.size() > MAX_CHANNELS || favorites.size() > MAX_FAVORITES)
            throw new Exception("Недостаточно места: максимум 100 каналов и 1000 избранных видео");
        int newChannels = channels.size() - existingChannels.size();
        int newFavorites = favorites.size() - existingFavorites.size();
        String channelsJson = encode(new ArrayList<>(channels.values())).toString();
        String favoritesJson = encode(new ArrayList<>(favorites.values())).toString();
        if (!context.getSharedPreferences("subscriptions", 0).edit()
                .putString("channels", channelsJson).commit()
                || !context.getSharedPreferences("favorites", 0).edit()
                .putString("videos", favoritesJson).commit())
            throw new Exception("Не удалось сохранить библиотеку");
        return new Result(newChannels, newFavorites);
    }

    private static JSONArray encode(List<VideoItem> items) throws Exception {
        JSONArray array = new JSONArray();
        for (VideoItem item : items) array.put(VideoJson.encode(item));
        return array;
    }

    private static List<VideoItem> decode(JSONArray array, boolean channels) throws Exception {
        LinkedHashMap<String, VideoItem> unique = new LinkedHashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value == null) throw new Exception("Повреждённая запись в файле");
            VideoItem item = VideoJson.decode(value);
            if (item.source == null || item.source.isEmpty()
                    || (channels ? item.channelId.isEmpty()
                    : item.playUrl == null || item.playUrl.isEmpty()))
                throw new Exception("Повреждённая запись в файле");
            String key = channels ? item.channelKey() : item.stableKey();
            if (!unique.containsKey(key)) unique.put(key, item);
        }
        return new ArrayList<>(unique.values());
    }
}
