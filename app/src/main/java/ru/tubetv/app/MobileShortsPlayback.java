package ru.tubetv.app;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One video decoder for the centered card in the mobile Shorts feed. */
@OptIn(markerClass = UnstableApi.class)
final class MobileShortsPlayback {
    interface UnavailableListener { void onUnavailable(VideoItem item); }
    private final Activity activity;
    private final UnavailableListener onUnavailable;
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final ExecutorService preloader = Executors.newSingleThreadExecutor();
    private final ExecutorService metadataResolver = Executors.newSingleThreadExecutor();
    private final Map<String, Prepared> prepared = new ConcurrentHashMap<>();
    private String preloadCurrentKey;
    private Future<?> preloadJob;
    private Future<?> metadataJob;
    private static final long PREPARED_TTL_MS = 90_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            saveProgress();
            if (player != null) main.postDelayed(this, 5000);
        }
    };
    private VideoItem item;
    private FrameLayout artwork;
    private ImageView thumbnail;
    private TextView indicator;
    private MirrorVideoView surface;
    private ExoPlayer player;
    private String playerSource;
    private Player.Listener playerListener;
    private AnalyticsListener analyticsListener;
    private Future<?> resolving;
    private int requestId;
    private boolean foreground = true;
    private boolean resumeAfterPause;
    private boolean pausedForScroll;
    private boolean recorded;
    private boolean failed;
    private int videoWidth;
    private int videoHeight;
    private float pixelRatio = 1f;
    private long selectedAtMs;
    private boolean firstFrameRendered;
    private int rebufferCount;
    private int retryCount;

    private static final class Prepared {
        final PlaybackInfo stream;
        final long createdAtMs;
        Prepared(PlaybackInfo stream) {
            this.stream = stream;
            this.createdAtMs = SystemClock.elapsedRealtime();
        }
    }

    MobileShortsPlayback(Activity activity, UnavailableListener onUnavailable) {
        this.activity = activity;
        this.onUnavailable = onUnavailable;
    }

    boolean isSelected(FrameLayout view) { return artwork == view; }

    void select(VideoItem next, FrameLayout frame, ImageView image, TextView cue) {
        if (artwork == frame && item != null && item.stableKey().equals(next.stableKey())) {
            if (pausedForScroll && player != null && foreground) player.play();
            pausedForScroll = false;
            return;
        }
        clearForTransition();
        item = next;
        selectedAtMs = SystemClock.elapsedRealtime();
        if (BuildConfig.DEBUG) Log.i("RussTubeShortsPerf", "selected source=" + next.source
                + " autoplay=" + MobileBrowsePreferences.shortsAutoplay(activity));
        firstFrameRendered = false;
        rebufferCount = 0;
        artwork = frame;
        thumbnail = image;
        indicator = cue;
        recorded = WatchHistoryStore.contains(activity, next);
        retryCount = 0;
        indicator.setText("▶");
        indicator.setVisibility(View.VISIBLE);
        thumbnail.setVisibility(View.VISIBLE);
        if (MobileBrowsePreferences.shortsAutoplay(activity)) start();
    }

    void preload(List<VideoItem> feed, int position) {
        int count = MobileBrowsePreferences.shortsPreloadCount(activity);
        if (count == 0 || (MobileBrowsePreferences.shortsWifiOnly(activity) && !onWifi())) {
            stopPreloading();
            return;
        }
        Set<String> nextKeys = new HashSet<>();
        List<VideoItem> next = new ArrayList<>();
        for (int i = position + 1; i < feed.size() && next.size() < count; i++) {
            VideoItem candidate = feed.get(i);
            if (!UnavailableVideoStore.contains(activity, candidate)
                    && nextKeys.add(candidate.stableKey())) next.add(candidate);
        }
        StringBuilder signature = new StringBuilder(feed.get(position).stableKey()).append('\n').append(count);
        for (VideoItem candidate : next) signature.append('\n').append(candidate.stableKey());
        String currentKey = signature.toString();
        if (currentKey.equals(preloadCurrentKey)) return;
        preloadCurrentKey = currentKey;
        if (item != null) nextKeys.add(item.stableKey());
        if (position > 0) nextKeys.add(feed.get(position - 1).stableKey());
        if (preloadJob != null) preloadJob.cancel(true);
        if (metadataJob != null) metadataJob.cancel(true);
        prepared.keySet().retainAll(nextKeys);
        if (next.isEmpty()) return;
        if (BuildConfig.DEBUG) android.util.Log.i("RussTubeShortsPreload",
                "scheduled=" + next.size() + " current=" + currentKey.hashCode());
        VideoItem first = next.get(0);
        preloadJob = preloader.submit(() -> {
            try {
                PlaybackEngineFactory.preloadShort(activity, first, resolvePrepared(first));
            } catch (Exception error) { logPreloadFailure(first, error); }
        });
        // Resolve N+2 and further lookahead without downloading media bytes.
        if (next.size() > 1) metadataJob = metadataResolver.submit(() -> {
            for (int i = 1; i < next.size() && !Thread.currentThread().isInterrupted(); i++) {
                VideoItem candidate = next.get(i);
                try { resolvePrepared(candidate); }
                catch (Exception error) { logPreloadFailure(candidate, error); }
            }
        });
    }

    private PlaybackInfo resolvePrepared(VideoItem candidate) throws Exception {
        Prepared cached = prepared.get(candidate.stableKey());
        if (cached != null && SystemClock.elapsedRealtime() - cached.createdAtMs < PREPARED_TTL_MS) {
            if (BuildConfig.DEBUG) Log.i("RussTubeShortsPerf", "resolveMs=0 cached=true source="
                    + candidate.source);
            return cached.stream;
        }
        long started = SystemClock.elapsedRealtime();
        PlaybackInfo stream = new StreamResolver().resolveForPlayback(candidate.playUrl, false);
        if (BuildConfig.DEBUG) Log.i("RussTubeShortsPerf", "resolveMs="
                + (SystemClock.elapsedRealtime() - started) + " cached=false source="
                + candidate.source);
        if (!Thread.currentThread().isInterrupted())
            prepared.put(candidate.stableKey(), new Prepared(stream));
        return stream;
    }

    private void logPreloadFailure(VideoItem candidate, Exception error) {
        if (BuildConfig.DEBUG && !Thread.currentThread().isInterrupted())
            Log.i("RussTubeShortsPreload", "failed source=" + candidate.source
                    + " reason=" + error.getClass().getSimpleName());
    }

    private boolean onWifi() {
        ConnectivityManager manager = (ConnectivityManager) activity.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null
                : manager.getNetworkCapabilities(active);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private boolean online() {
        ConnectivityManager manager = (ConnectivityManager) activity.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null
                : manager.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    void toggle() {
        if (item == null) return;
        if (failed) {
            resetDecoder();
            retryCount = 0;
            start();
            return;
        }
        if (player == null || player.getMediaItemCount() == 0) {
            if (resolving == null) start();
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            indicator.setText("▶");
            indicator.setVisibility(View.VISIBLE);
        } else {
            if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
            player.play();
            indicator.setVisibility(View.GONE);
        }
        keepScreenAwake();
    }

    void pauseForScroll() {
        if (player != null && player.isPlaying()) {
            pausedForScroll = true;
            player.pause();
        }
        keepScreenAwake();
    }

    void onPause() {
        foreground = false;
        stopPreloading();
        resumeAfterPause = player != null && player.isPlaying();
        if (player != null) player.pause();
        if (surface != null) surface.onPause();
        keepScreenAwake();
    }

    void onResume() {
        foreground = true;
        if (surface != null) surface.onResume();
        if (resumeAfterPause && player != null) player.play();
        else if (retryCount > 0 && !failed
                && (player == null || player.getMediaItemCount() == 0) && resolving == null
                && item != null) start();
        resumeAfterPause = false;
        keepScreenAwake();
    }

    void clear() {
        clearSelection(false);
    }

    void clearForTransition() {
        clearSelection(true);
    }

    private void clearSelection(boolean retainPlayer) {
        requestId++;
        if (resolving != null) resolving.cancel(true);
        resolving = null;
        saveProgress();
        main.removeCallbacks(progress);
        if (retainPlayer && player != null && !failed) {
            if (playerListener != null) player.removeListener(playerListener);
            if (analyticsListener != null) player.removeAnalyticsListener(analyticsListener);
            playerListener = null;
            analyticsListener = null;
            player.pause();
            player.clearVideoSurface();
            player.clearMediaItems();
            releaseSurface();
        } else resetDecoder();
        if (thumbnail != null) thumbnail.setVisibility(View.VISIBLE);
        if (indicator != null) indicator.setVisibility(View.GONE);
        item = null;
        artwork = null;
        thumbnail = null;
        indicator = null;
        pausedForScroll = false;
        videoWidth = videoHeight = 0;
        keepScreenAwake();
    }

    void stopPreloading() {
        if (preloadJob != null) preloadJob.cancel(true);
        if (metadataJob != null) metadataJob.cancel(true);
        preloadJob = null;
        metadataJob = null;
        preloadCurrentKey = null;
    }

    private void resetDecoder() {
        if (player != null) { player.clearVideoSurface(); player.release(); player = null; }
        playerSource = null;
        playerListener = null;
        analyticsListener = null;
        releaseSurface();
        failed = false;
    }

    private void releaseSurface() {
        if (surface != null) {
            surface.release();
            if (surface.getParent() instanceof ViewGroup)
                ((ViewGroup) surface.getParent()).removeView(surface);
            surface = null;
        }
    }

    void reconfigureCache() {
        stopPreloading();
        preloader.submit(() -> PlaybackEngineFactory.reconfigureShortsCache(activity));
    }

    void clearCache() {
        stopPreloading();
        preloader.submit(() -> {
            long bytes = PlaybackEngineFactory.clearShortsCache(activity);
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "Очищено " + (bytes / (1024 * 1024)) + " МБ видеокэша",
                    Toast.LENGTH_SHORT).show());
        });
    }

    void release() {
        clear();
        stopPreloading();
        preloader.shutdownNow(); metadataResolver.shutdownNow();
        resolver.shutdownNow(); prepared.clear();
    }

    private void start() {
        if (item == null || resolving != null
                || (player != null && player.getMediaItemCount() > 0)) return;
        AudioPlaybackService.stopPlayback(activity);
        VideoItem target = item;
        int token = ++requestId;
        if (BuildConfig.DEBUG) Log.i("RussTubeShortsPerf", "start source=" + target.source);
        indicator.setText("Загрузка…");
        indicator.setTextSize(15);
        indicator.setVisibility(View.VISIBLE);
        resolving = resolver.submit(() -> {
            PlaybackInfo stream;
            try {
                stream = resolvePrepared(target);
            }
            catch (Exception error) {
                main.post(() -> {
                    if (token != requestId || indicator == null) return;
                    resolving = null;
                    retryOrSkip(target, token, error);
                });
                return;
            }
            final PlaybackInfo resolvedStream = stream;
            main.post(() -> {
                if (token != requestId || artwork == null) return;
                resolving = null;
                attach(resolvedStream, token);
            });
        });
    }

    private void attach(PlaybackInfo stream, int token) {
        VideoItem target = item;
        try {
            if (player != null && !target.source.equals(playerSource)) resetDecoder();
            boolean reusedPlayer = player != null;
            surface = new MirrorVideoView(activity);
            surface.setSurfaceListener(new MirrorVideoView.SurfaceListener() {
                @Override public void onSurfaceAvailable(Surface output) {
                    if (player != null) player.setVideoSurface(output);
                }
                @Override public void onSurfaceDestroyed(Surface output) {
                    if (player != null) player.clearVideoSurface(output);
                }
            });
            artwork.addView(surface, 0, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            if (player == null) {
                player = PlaybackEngineFactory.create(activity, item.source, item.pageUrl,
                        false, 0, false);
                playerSource = target.source;
            }
            if (BuildConfig.DEBUG) {
                analyticsListener = new AnalyticsListener() {
                @Override public void onRenderedFirstFrame(EventTime eventTime,
                                                            Object output, long renderTimeMs) {
                    if (token != requestId || firstFrameRendered) return;
                    firstFrameRendered = true;
                    Log.i("RussTubeShortsPerf", "firstFrameMs="
                            + (SystemClock.elapsedRealtime() - selectedAtMs)
                            + " source=" + target.source + " rebuffer=" + rebufferCount);
                }
                };
                player.addAnalyticsListener(analyticsListener);
            }
            Surface output = surface.getVideoSurface();
            if (output != null) player.setVideoSurface(output);
            player.setRepeatMode(MobileBrowsePreferences.shortsLoop(activity)
                    ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            playerListener = new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (token != requestId) return;
                    if (state == Player.STATE_BUFFERING && firstFrameRendered) rebufferCount++;
                    if (state == Player.STATE_READY) {
                        thumbnail.setVisibility(View.INVISIBLE);
                        indicator.setVisibility(player.isPlaying() ? View.GONE : View.VISIBLE);
                    } else if (state == Player.STATE_ENDED) {
                        indicator.setText("▶ Повторить");
                        indicator.setTextSize(15);
                        indicator.setVisibility(View.VISIBLE);
                    }
                    keepScreenAwake();
                }

                @Override public void onIsPlayingChanged(boolean playing) {
                    if (token != requestId) return;
                    if (playing) indicator.setVisibility(View.GONE);
                    keepScreenAwake();
                }

                @Override public void onVideoSizeChanged(VideoSize size) {
                    if (token != requestId) return;
                    videoWidth = size.width;
                    videoHeight = size.height;
                    pixelRatio = size.pixelWidthHeightRatio > 0 ? size.pixelWidthHeightRatio : 1f;
                    fitSurface();
                }

                @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                    if (token == requestId) retryOrSkip(target, token, error);
                }
            };
            player.addListener(playerListener);
            if (BuildConfig.DEBUG) Log.i("RussTubeShortsPerf", "attachMs="
                    + (SystemClock.elapsedRealtime() - selectedAtMs)
                    + " reused=" + reusedPlayer);
            player.setMediaItem(PlaybackEngineFactory.mediaItem(stream.streamUrl,
                    stream.streamMimeType));
            player.setPlayWhenReady(foreground);
            player.prepare();
            main.postDelayed(progress, 5000);
        } catch (Throwable error) {
            CrashLog.save(activity, error);
            resetDecoder();
            retryOrSkip(target, token, error);
        }
    }

    private void retryOrSkip(VideoItem target, int token, Throwable error) {
        if (token != requestId || item == null || indicator == null) return;
        prepared.remove(target.stableKey()); // A signed media URL may have expired.
        if (BuildConfig.DEBUG) Log.i("RussTubeShortsPerf", "error source=" + target.source
                + " attempt=" + (retryCount + 1) + " kind=" + error.getClass().getSimpleName());
        if (retryCount++ == 0) {
            resetDecoder();
            indicator.setText("Повторное подключение…");
            indicator.setTextSize(15);
            indicator.setVisibility(View.VISIBLE);
            thumbnail.setVisibility(View.VISIBLE);
            main.postDelayed(() -> {
                if (token == requestId && item == target && foreground) start();
            }, 700);
            return;
        }
        if (!online()) {
            resetDecoder();
            failed = true;
            indicator.setText("Нет сети · нажмите для повтора");
            indicator.setVisibility(View.VISIBLE);
            return;
        }
        if (!transientNetworkError(error)) UnavailableVideoStore.add(activity, target);
        failed = true;
        indicator.setText("Видео недоступно · следующий ролик");
        indicator.setTextSize(15);
        indicator.setVisibility(View.VISIBLE);
        thumbnail.setVisibility(View.VISIBLE);
        main.post(() -> onUnavailable.onUnavailable(target));
    }

    private boolean transientNetworkError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof IOException) return true;
        return false;
    }

    private void fitSurface() {
        if (surface == null || artwork == null || videoWidth <= 0 || videoHeight <= 0
                || artwork.getWidth() <= 0 || artwork.getHeight() <= 0) return;
        float ratio = videoWidth * pixelRatio / videoHeight;
        int width = artwork.getWidth();
        int height = artwork.getHeight();
        if (MobileBrowsePreferences.shortsFill(activity)) {
            surface.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
            float frameRatio = width / (float) height;
            if (ratio > frameRatio) surface.setCrop(frameRatio / ratio, 1f);
            else surface.setCrop(1f, ratio / frameRatio);
            return;
        }
        surface.setCrop(1f, 1f);
        int fittedWidth = Math.min(width, Math.round(height * ratio));
        int fittedHeight = Math.min(height, Math.round(width / ratio));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                fittedWidth, fittedHeight, Gravity.CENTER);
        surface.setLayoutParams(params);
    }

    private void saveProgress() {
        if (player == null || item == null) return;
        long position = Math.max(0, player.getCurrentPosition());
        long duration = player.getDuration() > 0 ? player.getDuration() : item.durationMs;
        if (duration > 0) {
            WatchProgressStore.save(activity, item.source, item.pageUrl, position, duration);
            StateStore.savePlayerPosition(activity, item.source, item.pageUrl, position);
        }
        if (!recorded && WatchProgressStore.isViewed(position, duration)) {
            WatchHistoryStore.record(activity, item.withDuration(duration), false, 0, false, true);
            recorded = true;
        }
    }

    private void keepScreenAwake() {
        boolean awake = foreground && player != null && player.isPlaying();
        if (awake) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
