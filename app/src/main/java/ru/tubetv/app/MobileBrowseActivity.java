package ru.tubetv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Touch-first RussTube interface; search, stores and playback are shared with TV. */
public final class MobileBrowseActivity extends Activity {
    private static final int VOICE_SEARCH_REQUEST = 7143;
    private enum Page { HOME, SEARCH, SHORTS, LIVE, SUBSCRIPTIONS, LIBRARY, HISTORY, FAVORITES, CHANNELS, POPULAR, SETTINGS }
    private static final int BACKGROUND = MobileAuroraTheme.BACKGROUND;
    private static final int PANEL = MobileAuroraTheme.SURFACE;
    private static final int SECONDARY = MobileAuroraTheme.MUTED;
    private static final int ACCENT = MobileAuroraTheme.TEAL;
    private static final Page[] TABS = {Page.HOME, Page.SHORTS, Page.LIVE, Page.SUBSCRIPTIONS, Page.LIBRARY};
    private static final String[] TAB_LABELS = {"Главная", "Shorts", "В эфире", "Подписки", "Вы"};
    private static final String[] TAB_ICONS = {"home", "shorts", "live", "subscriptions", "history"};

    private final ThreadPoolExecutor io = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128));
    private final List<Future<?>> jobs = new ArrayList<>();
    private final List<VideoItem> items = new ArrayList<>();
    private final List<VideoItem> baseItems = new ArrayList<>();
    private final List<VideoItem> candidates = new ArrayList<>();
    private final List<VideoItem> subscriptionCandidates = new ArrayList<>();
    private final Map<String, VideoItem> discoveredShorts = new LinkedHashMap<>();
    private final List<VideoItem> history = new ArrayList<>();
    private final List<VideoItem> regularHistory = new ArrayList<>();
    private final List<WatchHistoryStore.Entry> historyEntries = new ArrayList<>();
    private final Set<String> watchedKeys = new HashSet<>();
    private boolean shortsHistory;
    private final List<String> failed = new ArrayList<>();
    private final FeedRepository repository = new FeedRepository();
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private MobileShortsPlayback shortsPlayback;
    private Runnable queuedShortSelection;
    private int scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    private float shortTouchStartX;
    private float shortTouchStartY;
    private long shortTouchStartedAt;
    private int shortTouchPosition;
    private boolean shortTouchMoving;
    private String shortSelectedKey;
    private float pullStartY;
    private boolean pullFromTop;
    private Page page = Page.HOME;
    private VideoItem activeChannel;
    private int generation;
    private int pending;
    private boolean firstResume = true;
    private UpdateCoordinator updates;
    private boolean skipResumeReload;
    private String searchQuery = "";
    private Runnable queuedSearch;
    private boolean syncingSearchText;
    private VideoSort.Mode sortMode = VideoSort.Mode.NEWEST;
    private TextView heading;
    private LinearLayout header;
    private FrameLayout headerContainer;
    private LinearLayout toolbar;
    private HorizontalScrollView landscapeControls;
    private EditText landscapeQuery;
    private TextView landscapeSortButton;
    private TextView status;
    private TextView empty;
    private LinearLayout searchRow;
    private LinearLayout actions;
    private LinearLayout sortRow;
    private TextView sortButton;
    private HorizontalScrollView topics;
    private EditText query;
    private GridView list;
    private FeedAdapter adapter;
    private MobileSettingsPanel settingsPanel;
    private LinearLayout navigation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        updates = new UpdateCoordinator(this);
        shortsPlayback = new MobileShortsPlayback(this, this::onShortUnavailable);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        sortMode = VideoSort.parse(getSharedPreferences("browse", 0)
                .getString("video_sort", VideoSort.Mode.NEWEST.name()));
        setContentView(createContent());
        if (state != null) {
            try { page = Page.valueOf(state.getString("page", "HOME")); }
            catch (IllegalArgumentException ignored) { page = Page.HOME; }
            searchQuery = state.getString("query", "");
            query.setText(searchQuery);
        }
        String channel = getIntent().getStringExtra("channel");
        if (channel != null) {
            try { showChannel(VideoJson.decode(new JSONObject(channel))); return; }
            catch (Exception ignored) { /* Open the regular feed if external data is invalid. */ }
        }
        show(page);
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);

        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xf5071426, 0xd0071426, 0x44071426}));
        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), 0, dp(12), 0);
        FrameLayout brandFrame = new FrameLayout(this);
        int logoResource = getResources().getIdentifier("aurora_logo", "drawable", getPackageName());
        if (logoResource != 0) {
            ImageView brand = new ImageView(this);
            brand.setImageResource(logoResource);
            brand.setScaleType(ImageView.ScaleType.FIT_START);
            brand.setContentDescription("RussTube — Arctic Fox Apps");
            brandFrame.addView(brand, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView brand = label("RussTube", MobileAuroraTheme.BRAND_SP, MobileAuroraTheme.TEXT);
            brand.setTypeface(Typeface.DEFAULT_BOLD);
            brandFrame.addView(brand, new FrameLayout.LayoutParams(-1, -1));
        }
        toolbar.addView(brandFrame, new LinearLayout.LayoutParams(0, dp(64), 1));
        TextView search = icon("search", "Поиск", () -> show(Page.SEARCH));
        toolbar.addView(search, size(46, 46));
        TextView refresh = label("↻", 30, MobileAuroraTheme.CYAN);
        refresh.setGravity(Gravity.CENTER);
        refresh.setContentDescription("Обновить");
        refresh.setOnClickListener(v -> refresh());
        toolbar.addView(refresh, size(42, 46));
        header.addView(toolbar, size(-1, 64));

        landscapeControls = new HorizontalScrollView(this);
        landscapeControls.setHorizontalScrollBarEnabled(false);
        LinearLayout compactRow = new LinearLayout(this);
        compactRow.setGravity(Gravity.CENTER_VERTICAL);
        compactRow.setPadding(dp(10), 0, dp(10), 0);
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final Page target = TABS[i];
            compactAction(compactRow, TAB_LABELS[i], () -> show(target));
        }
        landscapeQuery = new EditText(this);
        landscapeQuery.setSingleLine(true);
        landscapeQuery.setTextSize(14);
        landscapeQuery.setTextColor(Color.WHITE);
        landscapeQuery.setHintTextColor(SECONDARY);
        landscapeQuery.setHint("Поиск видео");
        landscapeQuery.setPadding(dp(10), 0, dp(10), 0);
        landscapeQuery.setBackground(round(PANEL, 10));
        landscapeQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        landscapeQuery.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                query.setText(landscapeQuery.getText());
                submitSearch();
                return true;
            }
            return false;
        });
        compactRow.addView(landscapeQuery, size(230, 42));
        TextView landscapeVoice = icon("mic", "Голосовой поиск", this::startVoiceSearch);
        landscapeVoice.setBackground(round(PANEL, 9));
        LinearLayout.LayoutParams voiceParams = size(44, 42);
        voiceParams.rightMargin = dp(8);
        compactRow.addView(landscapeVoice, voiceParams);
        String[] quick = {"Популярное", "Новости", "Наука", "Музыка", "Игры", "Спорт"};
        for (String value : quick) compactAction(compactRow, value, () -> {
            if ("Популярное".equals(value)) show(Page.POPULAR);
            else { searchQuery = value; query.setText(value); landscapeQuery.setText(value); show(Page.SEARCH); }
        });
        landscapeSortButton = compactAction(compactRow, "", this::chooseSort);
        compactAction(compactRow, "История", () -> show(Page.HISTORY));
        compactAction(compactRow, "Избранное", () -> show(Page.FAVORITES));
        compactAction(compactRow, "Настройки", this::settings);
        landscapeControls.addView(compactRow);
        root.addView(landscapeControls, size(-1, 52));

        searchRow = new LinearLayout(this);
        searchRow.setBaselineAligned(false);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(16), dp(4), dp(16), dp(4));
        query = new EditText(this);
        query.setSingleLine(true);
        query.setTextSize(16);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(SECONDARY);
        query.setHint("Видео или канал на всех платформах");
        query.setPadding(dp(14), 0, dp(10), 0);
        query.setBackground(MobileAuroraTheme.stroke(PANEL, dp(12),
                0xff31506a, dp(1)));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                submitSearch(); return true;
            }
            return false;
        });
        searchRow.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView voice = icon("mic", "Голосовой поиск", this::startVoiceSearch);
        searchRow.addView(voice, size(48, 48));
        root.addView(searchRow, size(-1, 66));
        watchQuery(query, landscapeQuery);
        watchQuery(landscapeQuery, query);

        heading = label("", MobileAuroraTheme.HEADING_SP, MobileAuroraTheme.TEXT);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(dp(16), 0, dp(16), dp(3));
        header.addView(heading, size(-1, 42));
        headerContainer = new FrameLayout(this);
        ImageView aurora = new ImageView(this);
        int auroraResource = getResources().getIdentifier("aurora_header", "drawable", getPackageName());
        if (auroraResource != 0) aurora.setImageResource(auroraResource);
        aurora.setScaleType(ImageView.ScaleType.CENTER_CROP);
        headerContainer.addView(aurora, new FrameLayout.LayoutParams(-1, -1));
        headerContainer.addView(header, new FrameLayout.LayoutParams(-1, -1));
        root.addView(headerContainer, 0, size(-1, 106));

        topics = new HorizontalScrollView(this);
        topics.setHorizontalScrollBarEnabled(false);
        LinearLayout topicRow = new LinearLayout(this);
        topicRow.setPadding(dp(14), dp(1), dp(14), dp(10));
        String[] labels = {"Популярное", "Новости", "Наука", "Музыка", "Игры", "Спорт", "Путешествия"};
        String[] symbols = {"✦", "▣", "⚗", "♫", "✥", "●", "◇"};
        for (int i = 0; i < labels.length; i++) {
            String value = labels[i];
            TextView chip = label(symbols[i] + "  " + value,
                    MobileAuroraTheme.CHIP_SP, MobileAuroraTheme.TEXT);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setBackground(MobileAuroraTheme.stroke(i == 0 ? 0xff082b42 : PANEL,
                    dp(12), i == 0 ? ACCENT : 0xff31506a, dp(1)));
            LinearLayout.LayoutParams params = size(-2, 38);
            params.rightMargin = dp(8);
            topicRow.addView(chip, params);
            chip.setOnClickListener(v -> {
                if ("Популярное".equals(value)) show(Page.POPULAR);
                else { searchQuery = value; query.setText(value); show(Page.SEARCH); }
            });
        }
        topics.addView(topicRow);
        root.addView(topics, size(-1, 50));

        actions = new LinearLayout(this);
        actions.setPadding(dp(16), 0, dp(16), dp(4));
        root.addView(actions, size(-1, 49));
        sortRow = new LinearLayout(this);
        sortRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        sortRow.setPadding(dp(16), 0, dp(16), 0);
        sortButton = label("", 13, MobileAuroraTheme.TEXT);
        sortButton.setGravity(Gravity.CENTER);
        sortButton.setPadding(dp(14), 0, dp(14), 0);
        sortButton.setBackground(round(0xff0c2a43, 12));
        sortButton.setOnClickListener(v -> chooseSort());
        sortRow.addView(sortButton, size(-2, 38));
        root.addView(sortRow, size(-1, 48));
        status = label("", 12, SECONDARY);
        status.setPadding(dp(16), 0, dp(16), 0);
        root.addView(status, size(-1, 28));

        FrameLayout feed = new FrameLayout(this);
        list = new GridView(this);
        list.setNumColumns(1);
        list.setHorizontalSpacing(dp(14));
        list.setVerticalSpacing(dp(16));
        list.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(dp(14), 0, dp(14), dp(18));
        list.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (page == Page.SHORTS && bottom - top != oldBottom - oldTop)
                list.post(this::updateShortsPadding);
        });
        adapter = new FeedAdapter();
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                scrollState = state;
                if (page != Page.SHORTS) return;
                if (state == SCROLL_STATE_IDLE) queueShortSelection();
                else { cancelShortSelection(); shortsPlayback.pauseForScroll(); }
            }
            @Override public void onScroll(AbsListView view, int first, int count, int total) {
                if (page == Page.SHORTS || count <= 0) return;
                int width = list.getColumnWidth();
                if (width <= 0) width = Math.max(dp(180), getResources().getDisplayMetrics().widthPixels - dp(28));
                for (int i = first + count; i < Math.min(items.size(), first + count + 4); i++)
                    ((TubeApplication) getApplication()).imageLoader().prefetch(
                            items.get(i).thumbnail, width, width * 9 / 16);
            }
        });
        list.setOnTouchListener((view, event) -> {
            if (page == Page.SHORTS) return handleShortTouch(event);
            if (page != Page.HOME || activeChannel != null) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pullStartY = event.getY();
                pullFromTop = list.getFirstVisiblePosition() == 0
                        && (list.getChildCount() == 0 || list.getChildAt(0).getTop()
                        >= list.getPaddingTop());
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (pullFromTop && event.getY() - pullStartY > dp(110)) refresh();
                pullFromTop = false;
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pullFromTop = false;
            }
            return false;
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= items.size()) return;
            VideoItem item = items.get(position);
            if (page == Page.CHANNELS && activeChannel == null) showChannel(item);
            else if (page == Page.SHORTS) {
                shortSelectedKey = item.stableKey();
                Card card = (Card) view.getTag();
                if (shortsPlayback.isSelected(card.artwork)) shortsPlayback.toggle();
                else {
                    shortsPlayback.select(item, card.artwork, card.image, card.shortCue);
                    if (!MobileBrowsePreferences.shortsAutoplay(this)) shortsPlayback.toggle();
                }
            }
            else {
                skipResumeReload = true;
                VideoActions.play(this, item, false);
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= items.size()) return false;
            VideoItem item = items.get(position);
            VideoActions.show(this, item, () -> showChannel(item));
            return true;
        });
        feed.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = label("", 16, SECONDARY);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(28), dp(18), dp(28), dp(18));
        feed.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        settingsPanel = new MobileSettingsPanel(this,
                () -> startActivity(new Intent(this, NetworkSettingsActivity.class)), () -> { });
        settingsPanel.setVisibility(View.GONE);
        feed.addView(settingsPanel, new FrameLayout.LayoutParams(-1, -1));
        root.addView(feed, new LinearLayout.LayoutParams(-1, 0, 1));

        navigation = new LinearLayout(this);
        navigation.setBackground(MobileAuroraTheme.stroke(0xff071a2d,
                dp(20), 0xff185671, dp(1)));
        navigation.setPadding(dp(4), dp(3), dp(4), dp(3));
        LinearLayout.LayoutParams navParams = size(-1, 66);
        navParams.leftMargin = dp(2);
        navParams.rightMargin = dp(2);
        root.addView(navigation, navParams);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            root.setPadding(0, safe.top, 0, safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private void show(Page target) {
        cancelShortSelection();
        shortSelectedKey = null;
        if (target != Page.SHORTS) shortsPlayback.stopPreloading();
        shortsPlayback.clear();
        cancelQueuedSearch();
        cancel();
        page = target;
        activeChannel = null;
        items.clear(); baseItems.clear(); candidates.clear(); subscriptionCandidates.clear();
        history.clear(); regularHistory.clear();
        historyEntries.clear(); watchedKeys.clear();
        historyEntries.addAll(WatchHistoryStore.load(this));
        for (WatchHistoryStore.Entry entry : historyEntries) {
            history.add(entry.item);
            if (!entry.shorts) regularHistory.add(entry.item);
            watchedKeys.add(entry.item.stableKey());
        }
        actions.removeAllViews();
        syncingSearchText = true;
        landscapeQuery.setText(query.getText());
        syncingSearchText = false;
        searchRow.setVisibility(target == Page.SEARCH ? View.VISIBLE : View.GONE);
        topics.setVisibility(target == Page.HOME || target == Page.SEARCH || target == Page.POPULAR
                ? View.VISIBLE : View.GONE);
        switch (target) {
            case HOME:
                heading.setText("Главная");
                if (MobileBrowsePreferences.recommendHistory(this) && !regularHistory.isEmpty()) {
                    List<String> queries = RecommendationEngine.queries(regularHistory);
                    for (String value : queries) searchAll(value, false);
                }
                if (MobileBrowsePreferences.recommendSubscriptions(this)) {
                    for (VideoItem channel : SubscriptionStore.load(this))
                        enqueueSubscription(channel);
                }
                searchAll("популярное", false);
                break;
            case SEARCH:
                heading.setText("Поиск");
                if (searchQuery.trim().length() >= 2) searchAll(searchQuery.trim(), false);
                break;
            case POPULAR:
                heading.setText("Популярное");
                searchAll("популярное", false);
                break;
            case SHORTS:
                heading.setText("Shorts");
                for (VideoItem item : discoveredShorts.values())
                    if (MobileBrowsePreferences.sourceEnabled(this, item.source)
                            && !watchedKeys.contains(item.stableKey())
                            && !UnavailableVideoStore.contains(this, item)) candidates.add(item);
                baseItems.addAll(candidates);
                searchShorts();
                break;
            case LIVE:
                heading.setText("В эфире");
                enqueue(PeerTubeClient.SOURCE, () -> repository.live(PeerTubeClient.SOURCE, ""));
                searchAll("прямой эфир", true);
                searchAll("24/7", true);
                break;
            case SUBSCRIPTIONS:
                heading.setText("Подписки");
                action("Каналы", () -> show(Page.CHANNELS));
                List<VideoItem> subscriptions = SubscriptionStore.load(this);
                for (VideoItem channel : subscriptions) enqueue(channel.source, () -> repository.channel(channel));
                break;
            case CHANNELS:
                heading.setText("Ваши каналы");
                baseItems.addAll(SubscriptionStore.load(this));
                break;
            case LIBRARY:
                heading.setText("Вы");
                action("История", () -> show(Page.HISTORY));
                action("Избранное", () -> show(Page.FAVORITES));
                action("Настройки", this::settings);
                baseItems.addAll(history);
                break;
            case HISTORY:
                heading.setText("История");
                action(shortsHistory ? "Видео" : "✓ Видео", () -> showHistory(false));
                action(shortsHistory ? "✓ Просмотренные Shorts" : "Просмотренные Shorts",
                        () -> showHistory(true));
                for (WatchHistoryStore.Entry entry : historyEntries)
                    if (entry.shorts == shortsHistory) baseItems.add(entry.item);
                break;
            case FAVORITES:
                heading.setText("Избранное");
                baseItems.addAll(FavoriteStore.load(this));
                break;
            case SETTINGS:
                heading.setText("Настройки");
                break;
        }
        settingsPanel.setVisibility(target == Page.SETTINGS ? View.VISIBLE : View.GONE);
        list.setVisibility(target == Page.SETTINGS ? View.GONE : View.VISIBLE);
        actions.setVisibility(actions.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        sortRow.setVisibility(target == Page.CHANNELS ? View.GONE : View.VISIBLE);
        updateSortLabel();
        applySort();
        updateNavigation();
        render();
        if (!items.isEmpty()) list.setSelection(0);
        if (target == Page.SEARCH && searchQuery.isEmpty()
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            query.requestFocus();
            query.postDelayed(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(query, InputMethodManager.SHOW_IMPLICIT), 120);
        }
        applyLayout();
    }

    private void showChannel(VideoItem channel) {
        cancelShortSelection();
        shortsPlayback.clear();
        cancel();
        activeChannel = channel;
        items.clear(); baseItems.clear(); candidates.clear();
        searchRow.setVisibility(View.GONE);
        topics.setVisibility(View.GONE);
        heading.setText(VideoPresentation.channel(channel));
        actions.removeAllViews();
        actions.setVisibility(View.VISIBLE);
        sortRow.setVisibility(View.VISIBLE);
        action(SubscriptionStore.contains(this, channel) ? "Отписаться" : "Подписаться", () -> {
            try {
                SubscriptionStore.set(this, channel, !SubscriptionStore.contains(this, channel));
                showChannel(channel);
            } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        enqueue(channel.source, () -> repository.channel(channel));
        applySort();
        render();
        applyLayout();
    }

    private void submitSearch() {
        searchQuery = query.getText().toString().trim();
        if (searchQuery.length() < 2) { query.setError("Введите хотя бы два символа"); return; }
        cancelQueuedSearch();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(query.getWindowToken(), 0);
        query.clearFocus();
        landscapeQuery.clearFocus();
        show(Page.SEARCH);
    }

    private void watchQuery(EditText source, EditText other) {
        source.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                if (syncingSearchText) return;
                String typed = value.toString();
                if (!typed.contentEquals(other.getText())) {
                    syncingSearchText = true;
                    other.setText(typed);
                    syncingSearchText = false;
                }
                searchQuery = typed.trim();
                cancelQueuedSearch();
                if (page != Page.SEARCH) {
                    if (source != landscapeQuery || searchQuery.length() < 2) return;
                    queuedSearch = () -> {
                        queuedSearch = null;
                        show(Page.SEARCH);
                    };
                    searchHandler.postDelayed(queuedSearch, 450);
                    return;
                }
                cancel();
                items.clear(); baseItems.clear(); candidates.clear();
                applySort(); render();
                queuedSearch = () -> {
                    queuedSearch = null;
                    if (searchQuery.length() >= 2) searchAll(searchQuery, false);
                };
                searchHandler.postDelayed(queuedSearch, 450);
            }
        });
    }

    private void cancelQueuedSearch() {
        if (queuedSearch == null) return;
        searchHandler.removeCallbacks(queuedSearch);
        queuedSearch = null;
    }

    private void startVoiceSearch() {
        Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Что найти?");
        try { startActivityForResult(speech, VOICE_SEARCH_REQUEST); }
        catch (RuntimeException error) {
            Toast.makeText(this, "Голосовой ввод недоступен на устройстве", Toast.LENGTH_LONG).show();
        }
    }

    private void refresh() {
        if (activeChannel != null) showChannel(activeChannel);
        else show(page);
    }

    private void showHistory(boolean shorts) {
        shortsHistory = shorts;
        show(Page.HISTORY);
    }

    private void cancelShortSelection() {
        if (queuedShortSelection != null) searchHandler.removeCallbacks(queuedShortSelection);
        queuedShortSelection = null;
    }

    private boolean handleShortTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                shortTouchStartX = event.getX();
                shortTouchStartY = event.getY();
                shortTouchStartedAt = android.os.SystemClock.uptimeMillis();
                shortTouchPosition = selectedShortPosition();
                shortTouchMoving = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!shortTouchMoving && Math.abs(event.getY() - shortTouchStartY) > dp(16)) {
                    shortTouchMoving = true;
                    shortsPlayback.pauseForScroll();
                }
                return true;
            case MotionEvent.ACTION_UP:
                float deltaY = event.getY() - shortTouchStartY;
                if (Math.abs(deltaY) >= dp(48)
                        && Math.abs(deltaY) > Math.abs(event.getX() - shortTouchStartX)) {
                    snapToShort(shortTouchPosition + (deltaY < 0 ? 1 : -1));
                } else {
                    int position = list.pointToPosition((int) event.getX(), (int) event.getY());
                    if (position < 0 || position >= items.size()) position = shortTouchPosition;
                    if (shortTouchMoving) selectCenteredShort();
                    if (position >= 0 && position < items.size()) {
                        VideoItem item = items.get(position);
                        if (android.os.SystemClock.uptimeMillis() - shortTouchStartedAt >= 550
                                && !shortTouchMoving) {
                            VideoActions.show(this, item, () -> showChannel(item));
                        } else if (!shortTouchMoving) {
                            View child = list.getChildAt(position - list.getFirstVisiblePosition());
                            if (child != null) list.performItemClick(child, position, adapter.getItemId(position));
                        }
                    }
                }
                shortTouchMoving = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                shortTouchMoving = false;
                return true;
            default:
                return true;
        }
    }

    private int centeredShortPosition() {
        if (items.isEmpty() || list.getChildCount() == 0) return -1;
        int first = list.getFirstVisiblePosition();
        if (first < 0 || first >= items.size()) return -1;
        int center = list.getHeight() / 2;
        int closest = -1;
        int distance = Integer.MAX_VALUE;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            int delta = Math.abs((child.getTop() + child.getBottom()) / 2 - center);
            if (delta < distance) {
                closest = first + i;
                distance = delta;
            }
        }
        return closest >= items.size() ? -1 : closest;
    }

    private int selectedShortPosition() {
        if (shortSelectedKey != null) for (int i = 0; i < items.size(); i++)
            if (shortSelectedKey.equals(items.get(i).stableKey())) return i;
        return centeredShortPosition();
    }

    private void snapToShort(int requested) {
        if (items.isEmpty()) return;
        int target = Math.max(0, Math.min(items.size() - 1, requested));
        shortSelectedKey = items.get(target).stableKey();
        list.setSelection(target);
        list.post(this::queueShortSelection);
    }

    private void queueShortSelection() {
        cancelShortSelection();
        if (page != Page.SHORTS || items.isEmpty() || scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE)
            return;
        queuedShortSelection = () -> {
            queuedShortSelection = null;
            selectCenteredShort();
        };
        searchHandler.postDelayed(queuedShortSelection, 160);
    }

    private void selectCenteredShort() {
        if (page != Page.SHORTS || list.getChildCount() == 0 || scrollState
                != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) return;
        int first = list.getFirstVisiblePosition();
        if (first < 0 || first >= items.size()) return;
        int center = list.getHeight() / 2;
        View nearest = null;
        int distance = Integer.MAX_VALUE;
        int position = -1;
        if (shortSelectedKey != null) for (int i = 0; i < items.size(); i++) {
            if (shortSelectedKey.equals(items.get(i).stableKey())) {
                position = i;
                nearest = list.getChildAt(i - first);
                if (nearest == null) {
                    list.setSelection(i);
                    list.postDelayed(this::queueShortSelection, 40);
                    return;
                }
                break;
            }
        }
        if (nearest == null) for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            int delta = Math.abs((child.getTop() + child.getBottom()) / 2 - center);
            if (delta < distance) {
                nearest = child;
                distance = delta;
                position = first + i;
            }
        }
        if (nearest == null || position < 0 || position >= items.size()) return;
        if (shortSelectedKey != null
                && Math.abs((nearest.getTop() + nearest.getBottom()) / 2 - center) > dp(24)) {
            list.setSelection(position);
            list.postDelayed(this::queueShortSelection, 40);
            return;
        }
        shortSelectedKey = items.get(position).stableKey();
        Card card = (Card) nearest.getTag();
        shortsPlayback.select(items.get(position), card.artwork, card.image, card.shortCue);
        shortsPlayback.preload(items, position);
    }

    private void settings() {
        show(Page.SETTINGS);
    }

    private void searchAll(String value, boolean live) {
        String[] sources = live ? new String[]{"RUTUBE", "VK VIDEO", PeerTubeClient.SOURCE}
                : FeedRepository.SOURCES;
        for (String source : sources) enqueue(source,
                () -> live ? repository.live(source, value) : repository.search(source, value));
    }

    private void searchShorts() {
        for (String source : FeedRepository.SOURCES) {
            enqueue(source, () -> repository.shorts(source, "shorts"));
            enqueue(source, () -> repository.shorts(source, "шортс"));
            enqueue(source, () -> repository.shorts(source, "популярное"));
            if ("VK VIDEO".equals(source))
                enqueue(source, () -> repository.shorts(source, "клипы"));
        }
    }

    private void enqueue(String source, Load operation) {
        enqueue(source, operation, false);
    }

    private void enqueueSubscription(VideoItem channel) {
        enqueue(channel.source, () -> repository.channel(channel), true);
    }

    private void enqueue(String source, Load operation, boolean subscription) {
        if (!MobileBrowsePreferences.sourceEnabled(this, source)) return;
        int current = generation;
        pending++;
        jobs.add(io.submit(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            List<VideoItem> loaded = new ArrayList<>();
            boolean failure = false;
            try { loaded = operation.run(); }
            catch (Exception error) { failure = true; }
            if (BuildConfig.DEBUG) {
                int thumbnailCount = 0;
                for (VideoItem item : loaded)
                    if (item.thumbnail != null && !item.thumbnail.isEmpty()) thumbnailCount++;
                android.util.Log.i("RussTubeImagePerf", "catalog=" + source
                        + " urlReadyMs=" + (android.os.SystemClock.elapsedRealtime() - started)
                        + " thumbnails=" + thumbnailCount);
            }
            final List<VideoItem> result = loaded;
            final boolean failedRequest = failure;
            runOnUiThread(() -> {
                if (isFinishing() || current != generation) return;
                int previousShort = page == Page.SHORTS ? centeredShortPosition() : -1;
                String previousShortKey = page == Page.SHORTS && shortSelectedKey != null
                        ? shortSelectedKey : previousShort >= 0 && previousShort < items.size()
                        ? items.get(previousShort).stableKey() : null;
                pending--;
                if (failedRequest && !failed.contains(source)) failed.add(source);
                if (page == Page.HOME || page == Page.POPULAR || page == Page.SEARCH) {
                    for (VideoItem item : result) if (ShortsCatalog.accepts(item))
                        discoveredShorts.put(item.stableKey(), item);
                    while (discoveredShorts.size() > 200)
                        discoveredShorts.remove(discoveredShorts.keySet().iterator().next());
                }
                candidates.addAll(result);
                if (subscription) subscriptionCandidates.addAll(result);
                baseItems.clear();
                if (page == Page.HOME && activeChannel == null) {
                    baseItems.addAll(HomeFeed.combine(subscriptionCandidates, candidates,
                            regularHistory, MobileBrowsePreferences.recommendHistory(this)));
                } else {
                    Map<String, VideoItem> unique = new LinkedHashMap<>();
                    for (VideoItem item : candidates) unique.put(item.stableKey(), item);
                    baseItems.addAll(unique.values());
                }
                if (page == Page.HOME || page == Page.SHORTS) {
                    Iterator<VideoItem> iterator = baseItems.iterator();
                    while (iterator.hasNext())
                        if (watchedKeys.contains(iterator.next().stableKey())) iterator.remove();
                }
                if (page == Page.HOME) {
                    Iterator<VideoItem> iterator = baseItems.iterator();
                    while (iterator.hasNext())
                        if (ShortsCatalog.accepts(iterator.next())) iterator.remove();
                }
                if (page == Page.POPULAR) Collections.sort(baseItems,
                        (a, b) -> Long.compare(b.views, a.views));
                applySort();
                render();
                if (previousShortKey != null) for (int i = 0; i < items.size(); i++) {
                    if (previousShortKey.equals(items.get(i).stableKey())) {
                        list.setSelection(i);
                        break;
                    }
                }
            });
        }));
    }

    private void chooseSort() {
        VideoSort.Mode[] modes = VideoSort.Mode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label;
        new AlertDialog.Builder(this).setTitle("Порядок видео")
                .setSingleChoiceItems(labels, sortMode.ordinal(), (dialog, choice) -> {
                    sortMode = modes[choice];
                    getSharedPreferences("browse", 0).edit()
                            .putString("video_sort", sortMode.name()).apply();
                    updateSortLabel();
                    applySort();
                    render();
                    if (!items.isEmpty()) list.setSelection(0);
                    dialog.dismiss();
                }).setNegativeButton("Отмена", null).show();
    }

    private void updateSortLabel() {
        sortButton.setText("☷  " + sortMode.label + "  ▾");
        if (landscapeSortButton != null) landscapeSortButton.setText(sortMode.label + "  ▾");
    }

    private TextView compactAction(LinearLayout row, String title, Runnable action) {
        TextView button = label(title, 14, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(round(PANEL, 9));
        LinearLayout.LayoutParams params = size(-2, 40);
        params.rightMargin = dp(8);
        row.addView(button, params);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void applyLayout() {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        headerContainer.setVisibility(landscape ? View.GONE : View.VISIBLE);
        landscapeControls.setVisibility(landscape ? View.VISIBLE : View.GONE);
        searchRow.setVisibility(!landscape && page == Page.SEARCH ? View.VISIBLE : View.GONE);
        topics.setVisibility(!landscape && activeChannel == null
                && (page == Page.HOME || page == Page.SEARCH || page == Page.POPULAR)
                ? View.VISIBLE : View.GONE);
        actions.setVisibility(!landscape && actions.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        sortRow.setVisibility(!landscape && page != Page.SETTINGS
                && (page != Page.CHANNELS || activeChannel != null)
                ? View.VISIBLE : View.GONE);
        status.setVisibility(landscape || page == Page.SETTINGS ? View.GONE : View.VISIBLE);
        navigation.setVisibility(landscape ? View.GONE : View.VISIBLE);
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        int columns = page == Page.SHORTS ? 1
                : widthDp >= 1000 ? 3 : widthDp >= 600 || landscape ? 2 : 1;
        list.setNumColumns(columns);
        updateShortsPadding();
        list.setHorizontalSpacing(dp(14));
        adapter.notifyDataSetChanged();
    }

    private void updateShortsPadding() {
        int padding = 0;
        if (page == Page.SHORTS && list.getHeight() > 0) {
            int width = Math.max(dp(180), list.getWidth() - dp(28));
            float ratio = MobileBrowsePreferences.shortsFrameRatio(this) == 1 ? 5f / 4f : 16f / 9f;
            int cardHeight = MobileBrowsePreferences.shortsFrameRatio(this) == 2
                    ? list.getHeight() - dp(30)
                    : Math.min(Math.round(width * ratio), list.getHeight() - dp(30));
            padding = Math.max(dp(15), (list.getHeight() - cardHeight) / 2);
        }
        int bottom = page == Page.SHORTS ? padding : dp(18);
        if (list.getPaddingTop() != padding || list.getPaddingBottom() != bottom)
            list.setPadding(dp(14), padding, dp(14), bottom);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        int first = list.getFirstVisiblePosition();
        super.onConfigurationChanged(configuration);
        skipResumeReload = true;
        if (!landscapeQuery.hasFocus()) {
            syncingSearchText = true;
            landscapeQuery.setText(query.getText());
            syncingSearchText = false;
        }
        applyLayout();
        if (!items.isEmpty()) list.setSelection(first);
        queueShortSelection();
    }

    private void applySort() {
        items.clear();
        Set<String> unavailable = UnavailableVideoStore.keys(this);
        for (VideoItem item : baseItems)
            if ((page != Page.HOME && page != Page.SHORTS)
                    || !unavailable.contains(item.stableKey())) items.add(item);
        if (page != Page.CHANNELS || activeChannel != null) VideoSort.apply(items, sortMode);
    }

    private void onShortUnavailable(VideoItem unavailable) {
        if (page != Page.SHORTS || unavailable == null) return;
        int position = Math.max(0, centeredShortPosition());
        String key = unavailable.stableKey();
        discoveredShorts.remove(key);
        Iterator<VideoItem> candidateIterator = candidates.iterator();
        while (candidateIterator.hasNext())
            if (key.equals(candidateIterator.next().stableKey())) candidateIterator.remove();
        Iterator<VideoItem> baseIterator = baseItems.iterator();
        while (baseIterator.hasNext())
            if (key.equals(baseIterator.next().stableKey())) baseIterator.remove();
        shortsPlayback.clear();
        applySort();
        render();
        if (!items.isEmpty()) {
            list.setSelection(Math.min(position, items.size() - 1));
            list.post(this::queueShortSelection);
        }
    }

    private void render() {
        adapter.notifyDataSetChanged();
        if (page == Page.SHORTS) list.post(this::queueShortSelection);
        status.setText((pending > 0 ? "Загрузка…  ·  " : "") + "Видео: " + items.size()
                + (failed.isEmpty() ? "" : "  ·  Недоступны: " + TextUtils.join(", ", failed)));
        empty.setText(pending > 0 ? "Ищем видео…"
                : activeChannel != null
                        && !MobileBrowsePreferences.sourceEnabled(this, activeChannel.source)
                        ? "Эта площадка выключена в настройках"
                : page == Page.SEARCH && searchQuery.length() < 2 ? "Введите хотя бы два символа"
                : page == Page.SUBSCRIPTIONS ? "Подпишитесь на канал через меню видео"
                : page == Page.LIVE ? "Сейчас нет подтверждённых эфиров"
                : page == Page.SHORTS ? "Вертикальные видео пока не найдены"
                : page == Page.FAVORITES ? "Сохранённые видео появятся здесь"
                : page == Page.HISTORY && shortsHistory ? "Просмотренных Shorts пока нет"
                : page == Page.LIBRARY || page == Page.HISTORY ? "История просмотра пока пуста"
                : page == Page.CHANNELS && activeChannel == null ? "Подписок пока нет"
                : "Видео пока не найдены");
    }

    private void updateNavigation() {
        navigation.removeAllViews();
        Page selected = page == Page.SEARCH || page == Page.POPULAR ? Page.HOME
                : page == Page.HISTORY || page == Page.FAVORITES || page == Page.CHANNELS
                        || page == Page.SETTINGS ? Page.LIBRARY : page;
        for (int i = 0; i < TABS.length; i++) {
            Page target = TABS[i];
            boolean active = selected == target;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            ImageView image = new ImageView(this);
            image.setImageDrawable(TvIcons.icon(TAB_ICONS[i], active ? ACCENT : 0xffa4b9db, dp(25)));
            tab.addView(image, size(27, 27));
            TextView name = label(TAB_LABELS[i], MobileAuroraTheme.NAV_LABEL_SP,
                    active ? ACCENT : 0xffa4b9db);
            name.setGravity(Gravity.CENTER);
            tab.addView(name, size(-1, 22));
            navigation.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
            tab.setOnClickListener(v -> {
                if (page != target || activeChannel != null) show(target);
            });
        }
    }

    private void action(String title, Runnable callback) {
        TextView button = label(title, 13, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(round(PANEL, 9));
        LinearLayout.LayoutParams params = size(-2, 38);
        params.rightMargin = dp(8);
        actions.addView(button, params);
        button.setOnClickListener(v -> callback.run());
    }

    private TextView icon(String type, String description, Runnable callback) {
        TextView button = label("", 20, MobileAuroraTheme.TEXT);
        button.setGravity(Gravity.CENTER);
        button.setCompoundDrawables(null, TvIcons.icon(type, Color.WHITE, dp(23)), null, null);
        button.setContentDescription(description);
        button.setOnClickListener(v -> callback.run());
        return button;
    }

    private TextView label(String text, int sp, int color) {
        TextView result = new TextView(this);
        result.setText(text); result.setTextSize(sp); result.setTextColor(color);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private GradientDrawable round(int color, int radius) {
        return MobileAuroraTheme.round(color, dp(radius));
    }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width),
                height < 0 ? height : dp(height));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void cancel() {
        generation++;
        for (Future<?> job : jobs) job.cancel(true);
        jobs.clear(); io.purge(); pending = 0; failed.clear();
    }

    @Override protected void onResume() {
        super.onResume();
        updates.onResume();
        shortsPlayback.onResume();
        if (page == Page.SHORTS) {
            applySort();
            render();
            queueShortSelection();
        }
        if (firstResume) { firstResume = false; return; }
        if (skipResumeReload) { skipResumeReload = false; render(); return; }
        if (activeChannel != null) showChannel(activeChannel);
        else if (page == Page.HOME || page == Page.HISTORY || page == Page.LIBRARY
                || page == Page.FAVORITES || page == Page.SUBSCRIPTIONS || page == Page.CHANNELS) show(page);
        else render();
    }

    @Override protected void onPause() {
        shortsPlayback.onPause();
        updates.onPause();
        super.onPause();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_SEARCH_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS);
                if (results != null && !results.isEmpty()) {
                    query.setText(results.get(0));
                    submitSearch();
                }
            }
            return;
        }
        updates.onActivityResult(requestCode);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("page", page.name()); state.putString("query", searchQuery);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (activeChannel != null) { show(page); return; }
        if (page == Page.HISTORY || page == Page.FAVORITES || page == Page.CHANNELS
                || page == Page.SETTINGS) {
            show(Page.LIBRARY); return;
        }
        if (page != Page.HOME) { show(Page.HOME); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        cancelShortSelection(); shortsPlayback.release();
        cancelQueuedSearch(); updates.onDestroy(); cancel(); io.shutdownNow(); super.onDestroy();
    }

    private interface Load { List<VideoItem> run() throws Exception; }

    private final class FeedAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public VideoItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) {
            return position >= 0 && position < items.size()
                    ? items.get(position).stableKey().hashCode() : -1;
        }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Card card;
            if (recycled == null) {
                LinearLayout layout = new LinearLayout(MobileBrowseActivity.this);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setBackground(round(MobileAuroraTheme.SURFACE_DARK,
                        MobileAuroraTheme.CARD_RADIUS_DP));
                FrameLayout artwork = new FrameLayout(MobileBrowseActivity.this) {
                    @Override protected void onMeasure(int widthSpec, int heightSpec) {
                        int width = View.MeasureSpec.getSize(widthSpec);
                        int artworkHeight = Math.round(width * (page == Page.SHORTS
                                ? MobileBrowsePreferences.shortsFrameRatio(MobileBrowseActivity.this) == 1
                                        ? 5f / 4f : 16f / 9f : 9f / 16f));
                        if (page == Page.SHORTS) {
                            int available = Math.max(dp(180), list.getHeight() - dp(30));
                            if (MobileBrowsePreferences.shortsFrameRatio(MobileBrowseActivity.this) == 2)
                                artworkHeight = available;
                            else artworkHeight = Math.min(artworkHeight, available);
                        }
                        super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                                artworkHeight, View.MeasureSpec.EXACTLY));
                    }
                };
                artwork.setBackground(MobileAuroraTheme.stroke(PANEL,
                        dp(MobileAuroraTheme.ARTWORK_RADIUS_DP),
                        0xff315d75, dp(1)));
                artwork.setClipToOutline(true);
                ImageView image = new ImageView(MobileBrowseActivity.this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                artwork.addView(image, new FrameLayout.LayoutParams(-1, -1));
                TextView source = label("", 11, MobileAuroraTheme.TEXT);
                source.setTypeface(Typeface.DEFAULT_BOLD);
                source.setPadding(dp(8), 0, dp(8), 0);
                source.setBackground(round(0xee071426, 7));
                FrameLayout.LayoutParams sourceParams = new FrameLayout.LayoutParams(-2, dp(24), Gravity.TOP | Gravity.START);
                sourceParams.setMargins(dp(10), dp(10), 0, 0); artwork.addView(source, sourceParams);
                TextView duration = label("", 11, MobileAuroraTheme.TEXT);
                duration.setTypeface(Typeface.DEFAULT_BOLD);
                duration.setPadding(dp(6), 0, dp(6), 0); duration.setBackground(round(0xee071426, 6));
                FrameLayout.LayoutParams durationParams = new FrameLayout.LayoutParams(-2, dp(22), Gravity.BOTTOM | Gravity.END);
                durationParams.setMargins(0, 0, dp(10), dp(10)); artwork.addView(duration, durationParams);
                ProgressBar progress = new ProgressBar(MobileBrowseActivity.this, null,
                        android.R.attr.progressBarStyleHorizontal);
                progress.setMax(1000);
                progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
                progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
                artwork.addView(progress, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));
                TextView shortInfo = label("", 15, Color.WHITE);
                shortInfo.setMaxLines(4);
                shortInfo.setEllipsize(TextUtils.TruncateAt.END);
                shortInfo.setPadding(dp(14), dp(28), dp(14), dp(16));
                shortInfo.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{Color.TRANSPARENT, 0xb0071426}));
                artwork.addView(shortInfo, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                TextView shortCue = label("▶", 36, Color.WHITE);
                shortCue.setGravity(Gravity.CENTER);
                shortCue.setPadding(dp(14), dp(8), dp(14), dp(8));
                shortCue.setBackground(round(0x99071426, 14));
                shortCue.setVisibility(View.GONE);
                artwork.addView(shortCue, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
                layout.addView(artwork, size(-1, -2));

                LinearLayout info = new LinearLayout(MobileBrowseActivity.this);
                info.setPadding(dp(4), dp(10), dp(2), dp(12));
                TextView avatar = label("", 17, Color.WHITE);
                avatar.setTypeface(Typeface.DEFAULT_BOLD); avatar.setGravity(Gravity.CENTER);
                avatar.setBackground(round(0xff315679, 22));
                info.addView(avatar, size(40, 40));
                LinearLayout text = new LinearLayout(MobileBrowseActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);
                text.setPadding(dp(10), 0, dp(4), 0);
                TextView title = label("", MobileAuroraTheme.CARD_TITLE_SP,
                        MobileAuroraTheme.TEXT);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
                text.addView(title, size(-1, -2));
                TextView channel = label("", MobileAuroraTheme.CARD_META_SP, SECONDARY);
                channel.setSingleLine(true); channel.setEllipsize(TextUtils.TruncateAt.END);
                text.addView(channel, size(-1, -2));
                TextView meta = label("", MobileAuroraTheme.CARD_META_SP, SECONDARY);
                meta.setSingleLine(true); meta.setEllipsize(TextUtils.TruncateAt.END);
                text.addView(meta, size(-1, -2));
                info.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
                TextView more = label("⋮", 24, MobileAuroraTheme.TEXT);
                more.setGravity(Gravity.CENTER);
                more.setContentDescription("Действия с видео");
                info.addView(more, size(38, 42));
                layout.addView(info, size(-1, -2));
                layout.setTag(card = new Card(artwork, info, image, source, duration,
                        progress, shortInfo, shortCue, avatar, title, channel, meta, more));
                recycled = layout;
            } else card = (Card) recycled.getTag();
            VideoItem item = getItem(position);
            if (shortsPlayback.isSelected(card.artwork) && (page != Page.SHORTS
                    || !item.stableKey().equals(card.artwork.getTag()))) shortsPlayback.clear();
            card.artwork.setTag(item.stableKey());
            card.image.setScaleType(page == Page.SHORTS
                    ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            card.info.setVisibility(page == Page.SHORTS ? View.GONE : View.VISIBLE);
            card.shortInfo.setVisibility(page == Page.SHORTS
                    && MobileBrowsePreferences.shortsShowInfo(MobileBrowseActivity.this)
                    ? View.VISIBLE : View.GONE);
            if (page == Page.SHORTS) {
                String details = VideoPresentation.views(item);
                card.shortInfo.setText(item.source + " · " + VideoPresentation.channel(item)
                        + "\n" + item.title + (details.isEmpty() ? "" : "\n" + details));
            } else card.shortCue.setVisibility(View.GONE);
            FrameLayout.LayoutParams durationParams = (FrameLayout.LayoutParams)
                    card.duration.getLayoutParams();
            durationParams.gravity = (page == Page.SHORTS ? Gravity.TOP : Gravity.BOTTOM)
                    | Gravity.END;
            durationParams.topMargin = page == Page.SHORTS ? dp(10) : 0;
            durationParams.bottomMargin = page == Page.SHORTS ? 0 : dp(10);
            card.duration.setLayoutParams(durationParams);
            card.source.setText(item.isLive ? "● В ЭФИРЕ · " + item.source : item.source);
            card.duration.setText(item.isLive ? "В ЭФИРЕ" : VideoPresentation.duration(item.durationMs));
            card.duration.setVisibility(item.isLive || item.durationMs > 0 ? View.VISIBLE : View.GONE);
            card.title.setText(page == Page.CHANNELS && activeChannel == null
                    ? VideoPresentation.channel(item) : item.title);
            String channel = VideoPresentation.channel(item);
            card.avatar.setText(channel.isEmpty() ? "?"
                    : channel.substring(0, 1).toUpperCase(Locale.getDefault()));
            card.avatar.setBackground(round(item.source.contains("VK") ? 0xff315d98
                    : item.source.contains("RUTUBE") ? 0xff4f3990 : 0xff246b76, 22));
            card.channel.setText(item.source + " · " + channel);
            List<String> details = new ArrayList<>();
            if (!VideoPresentation.views(item).isEmpty()) details.add(VideoPresentation.views(item));
            if (!VideoPresentation.published(item).isEmpty()) details.add(VideoPresentation.published(item));
            card.meta.setText(TextUtils.join(" · ", details));
            WatchProgressStore.Progress watched = WatchProgressStore.get(MobileBrowseActivity.this, item);
            long total = watched.durationMs > 0 ? watched.durationMs : item.durationMs;
            card.progress.setVisibility(watched.positionMs > 0 && total > 0 ? View.VISIBLE : View.GONE);
            if (total > 0) card.progress.setProgress((int) Math.min(1000, watched.positionMs * 1000 / total));
            card.more.setOnClickListener(v -> VideoActions.show(MobileBrowseActivity.this, item,
                    () -> showChannel(item)));
            int thumbnailWidth = list.getColumnWidth();
            if (thumbnailWidth <= 0) thumbnailWidth = Math.max(dp(180),
                    getResources().getDisplayMetrics().widthPixels - dp(28));
            ((TubeApplication) getApplication()).imageLoader().load(item.thumbnail, card.image,
                    thumbnailWidth, thumbnailWidth * 9 / 16);
            return recycled;
        }
    }

    private static final class Card {
        final FrameLayout artwork;
        final LinearLayout info;
        final ImageView image;
        final TextView shortInfo, shortCue;
        final TextView source, duration, avatar, title, channel, meta, more;
        final ProgressBar progress;
        Card(FrameLayout artwork, LinearLayout info, ImageView image, TextView source,
             TextView duration, ProgressBar progress, TextView shortInfo, TextView shortCue,
             TextView avatar, TextView title, TextView channel, TextView meta, TextView more) {
            this.artwork = artwork; this.info = info;
            this.image = image; this.source = source; this.duration = duration;
            this.progress = progress; this.avatar = avatar; this.title = title;
            this.channel = channel; this.meta = meta; this.more = more;
            this.shortInfo = shortInfo; this.shortCue = shortCue;
        }
    }
}
