package ru.tubetv.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.text.TextUtils;

import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerActivity extends Activity {
    private static final long PLAYING_CONTROLS_TIMEOUT_MS = 3000L;
    private static final int CONTROLS_HEIGHT_DP = 234;
    private static final float[] PLAYBACK_SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 2f};
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            updateControls();
            updateWallClock();
            if (activityStarted && !audioMovedToBackground) ui.postDelayed(this, 1000);
        }
    };
    private ExoPlayer player;
    private MirrorVideoView videoSurface;
    private ProgressBar loading;
    private SeekBar timeline;
    private TextView message;
    private TextView playState;
    private TextView settingsState;
    private TextView time;
    private TextView tvDuration;
    private TextView repeatState;
    private TextView favoriteState;
    private TextView subscriptionState;
    private TextView captionsState;
    private TextView captionText;
    private TextView wallClock;
    private SideSelectionPanel captionPanel;
    private final java.util.ArrayDeque<SideSelectionPanel> captionParents = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<View> captionShades = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<View> captionOrigins = new java.util.ArrayDeque<>();
    private TextView focusedHint;
    private FrameLayout playerRoot;
    private LinearLayout controls;
    private LinearLayout playbackMenu;
    private LinearLayout mirrorOptions;
    private final List<TextView> speedOptions = new ArrayList<>();
    private final List<TextView> qualityOptions = new ArrayList<>();
    private int qualityHeight;
    private final boolean mobileMode = BuildConfig.MOBILE_VARIANT;
    private TextView normalMirrorOption;
    private TextView mirroredOption;
    private FrameLayout.LayoutParams controlsLayoutParams;
    private final Runnable hideControls = () -> {
        controls.animate().cancel();
        controls.setTranslationY(0f);
        controls.clearFocus();
        controls.setVisibility(View.GONE);
    };
    private String streamUrl;
    private String streamMimeType;
    private List<MediaItem.SubtitleConfiguration> streamSubtitles = java.util.Collections.emptyList();
    private String videoSource;
    private String videoIdentityUrl;
    private boolean autoPlay;
    private boolean trafficMode;
    private boolean audioOnly;
    private int targetHeight;
    private boolean scrubbing;
    private boolean controlsDragging;
    private boolean dpadScrubbing;
    private boolean heldSeekIsLong;
    private boolean mirrored;
    private boolean historyRecordedThisSession;
    private boolean playbackMenuVisible;
    private boolean centerLongPressTriggered;
    private boolean centerKeyHeld;
    private boolean audioMovedToBackground;
    private boolean leavingPlayer;
    private long resumePosition;
    private long knownDuration;
    private long lastSavedAt;
    private int videoWidth;
    private int videoHeight;
    private int safeLeft;
    private int safeRight;
    private int safeBottom;
    private int heldSeekKey = KeyEvent.KEYCODE_UNKNOWN;
    private long dpadSeekPosition;
    private float playbackSpeed;
    private VideoItem historyItem;
    private boolean activityStarted;
    private boolean windowFocused = true;
    private boolean playbackFailed;
    private boolean captionsDisabled = true;
    private boolean captionChoiceManual;
    private boolean playbackPanelRoot;
    private boolean captionsMenuVisible;
    private boolean destroyed;
    private final Runnable openMenuFromLongCenter = () -> {
        if (centerKeyHeld && player != null) {
            centerLongPressTriggered = true;
            setPlaybackMenuVisible(true);
        }
    };
    private final Runnable beginHeldSeek = () -> {
        if (heldSeekKey == KeyEvent.KEYCODE_DPAD_LEFT
                || heldSeekKey == KeyEvent.KEYCODE_DPAD_RIGHT) {
            heldSeekIsLong = true;
            beginDpadScrub();
            moveDpadScrub(seekDelta(heldSeekKey));
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        autoPlay = getIntent().getBooleanExtra("auto_play", true);
        trafficMode = getIntent().getBooleanExtra("traffic_mode", false);
        audioOnly = getIntent().getBooleanExtra("audio_only", false);
        targetHeight = Math.max(0, getIntent().getIntExtra("target_height", 0));
        qualityHeight = trafficMode ? (targetHeight > 0 ? targetHeight : 144) : 0;
        resumePosition = Math.max(0L, getIntent().getLongExtra("resume_position", 0L));
        videoSource = getIntent().getStringExtra("source");
        videoIdentityUrl = getIntent().getStringExtra("page_url");
        knownDuration = Math.max(0L, getIntent().getLongExtra("duration_ms", 0L));
        historyItem = new VideoItem(
                videoSource,
                getIntent().getStringExtra("title"),
                getIntent().getStringExtra("subtitle"),
                getIntent().getStringExtra("thumbnail"),
                getIntent().getStringExtra("resolver_url"),
                videoIdentityUrl,
                knownDuration)
                .withQuality(Math.max(0, getIntent().getIntExtra("max_width", 0)),
                        Math.max(0, getIntent().getIntExtra("max_height", 0)))
                .withChannel(getIntent().getStringExtra("channel_id"),
                        getIntent().getStringExtra("channel_name"),
                        getIntent().getStringExtra("channel_url"),
                        getIntent().getBooleanExtra("live", false))
                .withMetrics(getIntent().getLongExtra("views", -1), getIntent().getLongExtra("published_at", 0));
        historyRecordedThisSession = WatchHistoryStore.contains(this, historyItem);
        playbackSpeed = normalizedPlaybackSpeed(StateStore.playbackSpeed(this));
        if (videoIdentityUrl == null || videoIdentityUrl.isEmpty()) {
            videoIdentityUrl = getIntent().getStringExtra("resolver_url");
        }
        mirrored = WatchProgressStore.isMirrored(this, videoSource, videoIdentityUrl);
        if (getIntent().getBooleanExtra("resume_background_audio", false)) {
            resumePosition = AudioPlaybackService.takePosition(this, historyItem, resumePosition);
        } else {
            AudioPlaybackService.stopPlayback(this);
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(createContent());
        updateScreenAwake();
        requestNotificationPermissionForAudio();
        resolveStream();
    }

    private View createContent() {
        FrameLayout root = new FrameLayout(this);
        playerRoot = root;
        root.setBackgroundColor(Color.BLACK);
        root.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (captionPanel != null) closeCaptionPanel();
                else if (playbackMenuVisible) setPlaybackMenuVisible(false);
                else showControls();
            }
            return true;
        });
        videoSurface = new MirrorVideoView(this);
        videoSurface.setMirrored(mirrored);
        videoSurface.setSurfaceListener(new MirrorVideoView.SurfaceListener() {
            @Override public void onSurfaceAvailable(Surface surface) {
                if (player != null) player.setVideoSurface(surface);
            }

            @Override public void onSurfaceDestroyed(Surface surface) {
                if (player != null) player.clearVideoSurface(surface);
            }
        });
        root.addView(videoSurface, new FrameLayout.LayoutParams(-1, -1));
        if (!mobileMode) {
            captionText = label("", CaptionPreferences.SIZE_SP[CaptionPreferences.size(this)]);
            captionText.setTextColor(CaptionPreferences.COLOR_VALUES[CaptionPreferences.color(this)]);
            captionText.setGravity(Gravity.CENTER);
            captionText.setShadowLayer(dp(3), dp(1), dp(1), Color.BLACK);
            captionText.setVisibility(View.GONE);
            FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            captionParams.setMargins(dp(80), 0, dp(80), dp(100));
            root.addView(captionText, captionParams);
            wallClock = label("", TvPlayerPreferences.CLOCK_SIZE_SP[TvPlayerPreferences.clockSize(this)]);
            wallClock.setGravity(Gravity.RIGHT | Gravity.TOP);
            wallClock.setShadowLayer(dp(3), dp(1), dp(1), Color.BLACK);
            wallClock.setVisibility(TvPlayerPreferences.showClock(this) ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.RIGHT | Gravity.TOP);
            clockParams.setMargins(dp(24), dp(24), dp(48), 0);
            root.addView(wallClock, clockParams);
            updateWallClock();
        }
        ImageView speaker = new ImageView(this);
        speaker.setImageResource(R.drawable.ic_speaker);
        speaker.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        speaker.setVisibility(audioOnly ? View.VISIBLE : View.GONE);
        root.addView(speaker, new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER));

        loading = new ProgressBar(this);
        root.addView(loading, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(18 * tvUiScale());
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(30), dp(20), dp(30), dp(20));
        root.addView(message, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        message.setText("Получаю видеопоток…");

        controls = new DraggableControlsLayout();
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(mobileMode ? 16 : 42), dp(mobileMode ? 20 : 12),
                dp(mobileMode ? 16 : 42), dp(mobileMode ? 20 : 12));
        controls.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, 0xee000000, 0xfa000000}));
        controls.setVisibility(audioOnly ? View.VISIBLE : View.GONE);

        playbackMenu = createPlaybackMenu();
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
                mobileMode ? -1 : Math.min(getResources().getDisplayMetrics().widthPixels * 55 / 100,
                        Math.round(getResources().getDisplayMetrics().widthPixels * .34f * tvUiScale())),
                mobileMode ? -2 : -1, mobileMode ? Gravity.BOTTOM : Gravity.END);
        root.addView(playbackMenu, menuParams);

        TextView title = label(getIntent().getStringExtra("title"), isCompactPlayer() ? 20 : 28);
        title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.BOTTOM);
        controls.addView(title, new LinearLayout.LayoutParams(-1, dp(mobileMode ? 64 : 72)));
        List<String> meta = new ArrayList<>();
        if (!mobileMode) meta.add(historyItem.source);
        meta.add(VideoPresentation.channel(historyItem));
        if (!VideoPresentation.views(historyItem).isEmpty()) meta.add(VideoPresentation.views(historyItem));
        if (!VideoPresentation.published(historyItem).isEmpty()) meta.add(VideoPresentation.published(historyItem));
        TextView metadata = label(TextUtils.join(" · ", meta), isCompactPlayer() ? 13 : 16);
        metadata.setTextColor(0xe0ffffff); metadata.setSingleLine(true);
        controls.addView(metadata, new LinearLayout.LayoutParams(-1, dp(mobileMode ? 28 : 24)));
        focusedHint = label("", 13);
        focusedHint.setVisibility(View.INVISIBLE);
        controls.addView(focusedHint, new LinearLayout.LayoutParams(-1, dp(mobileMode ? 26 : 18)));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        time = label("0:00 / 0:00", isCompactPlayer() ? 12 : 14);
        time.setGravity(mobileMode ? Gravity.END | Gravity.CENTER_VERTICAL
                : Gravity.START | Gravity.CENTER_VERTICAL);
        if (!mobileMode) {
            repeatState = addPlayerControl(row, "↻", "Повтор видео", () -> {
                if (player != null) player.setRepeatMode(player.getRepeatMode() == Player.REPEAT_MODE_ONE
                        ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
                updateControls();
            }, false);
            addPlayerControl(row, "1×", "Скорость воспроизведения",
                    () -> setPlaybackMenuVisible(true), false);
            captionsState = addPlayerControl(row, "CC", "Субтитры · удержать для настроек",
                    this::toggleCaptions, false);
        }
        addPlayerControl(row, mobileMode ? "◀◀" : "↶10", "Назад 10 секунд",
                () -> seekBy(-10_000), false);
        playState = addPlayerControl(row, "▶", "Пауза / воспроизведение", this::togglePlayback, true);
        addPlayerControl(row, mobileMode ? "▶▶" : "10↷", "Вперёд 10 секунд",
                () -> seekBy(10_000), false);
        if (captionsState != null) captionsState.setOnLongClickListener(v -> {
            showCaptionsMenu();
            return true;
        });
        setCaptionControlVisible(false);
        if (!mobileMode && !historyItem.channelId.isEmpty()) subscriptionState = addPlayerControl(row, "⊕",
                "Подписаться на канал", this::toggleSubscription, false);
        favoriteState = addPlayerControl(row, "♡", "Добавить в избранное", this::toggleFavorite, false);
        settingsState = addPlayerControl(row, "HQ", "Качество и настройки", () ->
                setPlaybackMenuVisible(!playbackMenuVisible), false);
        if (mobileMode) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            row.addView(time, new LinearLayout.LayoutParams(dp(78), dp(58)));
            controls.addView(row, new LinearLayout.LayoutParams(-1, dp(58)));
        }

        timeline = new SeekBar(this);
        timeline.setFocusable(true);
        timeline.setMax(1000);
        timeline.setContentDescription("Шкала видео: влево и вправо — перемотка на 10 секунд");
        timeline.setOnFocusChangeListener((view, focused) -> {
            if (focused) {
                showControls();
                ui.removeCallbacks(hideControls);
            } else {
                commitDpadScrub();
                scheduleControlsHide();
            }
        });
        int timelineAccent = mobileMode ? 0xffe53935 : TvAppearancePreferences.accent(this);
        timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(timelineAccent));
        timeline.setThumbTintList(android.content.res.ColorStateList.valueOf(timelineAccent));
        timeline.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff84888d));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || player == null) return;
                long duration = player.getDuration();
                if (duration > 0 && duration != C.TIME_UNSET) {
                    long position = duration * progress / 1000L;
                    time.setText(mobileMode ? formatTime(position) + " / " + formatTime(duration)
                            : formatTime(position));
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                dpadScrubbing = false;
                scrubbing = true;
                ui.removeCallbacks(hideControls);
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (player != null) {
                    long duration = player.getDuration();
                    if (duration > 0 && duration != C.TIME_UNSET) {
                        player.seekTo(duration * bar.getProgress() / 1000L);
                    }
                }
                scrubbing = false;
                showControls();
            }
        });
        controls.addView(timeline, new LinearLayout.LayoutParams(-1, dp(18)));

        if (!mobileMode) {
            LinearLayout timeRow = new LinearLayout(this);
            timeRow.setGravity(Gravity.CENTER_VERTICAL);
            timeRow.addView(time, new LinearLayout.LayoutParams(0, dp(22), 1));
            tvDuration = label("—", 14);
            tvDuration.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            timeRow.addView(tvDuration, new LinearLayout.LayoutParams(0, dp(22), 1));
            controls.addView(timeRow, new LinearLayout.LayoutParams(-1, dp(22)));
            controls.addView(row, new LinearLayout.LayoutParams(-1, dp(76)));
        }

        controlsLayoutParams = new FrameLayout.LayoutParams(-1,
                dp(mobileMode ? CONTROLS_HEIGHT_DP : 288), Gravity.BOTTOM);
        applyControlsLayout();
        root.addView(controls, controlsLayoutParams);
        playbackMenu.bringToFront();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets safe = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            safeLeft = safe.left;
            safeRight = safe.right;
            safeBottom = safe.bottom;
            applyControlsLayout();
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private TextView label(String value, int sp) {
        TextView label = new TextView(this);
        label.setText(value == null ? "" : value);
        label.setTextColor(Color.WHITE);
        label.setTextSize(sp * tvUiScale());
        return label;
    }

    private TextView controlButton(String value) {
        TextView button = label(value, 15);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.filter_chip_background);
        button.setFocusable(true);
        return button;
    }

    private TextView addPlayerControl(LinearLayout row, String symbol, String hint,
                                      Runnable action, boolean large) {
        TextView button = label(symbol, large ? 32 : mobileMode ? 22 : 26);
        button.setGravity(Gravity.CENTER); button.setFocusable(true);
        button.setContentDescription(hint);
        button.setOnClickListener(v -> action.run());
        button.setOnFocusChangeListener((view, focused) -> {
            GradientDrawable background = new GradientDrawable();
            background.setColor(focused ? (mobileMode ? 0xccdf3634
                    : TvAppearancePreferences.focus(this)) : Color.TRANSPARENT);
            if (!mobileMode && large) {
                background.setStroke(dp(2), TvAppearancePreferences.accent(this));
                background.setCornerRadius(dp(40));
            } else background.setCornerRadius(dp(mobileMode ? 12 : 2));
            view.setBackground(background);
            focusedHint.setText(focused ? hint : "");
            focusedHint.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
        });
        if (!mobileMode) {
            String iconName = symbol.equals("↻") ? "player_repeat"
                    : symbol.equals("↶10") ? "player_seek_back"
                    : symbol.equals("10↷") ? "player_seek_forward"
                    : symbol.equals("▶") ? "player_play"
                    : symbol.equals("⊕") ? "player_subscribe"
                    : symbol.equals("♡") ? "favorite"
                    : symbol.equals("HQ") ? "player_quality" : null;
            if (iconName != null) setTvPlayerIcon(button, iconName);
            if (large) {
                GradientDrawable ring = new GradientDrawable();
                ring.setColor(Color.TRANSPARENT);
                ring.setCornerRadius(dp(40));
                ring.setStroke(dp(2), TvAppearancePreferences.accent(this));
                button.setBackground(ring);
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(button, new LinearLayout.LayoutParams(dp(58), dp(50)));
            String caption = hint.startsWith("Назад") ? "Назад"
                    : hint.startsWith("Вперёд") ? "Вперёд"
                    : hint.startsWith("Пауза") ? "Пауза / пуск"
                    : hint.startsWith("Повтор") ? "Повтор"
                    : hint.startsWith("Скорость") ? "Скорость"
                    : hint.startsWith("Субтитры") ? "Субтитры"
                    : hint.startsWith("Подписаться") ? "Подписаться"
                    : hint.startsWith("Добавить в избранное") ? "Избранное" : "Качество";
            TextView captionLabel = label(caption, 12);
            captionLabel.setTextColor(0xffd0d9e7);
            captionLabel.setGravity(Gravity.CENTER);
            captionLabel.setMaxLines(1);
            captionLabel.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(captionLabel, new LinearLayout.LayoutParams(-1, dp(24)));
            row.addView(cell, new LinearLayout.LayoutParams(0, dp(76), 1));
            return button;
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(large ? 58 : 48), dp(large ? 58 : 48));
        params.rightMargin = dp(mobileMode ? 4 : 18); row.addView(button, params);
        return button;
    }

    private void setTvPlayerIcon(TextView button, String name) {
        button.setText("");
        button.setCompoundDrawablesWithIntrinsicBounds(
                TvIcons.icon(name, Color.WHITE, dp(largeIcon(name) ? 32 : 29)), null, null, null);
    }

    private boolean largeIcon(String name) {
        return "player_play".equals(name) || "player_pause".equals(name);
    }

    private void setCaptionControlVisible(boolean visible) {
        if (captionsState == null) return;
        View target = !mobileMode && captionsState.getParent() instanceof View
                ? (View) captionsState.getParent() : captionsState;
        target.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private LinearLayout createPlaybackMenu() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setVisibility(View.GONE);
        menu.setPadding(dp(24), dp(26), dp(24), dp(26));
        menu.setBackgroundColor(0xf21a1a1a);

        TextView menuTitle = label("Качество и скорость", 23);
        menu.addView(menuTitle, new LinearLayout.LayoutParams(-1, dp(50)));

        TextView qualityTitle = label("Качество видео", 14);
        menu.addView(qualityTitle, new LinearLayout.LayoutParams(-1, dp(28)));
        LinearLayout qualityRow = new LinearLayout(this);
        int[] heights = {0, 360, 720, 1080};
        for (int height : heights) {
            TextView option = settingsOption(height == 0 ? "Авто" : "до " + height + "p");
            option.setTag(height);
            option.setOnClickListener(v -> setQualityHeight((Integer) v.getTag()));
            qualityOptions.add(option);
            qualityRow.addView(option, new LinearLayout.LayoutParams(0, dp(42), 1));
        }
        menu.addView(qualityRow, new LinearLayout.LayoutParams(-1, dp(50)));

        TextView speedTitle = label("Скорость", isCompactPlayer() ? 12 : 14);
        speedTitle.setGravity(Gravity.CENTER_VERTICAL);
        menu.addView(speedTitle, new LinearLayout.LayoutParams(-1, dp(20)));

        LinearLayout speedRow = new LinearLayout(this);
        for (float speed : PLAYBACK_SPEEDS) {
            TextView option = settingsOption(speedLabel(speed));
            option.setTag(speed);
            option.setOnClickListener(view -> setPlaybackSpeed((Float) view.getTag()));
            speedOptions.add(option);
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(0, dp(34), 1f);
            optionParams.setMargins(dp(3), 0, dp(3), 0);
            speedRow.addView(option, optionParams);
        }
        menu.addView(speedRow, new LinearLayout.LayoutParams(-1, dp(36)));

        mirrorOptions = new LinearLayout(this);
        mirrorOptions.setGravity(Gravity.CENTER_VERTICAL);
        TextView mirrorTitle = label("Отображение", isCompactPlayer() ? 12 : 14);
        mirrorTitle.setGravity(Gravity.CENTER_VERTICAL);
        normalMirrorOption = settingsOption("Обычное");
        mirroredOption = settingsOption("Зеркальное");
        normalMirrorOption.setOnClickListener(view -> setMirrored(false));
        mirroredOption.setOnClickListener(view -> setMirrored(true));
        mirrorOptions.addView(mirrorTitle, new LinearLayout.LayoutParams(0, dp(34), 0.8f));
        LinearLayout.LayoutParams normalParams = new LinearLayout.LayoutParams(0, dp(34), 1f);
        normalParams.setMargins(dp(3), 0, dp(3), 0);
        mirrorOptions.addView(normalMirrorOption, normalParams);
        LinearLayout.LayoutParams mirroredParams = new LinearLayout.LayoutParams(0, dp(34), 1f);
        mirroredParams.setMargins(dp(3), 0, dp(3), 0);
        mirrorOptions.addView(mirroredOption, mirroredParams);
        mirrorOptions.setVisibility(audioOnly ? View.GONE : View.VISIBLE);
        menu.addView(mirrorOptions, new LinearLayout.LayoutParams(-1, dp(38)));
        if (mobileMode) {
            TextView repeat = settingsOption("Повтор");
            repeat.setOnClickListener(v -> {
                if (player != null) player.setRepeatMode(player.getRepeatMode() == Player.REPEAT_MODE_ONE
                        ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
                updateControls();
            });
            menu.addView(repeat, new LinearLayout.LayoutParams(-1, dp(42)));
            if (!historyItem.channelId.isEmpty()) {
                TextView subscribe = settingsOption("Подписаться / отписаться");
                subscribe.setOnClickListener(v -> toggleSubscription());
                menu.addView(subscribe, new LinearLayout.LayoutParams(-1, dp(42)));
            }
            TextView favorite = settingsOption("Добавить / убрать из избранного");
            favorite.setOnClickListener(v -> toggleFavorite());
            menu.addView(favorite, new LinearLayout.LayoutParams(-1, dp(42)));
        }
        updatePlaybackSettingsUi();
        return menu;
    }

    private TextView settingsOption(String value) {
        TextView option = controlButton(value);
        option.setTextSize(isCompactPlayer() ? 12 : 14);
        option.setPadding(dp(4), 0, dp(4), 0);
        return option;
    }

    private void resolveStream() {
        String url = getIntent().getStringExtra("resolver_url");
        resolver.execute(() -> {
            try {
                PlaybackInfo resolved = new StreamResolver().resolveForPlayback(url, audioOnly);
                runOnUiThread(() -> {
                    if (destroyed || isFinishing()) return;
                    streamUrl = resolved.streamUrl;
                    streamMimeType = resolved.streamMimeType;
                    streamSubtitles = resolved.subtitles;
                    if (activityStarted) startPlayerSafely();
                });
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()) return;
                runOnUiThread(() -> {
                    if (!destroyed && !isFinishing()) {
                        showError(error.getMessage() == null ? "Видео больше недоступно" : error.getMessage());
                    }
                });
            }
        });
    }

    private void startPlayerSafely() {
        try {
            startPlayer();
        } catch (Throwable error) {
            CrashLog.save(getApplicationContext(), error);
            String detail = error.getClass().getSimpleName();
            if (error.getMessage() != null) detail += ": " + error.getMessage();
            showError("Сбой инициализации плеера: " + detail);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startPlayer() {
        if (!activityStarted || destroyed || isFinishing() || streamUrl == null || player != null) return;
        player = PlaybackEngineFactory.create(this, videoSource, videoIdentityUrl,
                trafficMode, targetHeight, audioOnly);
        if (audioOnly) videoSurface.setVisibility(View.INVISIBLE);
        videoSurface.setMirrored(mirrored);
        Surface output = videoSurface.getVideoSurface();
        if (output != null) player.setVideoSurface(output);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                updateScreenAwake();
                if (state == Player.STATE_READY) {
                    loading.setVisibility(View.GONE);
                    message.setVisibility(View.GONE);
                    if (!autoPlay || audioOnly) showControls();
                } else if (state == Player.STATE_ENDED) {
                    if (!mobileMode && !historyItem.isLive
                            && player.getRepeatMode() != Player.REPEAT_MODE_ONE) {
                        ui.post(() -> {
                            if (destroyed || isFinishing() || player == null
                                    || player.getPlaybackState() != Player.STATE_ENDED) return;
                            leavingPlayer = true;
                            saveProgress();
                            StateStore.markReturnScreen(PlayerActivity.this);
                            finish();
                        });
                    } else showControls();
                }
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updateControls();
                updateScreenAwake();
                scheduleControlsHide();
            }

            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                updateScreenAwake();
            }

            @Override public void onTracksChanged(Tracks tracks) {
                boolean captionsAvailable = false;
                for (Tracks.Group group : tracks.getGroups())
                    if (group.getType() == C.TRACK_TYPE_TEXT)
                        for (int i = 0; i < group.length; i++)
                            if (group.isTrackSupported(i)) captionsAvailable = true;
                setCaptionControlVisible(captionsAvailable);
                if (captionsAvailable && !captionsDisabled && !captionChoiceManual)
                    selectPreferredCaption(tracks);
            }

            @Override public void onCues(CueGroup cues) {
                if (captionText == null) return;
                StringBuilder lines = new StringBuilder();
                for (androidx.media3.common.text.Cue cue : cues.cues)
                    if (cue.text != null) {
                        if (lines.length() > 0) lines.append('\n');
                        lines.append(cue.text);
                    }
                captionText.setText(lines.toString());
                captionText.setVisibility(!captionsDisabled && lines.length() > 0
                        ? View.VISIBLE : View.GONE);
            }

            @Override public void onVideoSizeChanged(VideoSize size) {
                videoWidth = size.width;
                videoHeight = size.height;
                fitSurface(size.width, size.height);
            }

            @Override public void onPlayerError(PlaybackException error) {
                playbackFailed = true;
                updateScreenAwake();
                showError("Ошибка воспроизведения: " + error.getErrorCodeName());
            }
        });
        player.setMediaItem(PlaybackEngineFactory.mediaItem(streamUrl, streamMimeType)
                .buildUpon().setSubtitleConfigurations(streamSubtitles).build());
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, captionsDisabled).build());
        if (resumePosition > 0) player.seekTo(resumePosition);
        player.setPlaybackSpeed(playbackSpeed);
        player.setPlayWhenReady(autoPlay);
        player.prepare();
        restartProgressTicker();
    }

    private void restartProgressTicker() {
        ui.removeCallbacks(progressTicker);
        if (activityStarted && player != null && !audioMovedToBackground) ui.post(progressTicker);
    }

    private void showControls() {
        boolean wasHidden = controls.getVisibility() != View.VISIBLE;
        controls.animate().cancel();
        if (wasHidden) controls.setTranslationY(0f);
        controls.setVisibility(View.VISIBLE);
        updateControls();
        scheduleControlsHide();
    }

    private void scheduleControlsHide() {
        ui.removeCallbacks(hideControls);
        if (audioOnly || playbackMenuVisible || captionsMenuVisible || scrubbing || dpadScrubbing
                || timeline.hasFocus()
                || controlsDragging || player == null
                || controls.getVisibility() != View.VISIBLE
                || player.getPlaybackState() == Player.STATE_ENDED) return;
        if (player.isPlaying()) ui.postDelayed(hideControls, PLAYING_CONTROLS_TIMEOUT_MS);
    }

    private void updateControls() {
        if (player == null) return;
        long position = Math.max(0, player.getCurrentPosition());
        long duration = player.getDuration();
        if (!scrubbing) {
            long displayedPosition = dpadScrubbing ? dpadSeekPosition : position;
            if (duration <= 0 || duration == C.TIME_UNSET) {
                timeline.setProgress(0);
                time.setText(mobileMode ? formatTime(displayedPosition) + " / —"
                        : formatTime(displayedPosition));
                if (tvDuration != null) tvDuration.setText("В эфире");
            } else {
                timeline.setProgress((int) Math.min(1000,
                        displayedPosition * 1000 / duration));
                time.setText(mobileMode ? formatTime(displayedPosition) + " / " + formatTime(duration)
                        : formatTime(displayedPosition));
                if (tvDuration != null) tvDuration.setText(formatTime(duration));
            }
        }
        if (mobileMode) playState.setText(player.isPlaying() ? "Ⅱ" : "▶");
        else setTvPlayerIcon(playState, player.isPlaying() ? "player_pause" : "player_play");
        if (repeatState != null) {
            if (mobileMode) repeatState.setText(player.getRepeatMode() == Player.REPEAT_MODE_ONE ? "↻¹" : "↻");
            else setTvPlayerIcon(repeatState, "player_repeat");
        }
        if (favoriteState != null) {
            if (mobileMode) favoriteState.setText(FavoriteStore.contains(this, historyItem) ? "♥" : "♡");
            else setTvPlayerIcon(favoriteState, "favorite");
        }
        if (subscriptionState != null) {
            if (mobileMode) subscriptionState.setText(SubscriptionStore.contains(this, historyItem) ? "⊖" : "⊕");
            else setTvPlayerIcon(subscriptionState, SubscriptionStore.contains(this, historyItem)
                    ? "player_unsubscribe" : "player_subscribe");
        }
        long now = System.currentTimeMillis();
        if (activityStarted && !audioMovedToBackground && now - lastSavedAt > 5000) {
            StateStore.savePlayerPosition(this, videoSource, videoIdentityUrl, position);
            saveWatchProgress(position, duration);
            lastSavedAt = now;
        }
    }

    private void togglePlayback() {
        if (player == null) return;
        boolean wasPlaying = player.isPlaying();
        commitDpadScrub();
        if (wasPlaying) player.pause(); else player.play();
        showControls();
    }

    private void seekBy(long deltaMs) {
        if (player == null) return;
        long target = Math.max(0, player.getCurrentPosition() + deltaMs);
        long duration = player.getDuration();
        if (duration > 0 && duration != C.TIME_UNSET) target = Math.min(target, duration);
        player.seekTo(target);
        showControls();
    }

    private void beginDpadScrub() {
        if (player == null) return;
        if (!dpadScrubbing) {
            dpadSeekPosition = Math.max(0L, player.getCurrentPosition());
            dpadScrubbing = true;
        }
        showControls();
        ui.removeCallbacks(hideControls);
    }

    private void moveDpadScrub(long deltaMs) {
        if (player == null) return;
        beginDpadScrub();
        long target = Math.max(0L, dpadSeekPosition + deltaMs);
        long duration = player.getDuration();
        if (duration > 0 && duration != C.TIME_UNSET) target = Math.min(target, duration);
        dpadSeekPosition = target;
        updateControls();
    }

    private void commitDpadScrub() {
        if (player == null || !dpadScrubbing) return;
        long position = dpadSeekPosition;
        dpadScrubbing = false;
        player.seekTo(position);
    }

    private static long seekDelta(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -10_000L : 10_000L;
    }

    private void toggleFavorite() {
        try {
            FavoriteStore.set(this, historyItem, !FavoriteStore.contains(this, historyItem));
            updateControls();
        } catch (Exception error) { focusedHint.setText(error.getMessage()); }
    }

    private void toggleSubscription() {
        try {
            SubscriptionStore.set(this, historyItem, !SubscriptionStore.contains(this, historyItem));
            updateControls();
        } catch (Exception error) { focusedHint.setText(error.getMessage()); }
    }

    private static final class SubtitleChoice {
        final Tracks.Group group;
        final int track;
        final String label;
        final int order;
        SubtitleChoice(Tracks.Group group, int track, String label, int order) {
            this.group = group; this.track = track; this.label = label; this.order = order;
        }
    }

    private void showCaptionsMenu() {
        if (player == null || captionsState == null) return;
        List<SubtitleChoice> choices = new ArrayList<>();
        int selected = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int track = 0; track < group.length; track++) {
                if (!group.isTrackSupported(track)) continue;
                Format format = group.getTrackFormat(track);
                String language = format.language == null ? "" : format.language;
                String code = Locale.forLanguageTag(language).getLanguage();
                String name = "ru".equals(code) ? "Русский" : "en".equals(code) ? "English"
                        : language.isEmpty() || "und".equals(language) ? "Субтитры"
                        : Locale.forLanguageTag(language).getDisplayLanguage(new Locale("ru"));
                String supplied = format.label == null ? "" : format.label.trim();
                String label = supplied.isEmpty() ? name
                        : supplied.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))
                                ? supplied : name + " · " + supplied;
                int order = "ru".equals(code) ? 0 : "en".equals(code) ? 1 : 2;
                choices.add(new SubtitleChoice(group, track, label, order));
            }
        }
        if (choices.isEmpty()) return;
        java.util.Collections.sort(choices, (a, b) -> Integer.compare(a.order, b.order));
        String[] labels = new String[choices.size() + 5];
        labels[0] = "Выключено";
        for (int i = 0; i < choices.size(); i++) {
            SubtitleChoice choice = choices.get(i);
            labels[i + 1] = choice.label;
            if (!captionsDisabled && choice.group.isTrackSelected(choice.track)) selected = i + 1;
        }
        int extra = choices.size() + 1;
        labels[extra] = "Язык: " + CaptionPreferences.LANGUAGES[CaptionPreferences.language(this)];
        labels[extra + 1] = "Размер: " + CaptionPreferences.SIZES[CaptionPreferences.size(this)];
        labels[extra + 2] = "Цвет: " + CaptionPreferences.COLORS[CaptionPreferences.color(this)];
        labels[extra + 3] = "Канал: " + VideoPresentation.channel(historyItem);
        captionsMenuVisible = true;
        ui.removeCallbacks(hideControls);
        openCaptionPanel("Субтитры · " + historyItem.source, labels, selected, index -> {
            if (index < extra) {
                androidx.media3.common.TrackSelectionParameters.Builder parameters =
                        player.getTrackSelectionParameters().buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT);
                captionsDisabled = index == 0;
                captionChoiceManual = !captionsDisabled;
                parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, captionsDisabled);
                if (!captionsDisabled) {
                    SubtitleChoice choice = choices.get(index - 1);
                    parameters.setOverrideForType(new TrackSelectionOverride(
                            choice.group.getMediaTrackGroup(), choice.track));
                }
                player.setTrackSelectionParameters(parameters.build());
                captionsState.setAlpha(captionsDisabled ? .5f : 1f);
                if (captionsDisabled && captionText != null) captionText.setVisibility(View.GONE);
                closeCaptionPanel();
            } else if (index == extra) {
                openCaptionSetting("Язык субтитров", CaptionPreferences.LANGUAGES,
                        CaptionPreferences.language(this), choice -> {
                            CaptionPreferences.setLanguage(this, choice);
                            captionChoiceManual = false;
                            selectPreferredCaption(player.getCurrentTracks());
                        }, () -> captionPanel.setChoiceLabel(extra, "Язык: "
                                + CaptionPreferences.LANGUAGES[CaptionPreferences.language(this)]));
            } else if (index == extra + 1) {
                openCaptionSetting("Размер субтитров", CaptionPreferences.SIZES,
                        CaptionPreferences.size(this), choice -> {
                            CaptionPreferences.setSize(this, choice);
                            captionText.setTextSize(CaptionPreferences.SIZE_SP[choice]);
                        }, () -> captionPanel.setChoiceLabel(extra + 1, "Размер: "
                                + CaptionPreferences.SIZES[CaptionPreferences.size(this)]));
            } else if (index == extra + 2) {
                openCaptionSetting("Цвет субтитров", CaptionPreferences.COLORS,
                        CaptionPreferences.color(this), choice -> {
                            CaptionPreferences.setColor(this, choice);
                            captionText.setTextColor(CaptionPreferences.COLOR_VALUES[choice]);
                        }, () -> captionPanel.setChoiceLabel(extra + 2, "Цвет: "
                                + CaptionPreferences.COLORS[CaptionPreferences.color(this)]));
            } else if (!historyItem.channelUrl.isEmpty()) {
                closeCaptionPanel();
                VideoActions.openChannel(this, historyItem);
            }
        });
    }

    private interface CaptionAction { void apply(int index); }

    private void openCaptionSetting(String title, String[] labels, int selected,
                                    CaptionAction action, Runnable refreshParent) {
        openCaptionPanel(title, labels, selected, index -> {
            action.apply(index);
            closeCaptionPanel();
            if (captionPanel != null) refreshParent.run();
        });
    }

    private void openCaptionPanel(String title, String[] labels, int selected,
                                  CaptionAction action) {
        captionsMenuVisible = true;
        ui.removeCallbacks(hideControls);
        if (captionPanel != null) {
            View origin = getCurrentFocus();
            captionOrigins.push(origin == null ? captionPanel : origin);
            captionParents.push(captionPanel);
            captionPanel.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            View shade = new View(this);
            shade.setBackgroundColor(0x99000000);
            playerRoot.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            captionShades.push(shade);
        }
        captionPanel = new SideSelectionPanel(this, title, labels, selected, action::apply);
        int width = Math.min(dp(480), Math.round(getResources().getDisplayMetrics().widthPixels * .42f));
        if (!captionParents.isEmpty()) width = Math.round(width * .90f);
        playerRoot.addView(captionPanel, new FrameLayout.LayoutParams(width, -1, Gravity.RIGHT));
        if (TvAppearancePreferences.animations(this)) {
            captionPanel.setTranslationX(width);
            captionPanel.setAlpha(.82f);
            captionPanel.animate().translationX(0).alpha(1f).setDuration(340)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
        }
    }

    private void closeCaptionPanel() {
        if (captionPanel == null) return;
        SideSelectionPanel closing = captionPanel;
        playerRoot.removeView(closing);
        if (!captionParents.isEmpty()) {
            playerRoot.removeView(captionShades.pop());
            captionPanel = captionParents.pop();
            captionPanel.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
            View origin = captionOrigins.pop();
            if (origin.isAttachedToWindow()) origin.requestFocus();
        } else {
            captionPanel = null;
            captionsMenuVisible = false;
            showControls();
            View target = playbackPanelRoot ? settingsState : captionsState;
            playbackPanelRoot = false;
            if (target != null) target.requestFocus();
        }
    }

    private void toggleCaptions() {
        if (player == null) return;
        captionsDisabled = !captionsDisabled;
        captionChoiceManual = false;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, captionsDisabled).build());
        if (!captionsDisabled) selectPreferredCaption(player.getCurrentTracks());
        else if (captionText != null) captionText.setVisibility(View.GONE);
        captionsState.setAlpha(captionsDisabled ? .5f : 1f);
    }

    private void selectPreferredCaption(Tracks tracks) {
        if (captionsDisabled || player == null) return;
        int preferred = CaptionPreferences.language(this);
        String code = CaptionPreferences.LANGUAGE_CODES[preferred];
        Tracks.Group fallbackGroup = null;
        int fallbackTrack = -1;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                String language = group.getTrackFormat(i).language;
                if (fallbackGroup == null || (language != null && !language.isEmpty()
                        && (fallbackGroup.getTrackFormat(fallbackTrack).language == null
                        || fallbackGroup.getTrackFormat(fallbackTrack).language.isEmpty()))) {
                    fallbackGroup = group;
                    fallbackTrack = i;
                }
                if (!code.isEmpty() && language != null && language.startsWith(code)) {
                    selectCaptionTrack(group, i);
                    return;
                }
            }
        }
        if (fallbackGroup != null) selectCaptionTrack(fallbackGroup, fallbackTrack);
    }

    private void selectCaptionTrack(Tracks.Group group, int track) {
        if (group.isTrackSelected(track)) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setOverrideForType(new TrackSelectionOverride(
                        group.getMediaTrackGroup(), track)).build());
    }

    private void updateScreenAwake() {
        boolean awake = PlaybackScreenPolicy.shouldKeepAwake(activityStarted, windowFocused,
                player != null && player.getPlayWhenReady(),
                player == null ? Player.STATE_IDLE : player.getPlaybackState(), playbackFailed);
        if (awake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (playerRoot != null) playerRoot.setKeepScreenOn(awake);
    }

    private void updateWallClock() {
        if (wallClock == null) return;
        wallClock.setText(android.text.format.DateFormat.format(
                android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a",
                System.currentTimeMillis()));
    }

    private boolean handleHorizontalDpad(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT
                && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (player == null) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0 || heldSeekKey != keyCode) {
                ui.removeCallbacks(beginHeldSeek);
                heldSeekKey = keyCode;
                heldSeekIsLong = false;
                ui.postDelayed(beginHeldSeek, ViewConfiguration.getLongPressTimeout());
            } else {
                if (!heldSeekIsLong) {
                    ui.removeCallbacks(beginHeldSeek);
                    heldSeekIsLong = true;
                    beginDpadScrub();
                    moveDpadScrub(seekDelta(keyCode));
                } else {
                    moveDpadScrub(seekDelta(keyCode));
                }
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && heldSeekKey == keyCode) {
            ui.removeCallbacks(beginHeldSeek);
            if (!heldSeekIsLong) {
                if (dpadScrubbing) moveDpadScrub(seekDelta(keyCode));
                else seekBy(seekDelta(keyCode));
            } else {
                commitDpadScrub();
                showControls();
            }
            heldSeekKey = KeyEvent.KEYCODE_UNKNOWN;
            heldSeekIsLong = false;
            return true;
        }
        return true;
    }

    private void setMirrored(boolean value) {
        if (audioOnly || mirrored == value) return;
        mirrored = value;
        videoSurface.setMirrored(mirrored);
        saveMirrorState();
        updatePlaybackSettingsUi();
    }

    private void setPlaybackSpeed(float speed) {
        playbackSpeed = normalizedPlaybackSpeed(speed);
        StateStore.savePlaybackSpeed(this, playbackSpeed);
        if (player != null) player.setPlaybackSpeed(playbackSpeed);
        updatePlaybackSettingsUi();
    }

    private void setQualityHeight(int height) {
        qualityHeight = height;
        if (player != null && !audioOnly) {
            int width = height == 0 ? 3840 : (height * 16 + 8) / 9;
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setMaxVideoSize(width, height == 0 ? 2160 : height).build());
        }
        updatePlaybackSettingsUi();
    }

    private void showTvPlaybackMenu() {
        if (captionPanel != null) return;
        playbackPanelRoot = true;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!audioOnly) {
            final int row = labels.size();
            labels.add("Качество: " + qualityLabel() + "  ›");
            actions.add(() -> {
                int[] heights = {0, 360, 720, 1080};
                String[] options = {"Авто", "до 360p", "до 720p", "до 1080p"};
                int selected = 0;
                for (int i = 0; i < heights.length; i++) if (qualityHeight == heights[i]) selected = i;
                openCaptionSetting("Качество видео", options, selected,
                        choice -> setQualityHeight(heights[choice]),
                        () -> captionPanel.setChoiceLabel(row, "Качество: " + qualityLabel() + "  ›"));
            });
        }
        final int speedRow = labels.size();
        labels.add("Скорость: " + speedLabel(playbackSpeed) + "  ›");
        actions.add(() -> {
            String[] options = new String[PLAYBACK_SPEEDS.length];
            int selected = 0;
            for (int i = 0; i < options.length; i++) {
                options[i] = speedLabel(PLAYBACK_SPEEDS[i]);
                if (Math.abs(playbackSpeed - PLAYBACK_SPEEDS[i]) < .01f) selected = i;
            }
            openCaptionSetting("Скорость", options, selected,
                    choice -> setPlaybackSpeed(PLAYBACK_SPEEDS[choice]),
                    () -> captionPanel.setChoiceLabel(speedRow,
                            "Скорость: " + speedLabel(playbackSpeed) + "  ›"));
        });
        if (!audioOnly) {
            final int mirrorRow = labels.size();
            labels.add("Отображение: " + (mirrored ? "Зеркальное" : "Обычное") + "  ›");
            actions.add(() -> openCaptionSetting("Отображение",
                    new String[]{"Обычное", "Зеркальное"}, mirrored ? 1 : 0,
                    choice -> setMirrored(choice == 1),
                    () -> captionPanel.setChoiceLabel(mirrorRow, "Отображение: "
                            + (mirrored ? "Зеркальное" : "Обычное") + "  ›")));
        }
        final int repeatRow = labels.size();
        labels.add("Повтор: " + (player != null && player.getRepeatMode() == Player.REPEAT_MODE_ONE
                ? "Включён" : "Выключен") + "  ›");
        actions.add(() -> openCaptionSetting("Повтор",
                new String[]{"Выключен", "Включён"},
                player != null && player.getRepeatMode() == Player.REPEAT_MODE_ONE ? 1 : 0,
                choice -> {
                    if (player != null) player.setRepeatMode(choice == 1
                            ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                    updateControls();
                }, () -> captionPanel.setChoiceLabel(repeatRow, "Повтор: "
                        + (player != null && player.getRepeatMode() == Player.REPEAT_MODE_ONE
                        ? "Включён" : "Выключен") + "  ›")));
        labels.add("Субтитры  ›"); actions.add(this::showCaptionsMenu);
        labels.add("Информация о видео  ›"); actions.add(this::showTvVideoInfo);
        openCaptionPanel("Параметры видео", labels.toArray(new String[0]), -1,
                index -> actions.get(index).run());
    }

    private String qualityLabel() {
        return qualityHeight == 0 ? "Авто" : "до " + qualityHeight + "p";
    }

    private void showTvVideoInfo() {
        String channel = VideoPresentation.channel(historyItem);
        List<String> labels = new ArrayList<>();
        labels.add("Источник: " + historyItem.source);
        labels.add("Название: " + historyItem.title);
        labels.add("Канал: " + channel + (historyItem.channelId.isEmpty() ? "" : "  ›"));
        String views = VideoPresentation.views(historyItem);
        if (!views.isEmpty()) labels.add(views);
        openCaptionPanel("О видео", labels.toArray(new String[0]), -1, index -> {
            if (index == 2 && !historyItem.channelId.isEmpty())
                VideoActions.openChannel(this, historyItem);
        });
    }

    private void setPlaybackMenuVisible(boolean visible) {
        if (!mobileMode) {
            if (visible) showTvPlaybackMenu();
            else if (captionPanel != null) closeCaptionPanel();
            return;
        }
        if (playbackMenu == null || settingsState == null) return;
        playbackMenuVisible = visible;
        playbackMenu.setVisibility(visible ? View.VISIBLE : View.GONE);
        settingsState.setSelected(visible);
        if (visible) {
            showControls();
            TextView selected = selectedSpeedOption();
            if (DeviceType.isTelevision(this) && selected != null) selected.requestFocus();
        } else {
            controls.clearFocus();
            scheduleControlsHide();
        }
    }

    private void updatePlaybackSettingsUi() {
        if (settingsState != null) settingsState.setText("HQ");
        for (TextView option : speedOptions) {
            Object tag = option.getTag();
            option.setSelected(tag instanceof Float
                    && Math.abs((Float) tag - playbackSpeed) < 0.001f);
        }
        for (TextView option : qualityOptions) option.setSelected(option.getTag() instanceof Integer
                && (Integer) option.getTag() == qualityHeight);
        if (normalMirrorOption != null) normalMirrorOption.setSelected(!mirrored);
        if (mirroredOption != null) mirroredOption.setSelected(mirrored);
    }

    private TextView selectedSpeedOption() {
        for (TextView option : speedOptions) {
            if (option.isSelected()) return option;
        }
        return speedOptions.isEmpty() ? null : speedOptions.get(0);
    }

    private String settingsLabel() {
        return "⚙  " + speedLabel(playbackSpeed) + (mirrored && !audioOnly ? "  ↔" : "");
    }

    private int playbackMenuHeightDp() {
        return audioOnly ? 64 : 102;
    }

    private static float normalizedPlaybackSpeed(float speed) {
        for (float supported : PLAYBACK_SPEEDS) {
            if (Math.abs(supported - speed) < 0.01f) return supported;
        }
        return 1f;
    }

    private static String speedLabel(float speed) {
        if (Math.abs(speed - 1f) < 0.001f) return "1×";
        if (Math.abs(speed - 2f) < 0.001f) return "2×";
        if (Math.abs(speed - 0.75f) < 0.001f) return "0,75×";
        if (Math.abs(speed - 1.25f) < 0.001f) return "1,25×";
        return "1,5×";
    }

    private static String formatTime(long millis) {
        long total = Math.max(0, millis / 1000);
        long hours = total / 3600;
        long minutes = total % 3600 / 60;
        long seconds = total % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private void fitSurface(int width, int height) {
        if (width <= 0 || height <= 0) return;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float ratio = Math.min(screenW / (float) width, screenH / (float) height);
        videoSurface.setLayoutParams(new FrameLayout.LayoutParams(
                Math.round(width * ratio), Math.round(height * ratio), Gravity.CENTER));
    }

    private boolean isCompactPlayer() {
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT
                || widthDp < 720;
    }

    private void applyControlsLayout() {
        if (controlsLayoutParams == null) return;
        controlsLayoutParams.setMargins(safeLeft, 0, safeRight, safeBottom);
        if (controls != null) controls.setLayoutParams(controlsLayoutParams);
    }

    private final class DraggableControlsLayout extends LinearLayout {
        private final int touchSlop;
        private float downX;
        private float downY;
        private float startTranslation;
        private boolean dragging;

        DraggableControlsLayout() {
            super(PlayerActivity.this);
            touchSlop = ViewConfiguration.get(PlayerActivity.this).getScaledTouchSlop();
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    beginGesture(event);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    return startDraggingIfNeeded(event);
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    controlsDragging = false;
                    scheduleControlsHide();
                    return false;
                default:
                    return false;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    beginGesture(event);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!startDraggingIfNeeded(event)) return true;
                    moveWithFinger(event.getRawY() - downY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) {
                        controlsDragging = false;
                        scheduleControlsHide();
                        return true;
                    }
                    dragging = false;
                    controlsDragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    animate().translationY(0f)
                            .setDuration(240L)
                            .setInterpolator(new OvershootInterpolator(0.6f))
                            .withLayer()
                            .withEndAction(PlayerActivity.this::scheduleControlsHide)
                            .start();
                    return true;
                default:
                    return true;
            }
        }

        private void beginGesture(MotionEvent event) {
            downX = event.getRawX();
            downY = event.getRawY();
            startTranslation = getTranslationY();
            dragging = false;
            animate().cancel();
            ui.removeCallbacks(hideControls);
        }

        private boolean startDraggingIfNeeded(MotionEvent event) {
            if (dragging) return true;
            float dx = event.getRawX() - downX;
            float dy = event.getRawY() - downY;
            if (Math.abs(dy) <= touchSlop || Math.abs(dy) <= Math.abs(dx)) return false;
            dragging = true;
            controlsDragging = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }

        private void moveWithFinger(float distance) {
            float translation = startTranslation + distance;
            float minimum = -getTop();
            float maximum = ((View) getParent()).getHeight() - getBottom();
            setTranslationY(Math.max(minimum, Math.min(maximum, translation)));
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyControlsLayout();
        if (videoWidth > 0 && videoHeight > 0) fitSurface(videoWidth, videoHeight);
    }

    private void showError(String text) {
        UnavailableVideoStore.add(this, historyItem);
        playbackFailed = true;
        updateScreenAwake();
        loading.setVisibility(View.GONE);
        controls.setVisibility(View.GONE);
        message.setVisibility(View.VISIBLE);
        message.setText(text + "\n\nOK — открыть официальный плеер");
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                setPlaybackMenuVisible(!playbackMenuVisible);
            }
            return true;
        }
        if (captionPanel != null || playbackMenuVisible) return super.dispatchKeyEvent(event);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                showControls();
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) playState.requestFocus();
                else timeline.requestFocus();
            }
            return true;
        }
        if (timeline.hasFocus() && handleHorizontalDpad(event)) return true;
        if (controls.hasFocus()) return super.dispatchKeyEvent(event);
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (player != null && player.getPlaybackState() != Player.STATE_IDLE) togglePlayback();
                else openOfficial();
            }
            return true;
        }
        if (handleHorizontalDpad(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (player != null && player.getPlaybackState() != Player.STATE_IDLE) {
                togglePlayback();
            } else openOfficial();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (captionPanel != null) {
            closeCaptionPanel();
            return;
        }
        if (playbackMenuVisible) {
            setPlaybackMenuVisible(false);
            return;
        }
        leavingPlayer = true;
        saveProgress();
        StateStore.markReturnScreen(this);
        super.onBackPressed();
    }

    private void saveProgress() {
        saveMirrorState();
        if (player != null && !audioMovedToBackground) {
            long position = player.getCurrentPosition();
            StateStore.savePlayerPosition(this, videoSource, videoIdentityUrl, position);
            saveWatchProgress(position, player.getDuration());
        }
    }

    private void saveMirrorState() {
        if (!audioOnly) {
            WatchProgressStore.setMirrored(this, videoSource, videoIdentityUrl, mirrored);
        }
    }

    private void saveWatchProgress(long position, long duration) {
        long actualDuration = duration > 0 && duration != androidx.media3.common.C.TIME_UNSET
                ? duration : knownDuration;
        if (actualDuration > 0) knownDuration = actualDuration;
        WatchProgressStore.save(this,
                videoSource,
                videoIdentityUrl,
                Math.max(0L, position),
                Math.max(0L, actualDuration));
        if (!historyRecordedThisSession && WatchProgressStore.isViewed(position, actualDuration)) {
            WatchHistoryStore.record(this, historyItem.withDuration(actualDuration),
                    trafficMode, targetHeight, audioOnly);
            historyRecordedThisSession = true;
        }
    }

    private void openOfficial() {
        String page = getIntent().getStringExtra("page_url");
        if (page == null) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page))); } catch (Exception ignored) { }
    }

    private void openHistory() {
        leavingPlayer = true;
        saveProgress();
        StateStore.markHistory(this);
        finish();
    }

    @Override protected void onStart() {
        super.onStart();
        activityStarted = true;
        updateScreenAwake();
        if (audioMovedToBackground) {
            long position = AudioPlaybackService.takePosition(this, historyItem, resumePosition);
            if (player != null) {
                player.seekTo(position);
                player.play();
            } else {
                resumePosition = position;
            }
            audioMovedToBackground = false;
        }
        if (player == null && streamUrl != null) startPlayerSafely();
        if (player != null && !player.isPlaying()) showControls();
        restartProgressTicker();
    }

    @Override protected void onResume() {
        super.onResume();
        if (videoSurface != null) videoSurface.onResume();
    }

    @Override protected void onPause() {
        if (videoSurface != null) videoSurface.onPause();
        super.onPause();
    }

    @Override protected void onStop() {
        saveProgress();
        activityStarted = false;
        updateScreenAwake();
        ui.removeCallbacks(progressTicker);
        ui.removeCallbacks(hideControls);
        ui.removeCallbacks(beginHeldSeek);
        ui.removeCallbacks(openMenuFromLongCenter);
        heldSeekKey = KeyEvent.KEYCODE_UNKNOWN;
        if (player != null) {
            boolean keepAudio = audioOnly && !DeviceType.isTelevision(this)
                    && player.isPlaying() && streamUrl != null && !leavingPlayer && !isFinishing();
            player.pause();
            if (keepAudio) startBackgroundAudio();
        }
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        windowFocused = focused;
        updateScreenAwake();
    }

    private void startBackgroundAudio() {
        Intent background = new Intent(this, AudioPlaybackService.class)
                .setAction(AudioPlaybackService.ACTION_PLAY);
        if (getIntent().getExtras() != null) background.putExtras(getIntent().getExtras());
        background.putExtra("background_request", AudioPlaybackService.prepareStart(this));
        background.putExtra("stream_url", streamUrl);
        background.putExtra("stream_mime_type", streamMimeType);
        background.putExtra("resume_position", player == null ? 0L : player.getCurrentPosition());
        background.putExtra("duration_ms", knownDuration);
        background.putExtra("playback_speed", playbackSpeed);
        try {
            ContextCompat.startForegroundService(this, background);
            audioMovedToBackground = true;
        } catch (RuntimeException ignored) {
            audioMovedToBackground = false;
        }
    }

    private void requestNotificationPermissionForAudio() {
        if (!audioOnly || DeviceType.isTelevision(this) || Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5803);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!intent.getBooleanExtra("resume_background_audio", false)) return;
        if (!java.util.Objects.equals(intent.getStringExtra("page_url"), videoIdentityUrl)) {
            setIntent(intent);
            recreate();
            return;
        }
        long position = AudioPlaybackService.takePosition(this, historyItem, resumePosition);
        if (player != null) {
            player.seekTo(position);
            player.play();
            showControls();
        }
        audioMovedToBackground = false;
    }

    @Override protected void onDestroy() {
        destroyed = true;
        activityStarted = false;
        resolver.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        if (videoSurface != null) videoSurface.release();
        super.onDestroy();
    }

    private void releasePlayer() {
        if (player == null) return;
        player.clearVideoSurface();
        player.release();
        player = null;
        updateScreenAwake();
    }

    private float tvUiScale() {
        return mobileMode ? 1f : TvScalePreferences.get(this, TvScalePreferences.INTERFACE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density * tvUiScale());
    }
}
