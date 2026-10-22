package ru.tubetv.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Portrait watch page sharing RussTube's resolver, decoder and local libraries. */
public final class MobilePlayerActivity extends Activity {
    private static final int BACKGROUND = 0xff0b0f14;
    private static final int SECONDARY = 0xffaeb9cc;
    private static final int ACCENT = 0xffe53935;
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideControls = () -> this.controls.setVisibility(View.GONE);
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (started) ui.postDelayed(this, 1000);
        }
    };
    private ExoPlayer player;
    private LinearLayout root;
    private LinearLayout toolbar;
    private ScrollView detailsScroll;
    private MirrorVideoView surface;
    private FrameLayout videoFrame;
    private LinearLayout controls;
    private ProgressBar loading;
    private TextView errorText;
    private TextView playButton;
    private TextView fullscreenButton;
    private TextView time;
    private TextView favoriteButton;
    private TextView subscribeButton;
    private SeekBar timeline;
    private VideoItem video;
    private String streamUrl;
    private String streamMimeType;
    private boolean started;
    private boolean focused = true;
    private boolean failed;
    private boolean scrubbing;
    private boolean recorded;
    private boolean backgroundStarted;
    private boolean autoResume = true;
    private boolean portraitFullscreen;
    private int videoWidth;
    private int videoHeight;
    private float pixelRatio = 1f;
    private long resumePosition;
    private long knownDuration;
    private long lastSaved;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) portraitFullscreen = state.getBoolean("portrait_fullscreen", false);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        Intent intent = getIntent();
        knownDuration = Math.max(0, intent.getLongExtra("duration_ms", 0));
        resumePosition = Math.max(0, intent.getLongExtra("resume_position", 0));
        video = new VideoItem(intent.getStringExtra("source"), intent.getStringExtra("title"),
                intent.getStringExtra("subtitle"), intent.getStringExtra("thumbnail"),
                intent.getStringExtra("resolver_url"), intent.getStringExtra("page_url"), knownDuration)
                .withQuality(intent.getIntExtra("max_width", 0), intent.getIntExtra("max_height", 0))
                .withChannel(intent.getStringExtra("channel_id"), intent.getStringExtra("channel_name"),
                        intent.getStringExtra("channel_url"), intent.getBooleanExtra("live", false))
                .withMetrics(intent.getLongExtra("views", -1), intent.getLongExtra("published_at", 0));
        if (intent.getBooleanExtra("resume_background_audio", false)) {
            autoResume = AudioPlaybackService.shouldAutoResume();
            resumePosition = AudioPlaybackService.takePosition(this, video, resumePosition);
        } else {
            AudioPlaybackService.stopPlayback(this);
        }
        recorded = WatchHistoryStore.contains(this, video);
        setContentView(createContent());
        applyOrientation();
        requestNotificationPermission();
        resolveStream();
    }

    private View createContent() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            boolean fullscreen = isFullscreen();
            root.setPadding(0, fullscreen ? 0 : safe.top, 0, fullscreen ? 0 : safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);

        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 36, Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, size(52, 52));
        TextView brand = label("RussTube", 20, Color.WHITE);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        toolbar.addView(brand, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView official = label("↗", 25, Color.WHITE);
        official.setGravity(Gravity.CENTER);
        official.setContentDescription("Открыть на платформе");
        official.setOnClickListener(v -> openOfficial());
        toolbar.addView(official, size(48, 52));
        root.addView(toolbar, size(-1, 52));

        videoFrame = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (isFullscreen()) {
                    super.onMeasure(widthSpec, heightSpec);
                    return;
                }
                int width = View.MeasureSpec.getSize(widthSpec);
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                        Math.round(width * 9f / 16f), View.MeasureSpec.EXACTLY));
            }
        };
        videoFrame.setBackgroundColor(Color.BLACK);
        videoFrame.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> fitSurface());
        surface = new MirrorVideoView(this);
        surface.setSurfaceListener(new MirrorVideoView.SurfaceListener() {
            @Override public void onSurfaceAvailable(Surface output) {
                if (player != null) player.setVideoSurface(output);
            }
            @Override public void onSurfaceDestroyed(Surface output) {
                if (player != null) player.clearVideoSurface(output);
            }
        });
        videoFrame.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        View tapLayer = new View(this);
        tapLayer.setContentDescription("Показать или скрыть управление видео");
        tapLayer.setOnClickListener(v -> toggleControlsVisibility());
        videoFrame.addView(tapLayer, new FrameLayout.LayoutParams(-1, -1));
        loading = new ProgressBar(this);
        loading.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        videoFrame.addView(loading, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
        errorText = label("Получаю видеопоток…", 14, Color.WHITE);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(dp(24), dp(12), dp(24), dp(12));
        videoFrame.addView(errorText, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(9), dp(12), dp(9), dp(3));
        controls.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, 0xdd000000, 0xf9000000}));
        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setGravity(Gravity.CENTER_VERTICAL);
        addControl(controlRow, "−10", () -> seek(-10_000));
        playButton = addControl(controlRow, "▶", this::togglePlayback);
        addControl(controlRow, "+10", () -> seek(10_000));
        controlRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        time = label("0:00 / 0:00", 12, Color.WHITE);
        time.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        controlRow.addView(time, new LinearLayout.LayoutParams(0, dp(42), 1));
        fullscreenButton = addControl(controlRow, "⛶", this::toggleFullscreen);
        fullscreenButton.setTextSize(26);
        fullscreenButton.setContentDescription("На весь экран");
        controls.addView(controlRow, size(-1, 46));
        timeline = new SeekBar(this);
        timeline.setMax(1000);
        timeline.setProgressTintList(ColorStateList.valueOf(ACCENT));
        timeline.setThumbTintList(ColorStateList.valueOf(ACCENT));
        timeline.setProgressBackgroundTintList(ColorStateList.valueOf(0xff888888));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && player != null && validDuration()) {
                    time.setText(format(player.getDuration() * progress / 1000L)
                            + " / " + format(player.getDuration()));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true; ui.removeCallbacks(hideControls);
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (player != null && validDuration())
                    player.seekTo(player.getDuration() * bar.getProgress() / 1000L);
                scrubbing = false; showControls();
            }
        });
        controls.addView(timeline, size(-1, 24));
        videoFrame.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        videoFrame.setOnClickListener(v -> toggleControlsVisibility());
        root.addView(videoFrame, size(-1, -2));

        detailsScroll = new ScrollView(this);
        detailsScroll.setFillViewport(true);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(16), dp(17), dp(16), dp(30));
        TextView title = label(video.title, 20, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        details.addView(title, size(-1, -2));
        List<String> facts = new ArrayList<>();
        if (!VideoPresentation.views(video).isEmpty()) facts.add(VideoPresentation.views(video));
        if (!VideoPresentation.published(video).isEmpty()) facts.add(VideoPresentation.published(video));
        if (video.isLive) facts.add("Сейчас в эфире");
        TextView metrics = label(TextUtils.join(" · ", facts), 13, SECONDARY);
        metrics.setPadding(0, dp(7), 0, dp(18));
        details.addView(metrics, size(-1, -2));

        LinearLayout channelRow = new LinearLayout(this);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);
        String channel = VideoPresentation.channel(video);
        TextView avatar = label(channel.substring(0, 1).toUpperCase(), 19, Color.WHITE);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(round(0xff33465a, 24));
        channelRow.addView(avatar, size(44, 44));
        TextView channelText = label(channel + "\n" + video.source, 14, Color.WHITE);
        channelText.setPadding(dp(11), 0, dp(4), 0);
        channelRow.addView(channelText, new LinearLayout.LayoutParams(0, dp(50), 1));
        if (!video.channelId.isEmpty()) {
            subscribeButton = label("", 13, Color.WHITE);
            subscribeButton.setGravity(Gravity.CENTER);
            subscribeButton.setPadding(dp(10), 0, dp(10), 0);
            subscribeButton.setBackground(round(ACCENT, 18));
            subscribeButton.setOnClickListener(v -> toggleSubscription());
            channelRow.addView(subscribeButton, size(-2, 36));
        }
        details.addView(channelRow, size(-1, 52));

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(18), 0, dp(10));
        favoriteButton = action(actions, "♡  Избранное", this::toggleFavorite);
        action(actions, "⚙  Скорость", this::chooseSpeed);
        action(actions, "HQ", this::chooseQuality);
        details.addView(actions, size(-1, 76));
        if (video.subtitle != null && !video.subtitle.isEmpty()) {
            TextView description = label(video.subtitle, 14, SECONDARY);
            description.setPadding(0, dp(12), 0, dp(12));
            details.addView(description, size(-1, -2));
        }
        TextView channelVideos = label("Видео канала  ›", 15, Color.WHITE);
        channelVideos.setPadding(dp(14), 0, dp(14), 0);
        channelVideos.setBackground(round(0xff171a21, 10));
        channelVideos.setOnClickListener(v -> VideoActions.openChannel(this, video));
        channelVideos.setVisibility(video.channelId.isEmpty() ? View.GONE : View.VISIBLE);
        details.addView(channelVideos, size(-1, 48));
        detailsScroll.addView(details);
        root.addView(detailsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        updateActions();
        return root;
    }

    private void applyOrientation() {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        boolean fullscreen = isFullscreen();
        toolbar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        detailsScroll.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        videoFrame.setLayoutParams(fullscreen
                ? new LinearLayout.LayoutParams(-1, 0, 1)
                : size(-1, -2));
        getWindow().getDecorView().setSystemUiVisibility(fullscreen
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : 0);
        fullscreenButton.setContentDescription(fullscreen
                ? "Выйти из полноэкранного режима" : "На весь экран");
        fullscreenButton.setVisibility(landscape ? View.GONE : View.VISIBLE);
        fullscreenButton.setTextColor(portraitFullscreen ? ACCENT : Color.WHITE);
        ViewCompat.requestApplyInsets(root);
        videoFrame.requestLayout();
        videoFrame.post(this::fitSurface);
        showControls();
    }

    private boolean isFullscreen() {
        return portraitFullscreen || getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void toggleFullscreen() {
        portraitFullscreen = !portraitFullscreen;
        applyOrientation();
    }

    private void fitSurface() {
        if (videoWidth <= 0 || videoHeight <= 0 || videoFrame.getWidth() <= 0
                || videoFrame.getHeight() <= 0) return;
        float width = videoWidth * pixelRatio;
        float scale = Math.min(videoFrame.getWidth() / width,
                videoFrame.getHeight() / (float) videoHeight);
        int fittedWidth = Math.max(1, Math.round(width * scale));
        int fittedHeight = Math.max(1, Math.round(videoHeight * scale));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (params.width == fittedWidth && params.height == fittedHeight) return;
        surface.setLayoutParams(new FrameLayout.LayoutParams(
                fittedWidth, fittedHeight, Gravity.CENTER));
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyOrientation();
    }

    private TextView addControl(LinearLayout row, String text, Runnable action) {
        TextView button = label(text, 19, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(v -> { action.run(); showControls(); });
        row.addView(button, size(54, 44));
        return button;
    }

    private TextView action(LinearLayout row, String text, Runnable callback) {
        TextView button = label(text, 13, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setBackground(round(0xff222630, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
        params.rightMargin = dp(8);
        row.addView(button, params);
        button.setOnClickListener(v -> callback.run());
        return button;
    }

    private void resolveStream() {
        resolver.execute(() -> {
            try {
                PlaybackInfo info = new StreamResolver().resolveForPlayback(video.playUrl, false);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    streamUrl = info.streamUrl;
                    streamMimeType = info.streamMimeType;
                    if (started) startPlayer();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showError("Видео недоступно: " + error.getMessage());
                });
            }
        });
    }

    private void startPlayer() {
        if (!started || player != null || streamUrl == null) return;
        try {
            player = PlaybackEngineFactory.create(this, video.source, video.pageUrl,
                    false, 0, false);
            Surface output = surface.getVideoSurface();
            if (output != null) player.setVideoSurface(output);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    updateScreenAwake();
                    if (state == Player.STATE_READY) {
                        loading.setVisibility(View.GONE);
                        errorText.setVisibility(View.GONE);
                        showControls();
                    } else if (state == Player.STATE_ENDED) {
                        if (isFullscreen()) {
                            saveProgress();
                            finish();
                        } else showControls();
                    }
                }
                @Override public void onIsPlayingChanged(boolean playing) {
                    updateScreenAwake(); updateProgress(); scheduleHide();
                }
                @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                    updateScreenAwake();
                }
                @Override public void onVideoSizeChanged(VideoSize size) {
                    videoWidth = size.width;
                    videoHeight = size.height;
                    pixelRatio = size.pixelWidthHeightRatio > 0 ? size.pixelWidthHeightRatio : 1f;
                    videoFrame.post(MobilePlayerActivity.this::fitSurface);
                }
                @Override public void onPlayerError(PlaybackException error) {
                    showError("Ошибка воспроизведения: " + error.getErrorCodeName());
                }
            });
            player.setMediaItem(PlaybackEngineFactory.mediaItem(streamUrl, streamMimeType));
            if (resumePosition > 0) player.seekTo(resumePosition);
            player.setPlaybackSpeed(StateStore.playbackSpeed(this));
            player.setPlayWhenReady(autoResume);
            player.prepare();
            ui.post(ticker);
        } catch (Throwable error) {
            CrashLog.save(getApplicationContext(), error);
            showError("Не удалось запустить плеер");
        }
    }

    private void showError(String text) {
        failed = true; updateScreenAwake();
        loading.setVisibility(View.GONE);
        errorText.setVisibility(View.VISIBLE);
        errorText.setText(text + "\nОткройте видео на платформе кнопкой ↗");
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        updateProgress();
        scheduleHide();
    }

    private void toggleControlsVisibility() {
        if (controls.getVisibility() == View.VISIBLE) controls.setVisibility(View.GONE);
        else showControls();
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideControls);
        if (player != null && player.isPlaying() && !scrubbing) ui.postDelayed(hideControls, 10_000);
    }

    private void updateProgress() {
        if (player == null) return;
        long position = Math.max(0, player.getCurrentPosition());
        long duration = player.getDuration();
        if (!scrubbing) {
            time.setText(format(position) + " / " + (validDuration() ? format(duration) : "—"));
            timeline.setProgress(validDuration() ? (int) Math.min(1000, position * 1000 / duration) : 0);
        }
        playButton.setText(player.isPlaying() ? "Ⅱ" : "▶");
        if (started && System.currentTimeMillis() - lastSaved >= 5000) {
            saveProgress(); lastSaved = System.currentTimeMillis();
        }
    }

    private boolean validDuration() {
        return player != null && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET;
    }

    private void togglePlayback() {
        if (player == null) { openOfficial(); return; }
        if (player.isPlaying()) player.pause(); else player.play();
        updateProgress(); updateScreenAwake();
    }

    private void seek(long delta) {
        if (player == null) return;
        long target = Math.max(0, player.getCurrentPosition() + delta);
        if (validDuration()) target = Math.min(target, player.getDuration());
        player.seekTo(target);
    }

    private void chooseSpeed() {
        final float[] values = {.75f, 1f, 1.25f, 1.5f, 2f};
        String[] labels = {"0,75×", "1×", "1,25×", "1,5×", "2×"};
        new AlertDialog.Builder(this).setTitle("Скорость воспроизведения")
                .setItems(labels, (dialog, selected) -> {
                    if (player != null) player.setPlaybackSpeed(values[selected]);
                    StateStore.savePlaybackSpeed(this, values[selected]);
                }).show();
    }

    private void chooseQuality() {
        int[] heights = {0, 360, 720, 1080};
        String[] labels = {"Авто", "До 360p", "До 720p", "До 1080p"};
        new AlertDialog.Builder(this).setTitle("Качество видео")
                .setItems(labels, (dialog, selected) -> {
                    if (player == null) return;
                    int height = heights[selected];
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setMaxVideoSize(height == 0 ? 3840 : (height * 16 + 8) / 9,
                                    height == 0 ? 2160 : height).build());
                }).show();
    }

    private void toggleFavorite() {
        try {
            FavoriteStore.set(this, video, !FavoriteStore.contains(this, video));
            updateActions();
        } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void toggleSubscription() {
        try {
            SubscriptionStore.set(this, video, !SubscriptionStore.contains(this, video));
            updateActions();
        } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void updateActions() {
        favoriteButton.setText(FavoriteStore.contains(this, video) ? "♥  Сохранено" : "♡  Избранное");
        if (subscribeButton != null) subscribeButton.setText(
                SubscriptionStore.contains(this, video) ? "Вы подписаны" : "Подписаться");
    }

    private void saveProgress() {
        if (player == null) return;
        long position = Math.max(0, player.getCurrentPosition());
        long duration = validDuration() ? player.getDuration() : knownDuration;
        if (duration > 0) knownDuration = duration;
        StateStore.savePlayerPosition(this, video.source, video.pageUrl, position);
        WatchProgressStore.save(this, video.source, video.pageUrl, position, duration);
        if (!recorded && position >= WatchProgressStore.MIN_POSITION_MS) {
            WatchHistoryStore.record(this, video.withDuration(duration), false, 0, false);
            recorded = true;
        }
    }

    private void updateScreenAwake() {
        boolean awake = PlaybackScreenPolicy.shouldKeepAwake(started, focused,
                player != null && player.getPlayWhenReady(),
                player == null ? Player.STATE_IDLE : player.getPlaybackState(), failed);
        if (awake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (videoFrame != null) videoFrame.setKeepScreenOn(awake);
    }

    private void openOfficial() {
        if (video.pageUrl == null || video.pageUrl.isEmpty()) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(video.pageUrl))); }
        catch (Exception error) { Toast.makeText(this, "Не удалось открыть платформу", Toast.LENGTH_SHORT).show(); }
    }

    private void startBackgroundAudio() {
        Intent background = new Intent(this, AudioPlaybackService.class)
                .setAction(AudioPlaybackService.ACTION_PLAY);
        if (getIntent().getExtras() != null) background.putExtras(getIntent().getExtras());
        background.putExtra("background_request", AudioPlaybackService.prepareStart(this));
        background.putExtra("stream_url", streamUrl);
        background.putExtra("stream_mime_type", streamMimeType);
        background.putExtra("resume_position", player.getCurrentPosition());
        background.putExtra("duration_ms", knownDuration);
        background.putExtra("playback_speed", player.getPlaybackParameters().speed);
        try {
            ContextCompat.startForegroundService(this, background);
            backgroundStarted = true;
        } catch (RuntimeException error) {
            CrashLog.save(this, error);
            backgroundStarted = false;
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5804);
        }
    }

    @Override protected void onStart() {
        super.onStart(); started = true; updateScreenAwake();
        if (streamUrl != null && player == null) startPlayer();
        else if (player != null) ui.post(ticker);
    }

    @Override protected void onResume() {
        super.onResume(); if (surface != null) surface.onResume();
        if (backgroundStarted) {
            boolean resumePlaying = AudioPlaybackService.shouldAutoResume();
            long position = AudioPlaybackService.takePosition(this, video, resumePosition);
            resumePosition = position;
            backgroundStarted = false;
            autoResume = resumePlaying;
            if (player != null) {
                player.seekTo(position);
                if (resumePlaying) player.play();
                else player.pause();
                showControls();
            } else if (started && streamUrl != null) startPlayer();
        }
    }

    @Override protected void onPause() {
        if (player != null && player.getPlayWhenReady() && streamUrl != null
                && !failed && !isFinishing()) {
            saveProgress();
            startBackgroundAudio();
            player.pause();
        }
        if (surface != null) surface.onPause(); super.onPause();
    }

    @Override protected void onStop() {
        saveProgress(); started = false; updateScreenAwake();
        ui.removeCallbacks(ticker); ui.removeCallbacks(hideControls);
        if (player != null) player.pause();
        super.onStop();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!intent.getBooleanExtra("resume_background_audio", false)) return;
        setIntent(intent);
        if (!java.util.Objects.equals(intent.getStringExtra("page_url"), video.pageUrl)) {
            recreate();
            return;
        }
        boolean resumePlaying = AudioPlaybackService.shouldAutoResume();
        long position = AudioPlaybackService.takePosition(this, video, resumePosition);
        backgroundStarted = false;
        autoResume = resumePlaying;
        resumePosition = position;
        if (player != null) {
            player.seekTo(position);
            if (resumePlaying) player.play(); else player.pause();
            showControls();
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        focused = hasFocus; updateScreenAwake();
    }

    @Override public void onBackPressed() {
        if (portraitFullscreen && getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT) {
            portraitFullscreen = false;
            applyOrientation();
        } else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("portrait_fullscreen", portraitFullscreen);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        resolver.shutdownNow(); ui.removeCallbacksAndMessages(null);
        if (player != null) { player.clearVideoSurface(); player.release(); player = null; }
        if (surface != null) surface.release();
        updateScreenAwake();
        super.onDestroy();
    }

    private TextView label(String text, int sp, int color) {
        TextView value = new TextView(this);
        value.setText(text == null ? "" : text);
        value.setTextSize(sp); value.setTextColor(color);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color); result.setCornerRadius(dp(radius));
        return result;
    }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width),
                height < 0 ? height : dp(height));
    }

    private static String format(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600,
                seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
