package ru.tubetv.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One video decoder for the centered card in the mobile Shorts feed. */
final class MobileShortsPlayback {
    private final Activity activity;
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
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

    MobileShortsPlayback(Activity activity) { this.activity = activity; }

    boolean isSelected(FrameLayout view) { return artwork == view; }

    void select(VideoItem next, FrameLayout frame, ImageView image, TextView cue) {
        if (artwork == frame && item != null && item.stableKey().equals(next.stableKey())) {
            if (pausedForScroll && player != null && foreground) player.play();
            pausedForScroll = false;
            return;
        }
        clear();
        item = next;
        artwork = frame;
        thumbnail = image;
        indicator = cue;
        recorded = WatchHistoryStore.contains(activity, next);
        indicator.setText("▶");
        indicator.setVisibility(View.VISIBLE);
        thumbnail.setVisibility(View.VISIBLE);
        if (MobileBrowsePreferences.shortsAutoplay(activity)) start();
    }

    void toggle() {
        if (item == null) return;
        if (failed) {
            resetDecoder();
            start();
            return;
        }
        if (player == null) {
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
        resumeAfterPause = player != null && player.isPlaying();
        if (player != null) player.pause();
        if (surface != null) surface.onPause();
        keepScreenAwake();
    }

    void onResume() {
        foreground = true;
        if (surface != null) surface.onResume();
        if (resumeAfterPause && player != null) player.play();
        resumeAfterPause = false;
        keepScreenAwake();
    }

    void clear() {
        requestId++;
        if (resolving != null) resolving.cancel(true);
        resolving = null;
        saveProgress();
        main.removeCallbacks(progress);
        resetDecoder();
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

    private void resetDecoder() {
        if (player != null) { player.clearVideoSurface(); player.release(); player = null; }
        if (surface != null) {
            surface.release();
            if (surface.getParent() instanceof ViewGroup)
                ((ViewGroup) surface.getParent()).removeView(surface);
            surface = null;
        }
        failed = false;
    }

    void release() { clear(); resolver.shutdownNow(); }

    private void start() {
        if (item == null || resolving != null || player != null) return;
        AudioPlaybackService.stopPlayback(activity);
        VideoItem target = item;
        int token = ++requestId;
        indicator.setText("Загрузка…");
        indicator.setTextSize(15);
        indicator.setVisibility(View.VISIBLE);
        resolving = resolver.submit(() -> {
            PlaybackInfo stream;
            try { stream = new StreamResolver().resolveForPlayback(target.playUrl, false); }
            catch (Exception error) {
                main.post(() -> {
                    if (token != requestId || indicator == null) return;
                    resolving = null;
                    indicator.setText("Не удалось воспроизвести · нажмите для повтора");
                });
                return;
            }
            main.post(() -> {
                if (token != requestId || artwork == null) return;
                resolving = null;
                attach(stream);
            });
        });
    }

    private void attach(PlaybackInfo stream) {
        try {
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
            player = PlaybackEngineFactory.create(activity, item.source, item.pageUrl,
                    false, 0, false);
            Surface output = surface.getVideoSurface();
            if (output != null) player.setVideoSurface(output);
            player.setRepeatMode(MobileBrowsePreferences.shortsLoop(activity)
                    ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
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
                    if (playing) indicator.setVisibility(View.GONE);
                    keepScreenAwake();
                }

                @Override public void onVideoSizeChanged(VideoSize size) {
                    videoWidth = size.width;
                    videoHeight = size.height;
                    pixelRatio = size.pixelWidthHeightRatio > 0 ? size.pixelWidthHeightRatio : 1f;
                    fitSurface();
                }

                @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                    failed = true;
                    indicator.setText("Ошибка видео · нажмите для повтора");
                    indicator.setTextSize(15);
                    indicator.setVisibility(View.VISIBLE);
                    thumbnail.setVisibility(View.VISIBLE);
                    keepScreenAwake();
                }
            });
            player.setMediaItem(PlaybackEngineFactory.mediaItem(stream.streamUrl,
                    stream.streamMimeType));
            player.setPlayWhenReady(foreground);
            player.prepare();
            main.postDelayed(progress, 5000);
        } catch (Throwable error) {
            CrashLog.save(activity, error);
            resetDecoder();
            failed = true;
            indicator.setText("Ошибка видео · нажмите для повтора");
            indicator.setTextSize(15);
            indicator.setVisibility(View.VISIBLE);
        }
    }

    private void fitSurface() {
        if (surface == null || artwork == null || videoWidth <= 0 || videoHeight <= 0
                || artwork.getWidth() <= 0 || artwork.getHeight() <= 0) return;
        float ratio = videoWidth * pixelRatio / videoHeight;
        int width = artwork.getWidth();
        int height = artwork.getHeight();
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
        if (!recorded && position >= WatchProgressStore.MIN_POSITION_MS) {
            WatchHistoryStore.record(activity, item.withDuration(duration), false, 0, false);
            recorded = true;
        }
    }

    private void keepScreenAwake() {
        boolean awake = foreground && player != null && player.isPlaying();
        if (awake) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
