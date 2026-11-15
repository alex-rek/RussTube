package ru.tubetv.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Saves only complete, progressive public video files to a user-selected document. */
final class VideoDownload {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    static String resolve(VideoItem video) throws Exception {
        if (video.isLive) throw new Exception("Прямой эфир нельзя скачать как файл");
        if ("VK VIDEO".equals(video.source)) {
            JSONObject metadata = VkWebClient.getVideoById(VkVideoId.fromUrl(video.pageUrl));
            JSONObject files = metadata.optJSONObject("files");
            String best = null;
            int bestHeight = 0;
            if (files != null) for (Iterator<String> keys = files.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!key.matches("mp4_[0-9]+")) continue;
                String address = files.optString(key);
                int height = Integer.parseInt(key.substring(4));
                if (address.startsWith("https://") && height > bestHeight) {
                    best = address;
                    bestHeight = height;
                }
            }
            if (best != null) return best;
        }
        if ("OK".equals(video.source)) {
            OkClient.PlayerData player = OkClient.loadPlayerData(video.pageUrl);
            JSONArray formats = player.metadata == null ? null : player.metadata.optJSONArray("videos");
            String best = null;
            int bestHeight = -1;
            if (formats != null) for (int i = 0; i < formats.length(); i++) {
                JSONObject format = formats.optJSONObject(i);
                if (format == null) continue;
                String address = format.optString("url");
                if (!progressive(address)) continue;
                String quality = format.optString("name").replaceAll("[^0-9]", "");
                int height = quality.isEmpty() ? 0 : Integer.parseInt(quality);
                if (height > bestHeight) { best = address; bestHeight = height; }
            }
            if (best != null) return best;
        }
        if ("RUTUBE".equals(video.source)) {
            String id = Uri.parse(video.pageUrl).getLastPathSegment();
            if (id != null && id.matches("[a-zA-Z0-9]{32}")) {
                String optionsUrl = "https://rutube.ru/api/play/options/"
                        + id + "/?format=json&no_404=true&mq=all";
                String privateKey = Uri.parse(video.playUrl).getQueryParameter("p");
                if (privateKey != null && !privateKey.isEmpty())
                    optionsUrl += "&p=" + Uri.encode(privateKey);
                JSONObject options = fetchJson("RUTUBE", optionsUrl);
                JSONObject balancer = options.optJSONObject("video_balancer");
                if (balancer != null) for (Iterator<String> keys = balancer.keys(); keys.hasNext();) {
                    String address = balancer.optString(keys.next());
                    if (progressive(address)) return address;
                }
            }
        }
        if (PeerTubeClient.SOURCE.equals(video.source) && PeerTubeClient.isApiUrl(video.playUrl)) {
            JSONObject metadata = fetchJson(video.source, video.playUrl);
                JSONArray files = metadata.optJSONArray("files");
                String best = null;
                int height = 0;
                if (files != null) for (int i = 0; i < files.length(); i++) {
                    JSONObject file = files.optJSONObject(i);
                    if (file == null) continue;
                    String address = file.optString("fileUrl");
                    JSONObject resolution = file.optJSONObject("resolution");
                    int size = resolution == null ? 0 : resolution.optInt("id");
                    if (progressive(address) && size > height) {
                        best = address;
                        height = size;
                    }
                }
                if (best != null) return best;
        }
        PlaybackInfo info = new StreamResolver().resolveForPlayback(video.playUrl, false);
        if (info.streamUrl != null && info.streamUrl.startsWith("https://")
                && (info.streamMimeType == null || info.streamMimeType.startsWith("video/"))
                && progressive(info.streamUrl))
            return info.streamUrl;
        throw new Exception("Для этого видео доступен только потоковый формат, а не файл MP4");
    }

    private static JSONObject fetchJson(String source, String address) throws Exception {
        HttpURLConnection connection = NetworkProxy.openConnection(source, address);
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        try { return new JSONObject(BoundedHttpReader.readText(
                connection.getInputStream(), 1024 * 1024)); }
        finally { connection.disconnect(); }
    }

    private static boolean progressive(String address) {
        return address != null && address.startsWith("https://")
                && Uri.parse(address).getPath() != null
                && Uri.parse(address).getPath().toLowerCase().endsWith(".mp4");
    }

    static void save(Context context, String source, String pageUrl, String address, Uri destination) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = NetworkProxy.openConnection(source, address);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 RussTube/" + BuildConfig.VERSION_NAME);
                connection.setRequestProperty("Accept", "video/mp4,application/octet-stream,*/*");
                if (pageUrl != null && pageUrl.startsWith("https://"))
                    connection.setRequestProperty("Referer", pageUrl);
                int status = connection.getResponseCode();
                String mime = connection.getContentType();
                if (status != 200 || mime != null && (mime.contains("text/html")
                        || mime.contains("mpegurl") || mime.contains("dash+xml")))
                    throw new Exception("Площадка не отдала файл MP4 (HTTP " + status + ")");
                try (InputStream input = connection.getInputStream();
                     OutputStream output = app.getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new Exception("Не удалось открыть выбранный файл");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                UI.post(() -> Toast.makeText(app, "Видео сохранено", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                try { app.getContentResolver().delete(destination, null, null); }
                catch (Exception ignored) { }
                String message = error.getMessage();
                UI.post(() -> Toast.makeText(app, "Скачивание не удалось: " + message,
                        Toast.LENGTH_LONG).show());
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private VideoDownload() { }
}
