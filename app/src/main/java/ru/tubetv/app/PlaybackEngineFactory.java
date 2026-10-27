package ru.tubetv.app;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
final class PlaybackEngineFactory {
    private static final int[] SHORT_PRELOAD_BYTES = {512 * 1024, 1536 * 1024, 3 * 1024 * 1024};
    private static final float[] SHORT_PRELOAD_SECONDS = {2f, 5f, 10f};
    private static SimpleCache shortsCache;

    private static synchronized SimpleCache shortsCache(Context context) {
        if (shortsCache == null) shortsCache = new SimpleCache(
                new java.io.File(context.getCacheDir(), "shorts_media"),
                new LeastRecentlyUsedCacheEvictor(
                        MobileBrowsePreferences.shortsCacheMb(context) * 1024L * 1024L));
        return shortsCache;
    }

    static synchronized void reconfigureShortsCache(Context context) {
        if (shortsCache != null) { shortsCache.release(); shortsCache = null; }
        shortsCache(context);
    }

    static synchronized long clearShortsCache(Context context) {
        SimpleCache cache = shortsCache(context);
        long bytes = cache.getCacheSpace();
        for (String key : new ArrayList<>(cache.getKeys())) cache.removeResource(key);
        return bytes;
    }

    static void preloadShort(Context context, VideoItem item, PlaybackInfo stream) {
        if (stream == null || stream.streamUrl == null || stream.streamUrl.isEmpty()) return;
        if (MimeTypes.APPLICATION_MPD.equals(stream.streamMimeType)
                || stream.streamUrl.contains(".mpd")) return;
        int mode = MobileBrowsePreferences.shortsPreloadMode(context);
        int byteLimit = SHORT_PRELOAD_BYTES[mode];
        float seconds = SHORT_PRELOAD_SECONDS[mode];
        DataSource.Factory factory = cachedHttp(context, item.source, item.pageUrl);
        try {
            if (MimeTypes.APPLICATION_M3U8.equals(stream.streamMimeType)
                    || stream.streamUrl.contains(".m3u8")) {
                String playlistUrl = stream.streamUrl;
                for (int depth = 0; depth < 3 && !Thread.currentThread().isInterrupted(); depth++) {
                    String playlist = new String(readPrefix(factory, playlistUrl, 128 * 1024),
                            StandardCharsets.UTF_8);
                    if (playlist.contains("#EXT-X-STREAM-INF")) {
                        String variant = firstMediaUri(playlist);
                        if (variant == null) return;
                        playlistUrl = URI.create(playlistUrl).resolve(variant).toString();
                    } else {
                        preloadSegments(factory, playlistUrl, playlist, seconds, byteLimit);
                        return;
                    }
                }
            } else preloadProgressive(factory, stream.streamUrl, item.durationMs,
                    seconds, byteLimit);
        } catch (Exception ignored) {
            // A failed warmup must never prevent normal playback.
        }
    }

    private static String firstMediaUri(String playlist) {
        for (String line : playlist.split("\\r?\\n")) {
            String value = line.trim();
            if (!value.isEmpty() && !value.startsWith("#")) return value;
        }
        return null;
    }

    private static void preloadSegments(DataSource.Factory factory, String playlistUrl,
                                        String playlist, float secondsLimit,
                                        int byteLimit) throws IOException {
        if (playlist.contains("#EXT-X-BYTERANGE")) return;
        float seconds = 0f;
        float nextDuration = 0f;
        int bytes = 0;
        for (String line : playlist.split("\\r?\\n")) {
            if (Thread.currentThread().isInterrupted()) return;
            if (seconds >= secondsLimit || bytes >= byteLimit) break;
            String value = line.trim();
            if (value.startsWith("#EXTINF:")) {
                try { nextDuration = Float.parseFloat(value.substring(8).split(",", 2)[0]); }
                catch (NumberFormatException ignored) { nextDuration = 0f; }
            } else if (!value.isEmpty() && !value.startsWith("#")) {
                String segment = URI.create(playlistUrl).resolve(value).toString();
                bytes += cachePrefix(factory, segment, byteLimit - bytes);
                seconds += nextDuration > 0f ? nextDuration : 4f;
                nextDuration = 0f;
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.i("RussTubeShortsPreload",
                "kind=hls seconds=" + seconds + " bytes=" + bytes);
    }

    private static void preloadProgressive(DataSource.Factory factory, String url,
                                           long durationMs, float secondsLimit,
                                           int byteLimit) throws IOException {
        DataSource source = factory.createDataSource();
        try {
            long length = source.open(new DataSpec.Builder().setUri(url).build());
            int budget = Math.min(byteLimit, 512 * 1024);
            if (length > 0 && durationMs > 0) {
                long estimated = (long) (length * secondsLimit * 1000f / durationMs);
                budget = (int) Math.max(64 * 1024L,
                        Math.min(byteLimit, estimated));
            }
            byte[] bytes = new byte[16 * 1024];
            int loaded = 0;
            int read;
            while (!Thread.currentThread().isInterrupted() && loaded < budget
                    && (read = source.read(bytes, 0, Math.min(bytes.length, budget - loaded))) != -1)
                loaded += read;
            if (BuildConfig.DEBUG) android.util.Log.i("RussTubeShortsPreload",
                    "kind=progressive bytes=" + loaded + " durationMs=" + durationMs
                            + " contentLength=" + length);
        } finally {
            source.close();
        }
    }

    private static int cachePrefix(DataSource.Factory factory, String url, int limit)
            throws IOException {
        DataSource source = factory.createDataSource();
        try {
            source.open(new DataSpec.Builder().setUri(url).setLength(limit).build());
            byte[] buffer = new byte[16 * 1024];
            int loaded = 0;
            int read;
            while (!Thread.currentThread().isInterrupted() && loaded < limit
                    && (read = source.read(buffer, 0, Math.min(buffer.length, limit - loaded))) != -1)
                loaded += read;
            return loaded;
        } finally {
            source.close();
        }
    }

    private static byte[] readPrefix(DataSource.Factory factory, String url, int limit)
            throws IOException {
        DataSource source = factory.createDataSource();
        try {
            source.open(new DataSpec.Builder().setUri(url).setLength(limit).build());
            byte[] bytes = new byte[16 * 1024];
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 16 * 1024));
            int read;
            while (!Thread.currentThread().isInterrupted() && output.size() < limit
                    && (read = source.read(bytes, 0, Math.min(bytes.length, limit - output.size()))) != -1)
                output.write(bytes, 0, read);
            return output.toByteArray();
        } finally {
            source.close();
        }
    }

    private static DataSource.Factory cachedHttp(Context context, String source, String pageUrl) {
        return new CacheDataSource.Factory().setCache(shortsCache(context))
                .setUpstreamDataSourceFactory(http(context, source, pageUrl))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }
    static ExoPlayer create(Context context, String source, String pageUrl,
                            boolean trafficMode, int targetHeight, boolean audioOnly) {
        DeviceCapabilities capabilities = DeviceCapabilities.get();
        MediaCodecSelector selector = (mimeType, secure, tunneling) -> {
            List<MediaCodecInfo> available = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling);
            if (!mimeType.startsWith("video/") && !mimeType.startsWith("audio/")) return available;
            List<MediaCodecInfo> hardware = new ArrayList<>();
            for (MediaCodecInfo codec : available) {
                if (codec.hardwareAccelerated && !codec.softwareOnly) hardware.add(codec);
            }
            if (mimeType.startsWith("video/")) {
                return capabilities.supportsHardware(mimeType) ? hardware : new ArrayList<>();
            }
            // Some TV hardware AAC decoders accept mono only. Audio may fall back;
            // software video is never included in the selector's result.
            List<MediaCodecInfo> ordered = new ArrayList<>(hardware);
            for (MediaCodecInfo codec : available) if (!hardware.contains(codec)) ordered.add(codec);
            return ordered;
        };
        ExoPlayer player = new ExoPlayer.Builder(context)
                .setRenderersFactory(new DefaultRenderersFactory(context)
                        .setMediaCodecSelector(selector).setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(
                        BuildConfig.MOBILE_VARIANT ? cachedHttp(context, source, pageUrl)
                                : http(context, source, pageUrl)))
                .build();
        TrackSelectionParameters.Builder tracks = player.getTrackSelectionParameters().buildUpon();
        String[] video = capabilities.preferredVideoMimes(trafficMode);
        String[] audio = capabilities.preferredAudioMimes(trafficMode || audioOnly);
        if (video.length > 0) tracks.setPreferredVideoMimeTypes(video);
        if (audio.length > 0) tracks.setPreferredAudioMimeTypes(audio);
        if (audioOnly) {
            tracks.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setForceLowestBitrate(true);
        } else if (trafficMode) {
            int height = targetHeight > 0 ? targetHeight : 144;
            int width = height == 240 ? 426 : (height * 16 + 8) / 9;
            tracks.setMaxVideoSize(width, height).setForceHighestSupportedBitrate(true);
        } else {
            tracks.setMaxVideoSize(3840, 2160).setForceHighestSupportedBitrate(true);
        }
        player.setTrackSelectionParameters(tracks.build());
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(audioOnly ? C.AUDIO_CONTENT_TYPE_MUSIC : C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        return player;
    }

    static MediaItem mediaItem(String url, String mimeType) {
        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        if (mimeType != null) item.setMimeType(mimeType);
        else if (url.contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (url.contains(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD);
        return item.build();
    }

    private static DataSource.Factory http(Context context, String source, String pageUrl) {
        boolean vk = "VK VIDEO".equals(source);
        boolean dzen = "ДЗЕН".equals(source);
        boolean ok = "OK".equals(source);
        boolean peerTube = PeerTubeClient.SOURCE.equals(source);
        Map<String, String> headers = peerTube ? PeerTubeClient.mediaHeaders(pageUrl) : new HashMap<>();
        if (!dzen && !peerTube) {
            String referer = vk ? "https://vkvideo.ru/" : ok ? OkClient.REFERER : "https://rutube.ru/";
            if (referer != null) headers.put("Referer", referer);
        }
        String userAgent = dzen ? DzenClient.USER_AGENT : vk ? VkWebClient.USER_AGENT
                : ok ? OkClient.USER_AGENT
                : peerTube ? PeerTubeClient.MEDIA_USER_AGENT
                : "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36";
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(8_000).setReadTimeoutMs(10_000)
                .setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(headers);
        CookieHandler cookies = dzen ? new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
                : ScopedCookies.NONE;
        boolean proxyEnabled = NetworkProxy.load(context).type != Proxy.Type.DIRECT;
        return () -> new CookieDataSource(proxyEnabled
                ? new ProxyHttpDataSource(headers, userAgent, source) : http.createDataSource(), cookies);
    }

    private static final class CookieDataSource implements DataSource {
        private final DataSource upstream;
        private final CookieHandler cookies;
        CookieDataSource(DataSource upstream, CookieHandler cookies) {
            this.upstream = upstream;
            this.cookies = cookies;
        }
        @Override public long open(DataSpec spec) throws IOException {
            return ScopedCookies.with(cookies, () -> upstream.open(spec));
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            return upstream.read(buffer, offset, length);
        }
        @Override public void close() throws IOException { upstream.close(); }
        @Override public Uri getUri() { return upstream.getUri(); }
        @Override public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }
        @Override public void addTransferListener(TransferListener listener) { upstream.addTransferListener(listener); }
    }

    private PlaybackEngineFactory() { }
}
