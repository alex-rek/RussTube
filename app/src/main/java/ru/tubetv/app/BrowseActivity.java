package ru.tubetv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Native, D-pad-first browsing shell with a persistent navigation rail. */
public final class BrowseActivity extends Activity {
    private enum Page { HOME, SEARCH, RECOMMENDATIONS, LIVE, SUBSCRIPTIONS, CHANNELS, HISTORY, POPULAR, FILMS, FAVORITES }
    private enum LiveGroup { ALL, RUTUBE_TV, VK_TV, OK_TV }
    private enum FilmGroup { ALL, RUTUBE, VK, OK }
    private static final Page[] NAV_PAGES = {Page.HOME, Page.POPULAR, Page.RECOMMENDATIONS, Page.FILMS, Page.LIVE,
            Page.SUBSCRIPTIONS, Page.CHANNELS, Page.HISTORY, Page.FAVORITES};
    private static final String[] NAV_NAMES = {"Главная", "Популярное", "Рекомендации", "Фильмы", "В эфире",
            "Подписки", "Каналы", "История", "Избранное"};
    private static final String[] NAV_ICONS = {"home", "popular", "recommend", "films", "live",
            "subscriptions", "channels", "history", "favorite"};
    private static final String[] QUALITY_LABELS = {"Любое", "720p+", "1080p+", "1440p+", "4K"};
    private static final int[] QUALITY_WIDTHS = {0, 1280, 1920, 2560, 3840};
    private Page page = Page.HOME;
    private LiveGroup liveGroup = LiveGroup.ALL;
    private FilmGroup filmGroup = FilmGroup.ALL;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128));
    private final List<Future<?>> jobs = new ArrayList<>();
    private final List<VideoItem> items = new ArrayList<>();
    private final List<VideoItem> baseItems = new ArrayList<>();
    private final List<VideoItem> candidates = new ArrayList<>();
    private final List<VideoItem> subscriptionCandidates = new ArrayList<>();
    private final List<VideoItem> history = new ArrayList<>();
    private final Set<String> qualityRequested = new HashSet<>();
    private final List<Button> navigation = new ArrayList<>();
    private Button settingsNavigation;
    private final FeedRepository repository = new FeedRepository();
    private int generation;
    private int pending;
    private final List<String> failed = new ArrayList<>();
    private boolean channelsMode;
    private boolean firstResume = true;
    private UpdateCoordinator updates;
    private VideoItem activeChannel;
    private VideoSort.Mode sortMode = VideoSort.Mode.NEWEST;
    private Button sortButton;
    private Button qualityButton;
    private int qualityFilter;
    private TextView heading;
    private TextView description;
    private TextView status;
    private TextView empty;
    private LinearLayout searchRow;
    private LinearLayout actions;
    private EditText query;
    private Button findButton;
    private GridView grid;
    private Cards adapter;
    private LinearLayout rail;
    private ScrollView menuContainer;
    private LinearLayout content;
    private boolean menuExpanded;
    private int interfaceScaleStep;
    private int gridScaleStep;
    private int appearanceTheme;
    private boolean appearanceSymbols;
    private String focusKey;
    private int focusPosition;
    private boolean focusPending = true;
    private boolean returningFromPlayer;
    private int cardRevision;
    private int lastPrefetchPosition;
    private long lastBackAt;
    private TextView clock;
    private TextView calendar;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private String pendingResultsEmptyText;
    private final Runnable renderResults = () -> {
        long started = android.os.SystemClock.elapsedRealtime();
        rebuildBaseItems();
        applySort();
        display(pendingResultsEmptyText);
        if (BuildConfig.DEBUG) android.util.Log.i("RussTubeTvPerf", "renderResultsMs="
                + (android.os.SystemClock.elapsedRealtime() - started) + " cards=" + items.size());
    };
    private final Runnable autoSearch = () -> {
        if (page == Page.SEARCH) startSearch(query.getText().toString().trim(), false);
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", new Locale("ru")).format(now));
            calendar.setText(new SimpleDateFormat("EEE, d MMM", new Locale("ru")).format(now));
            timer.postDelayed(this, 30000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        updates = new UpdateCoordinator(this);
        sortMode = VideoSort.parse(getSharedPreferences("browse", 0)
                .getString("video_sort", VideoSort.Mode.NEWEST.name()));
        qualityFilter = Math.max(0, Math.min(QUALITY_LABELS.length - 1,
                getSharedPreferences("browse", 0).getInt("tv_quality_filter", 0)));
        interfaceScaleStep = TvScalePreferences.getStep(this, TvScalePreferences.INTERFACE);
        gridScaleStep = TvScalePreferences.getStep(this, TvScalePreferences.GRID);
        appearanceTheme = TvAppearancePreferences.theme(this);
        appearanceSymbols = TvAppearancePreferences.symbols(this);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(createContent());
        if (state != null) {
            try { page = Page.valueOf(state.getString("page", "HOME")); }
            catch (IllegalArgumentException ignored) { page = Page.HOME; }
            query.setText(state.getString("query", ""));
            try { liveGroup = LiveGroup.valueOf(state.getString("live_group", "ALL")); }
            catch (IllegalArgumentException ignored) { liveGroup = LiveGroup.ALL; }
            try { filmGroup = FilmGroup.valueOf(state.getString("film_group", "ALL")); }
            catch (IllegalArgumentException ignored) { filmGroup = FilmGroup.ALL; }
            focusKey = state.getString("focus_key");
            focusPosition = state.getInt("focus_position", 0);
        }
        show(page);
        String channel = getIntent().getStringExtra("channel");
        if (channel != null) try { showChannel(VideoJson.decode(new org.json.JSONObject(channel))); }
        catch (Exception ignored) { /* Invalid external state leaves the home screen usable. */ }
        timer.post(tick);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundResource(TvAppearancePreferences.backgroundImage(this));
        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(0, dp(64), 0, dp(8));
        rail.setBackgroundColor(TvAppearancePreferences.surface(this));
        for (int i = 0; i < NAV_NAMES.length; i++) {
            final Page target = NAV_PAGES[i];
            Button button = navigationButton(NAV_NAMES[i], NAV_ICONS[i], () -> {
                setMenuExpanded(false);
                if (page != target) { focusKey = null; focusPosition = 0; focusPending = true; }
                show(target);
            });
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            rail.addView(button, new LinearLayout.LayoutParams(-1, dp(44)));
            navigation.add(button);
        }
        settingsNavigation = navigationButton("Настройки", "settings", this::settings);
        settingsNavigation.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        rail.addView(settingsNavigation, new LinearLayout.LayoutParams(-1, dp(44)));
        menuContainer = new ScrollView(this);
        menuContainer.setFillViewport(true); menuContainer.setVerticalScrollBarEnabled(false);
        menuContainer.addView(rail, new ScrollView.LayoutParams(-1, -2));
        root.addView(menuContainer, new LinearLayout.LayoutParams(dp(230), -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(76), dp(4), dp(20), dp(4));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView searchLaunch = new ImageView(this);
        searchLaunch.setPadding(dp(10), dp(10), dp(10), dp(10));
        searchLaunch.setImageDrawable(TvIcons.icon("search", Color.WHITE, dp(26)));
        GradientDrawable searchCircle = new GradientDrawable();
        searchCircle.setShape(GradientDrawable.OVAL);
        searchCircle.setColor(TvAppearancePreferences.accent(this));
        searchLaunch.setBackground(searchCircle);
        searchLaunch.setFocusable(true);
        searchLaunch.setOnClickListener(v -> show(Page.SEARCH));
        searchLaunch.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN) {
                openNavigation(); return true;
            }
            return false;
        });
        searchLaunch.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(focused ? TvAppearancePreferences.focus(this) : TvAppearancePreferences.accent(this));
            circle.setStroke(dp(2), focused ? TvAppearancePreferences.accent(this) : Color.TRANSPARENT);
            searchLaunch.setBackground(circle);
        });
        searchLaunch.setContentDescription("Поиск видео, каналов и тем");
        toolbar.addView(searchLaunch, row(44, 44, 0, 0));
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout timeAndDate = new LinearLayout(this);
        timeAndDate.setOrientation(LinearLayout.VERTICAL);
        timeAndDate.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        clock = label("", 21, TvAppearancePreferences.text(this));
        clock.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        calendar = label("", 11, TvAppearancePreferences.secondary(this));
        calendar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        timeAndDate.addView(clock, new LinearLayout.LayoutParams(-1, dp(27)));
        timeAndDate.addView(calendar, new LinearLayout.LayoutParams(-1, dp(17)));
        toolbar.addView(timeAndDate, row(100, 44, 0, 0));
        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.tv_wordmark);
        brand.setScaleType(ImageView.ScaleType.CENTER_CROP);
        brand.setContentDescription("RussTubeTV");
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(dp(190), dp(44));
        brandParams.rightMargin = dp(8);
        toolbar.addView(brand, brandParams);
        content.addView(toolbar, row(-1, 48, 0, 18));
        heading = label("", 24, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(heading, row(-1, 34, 0, 0));
        description = label("", 13, Color.rgb(162, 175, 194));
        content.addView(description, row(-1, 28, 0, 6));
        searchRow = new LinearLayout(this);
        query = new EditText(this);
        query.setSingleLine(true);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(Color.rgb(145, 156, 175));
        query.setTextSize(17 * TvScalePreferences.get(this, TvScalePreferences.INTERFACE));
        query.setHint("Название видео или канала");
        query.setBackgroundResource(R.drawable.search_field_background);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submitSearch(); return true;
            }
            return false;
        });
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (page != Page.SEARCH) return;
                timer.removeCallbacks(autoSearch);
                cancel(); candidates.clear(); items.clear(); baseItems.clear();
                display("Введите хотя бы два символа для поиска.");
                if (value.toString().trim().length() >= 2) timer.postDelayed(autoSearch, 650);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        searchRow.addView(query, new LinearLayout.LayoutParams(0, dp(42), 1));
        findButton = button("Найти", this::submitSearch);
        searchRow.addView(findButton, row(100, 42, 8, 0));
        content.addView(searchRow, row(-1, 48, 0, 6));
        actions = new LinearLayout(this);
        HorizontalScrollView actionScroller = new HorizontalScrollView(this);
        actionScroller.setHorizontalScrollBarEnabled(false);
        actionScroller.setFillViewport(false);
        actionScroller.addView(actions, new HorizontalScrollView.LayoutParams(-2, -1));
        content.addView(actionScroller, row(-1, 40, 0, 10));
        status = label("", 11, Color.rgb(161, 175, 195));
        content.addView(status, row(-1, 24, 0, 4));
        FrameLayout results = new FrameLayout(this);
        grid = new GridView(this);
        grid.setId(View.generateViewId());
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(cardDp(Math.round(190 * TvScalePreferences.get(this, TvScalePreferences.GRID))));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(cardDp(14));
        grid.setVerticalSpacing(cardDp(16));
        grid.setPadding(cardDp(5), cardDp(5), cardDp(5), cardDp(10));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setDrawSelectorOnTop(true);
        adapter = new Cards();
        grid.setAdapter(adapter);
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int p, long id) {
                prefetchNear(p, Integer.compare(p, lastPrefetchPosition));
                lastPrefetchPosition = p;
                updateCardFocus();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { updateCardFocus(); }
        });
        grid.setOnFocusChangeListener((v, focused) -> updateCardFocus());
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                if (grid != null && visible > 0) prefetchNear(first + visible - 1, 1);
            }
        });
        grid.setOnItemClickListener((p, v, position, id) -> {
            if (position < items.size()) {
                VideoItem item = items.get(position);
                if (channelsMode) showChannel(item);
                else { rememberFocus(); returningFromPlayer = true; VideoActions.play(this, item); }
            }
        });
        grid.setOnItemLongClickListener((p, v, position, id) -> {
            if (position >= items.size()) return false;
            VideoItem item = items.get(position);
            VideoActions.show(this, item, () -> showChannel(item), this::refreshSavedFilms);
            return true;
        });
        grid.setOnKeyListener((v, key, event) -> {
            int selected = grid.getSelectedItemPosition();
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN
                    && (selected < 0 || selected % grid.getNumColumns() == 0)) {
                openNavigation(); return true;
            }
            if (key == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_DOWN) {
                int position = grid.getSelectedItemPosition();
                if (position >= 0 && position < items.size()) {
                    VideoItem item = items.get(position);
                    VideoActions.show(this, item, () -> showChannel(item), this::refreshSavedFilms);
                }
                return true;
            }
            return false;
        });
        results.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        empty = label("", 18, Color.rgb(168, 182, 204));
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(30), dp(20), dp(30), dp(20));
        results.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        content.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView hint = label("OK — смотреть    ·    Удерживать OK / Меню — канал и подписка", 11,
                Color.rgb(120, 136, 158));
        content.addView(hint, row(-1, 24, 0, 0));
        root.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        setMenuExpanded(false);
        return root;
    }

    private void show(Page target) {
        if (target == page && !focusPending) rememberFocus();
        if (target == Page.LIVE && page != Page.LIVE) liveGroup = LiveGroup.ALL;
        if (target == Page.FILMS && page != Page.FILMS) filmGroup = FilmGroup.ALL;
        cancel();
        page = target;
        activeChannel = null;
        channelsMode = false;
        candidates.clear(); subscriptionCandidates.clear(); items.clear(); baseItems.clear(); history.clear();
        for (WatchHistoryStore.Entry entry : WatchHistoryStore.load(this)) history.add(entry.item);
        updateNavigation();
        searchRow.setVisibility(page == Page.SEARCH || page == Page.LIVE ? View.VISIBLE : View.GONE);
        findButton.setVisibility(page == Page.LIVE ? View.VISIBLE : View.GONE);
        actions.removeAllViews();
        addAction("Обновить", () -> show(page));
        if (page != Page.CHANNELS) addSortAction();
        switch (page) {
            case HOME:
            case RECOMMENDATIONS:
                heading.setText(page == Page.HOME ? "Главная" : "Рекомендации");
                description.setText("Новые видео подписок и рекомендации по вашим интересам");
                addAction("Наука", () -> discover("наука"));
                addAction("Путешествия", () -> discover("путешествия"));
                addAction("Технологии", () -> discover("технологии"));
                boolean byHistory = getSharedPreferences("browse", 0)
                        .getBoolean("recommendations", true);
                boolean bySubscriptions = getSharedPreferences("browse", 0)
                        .getBoolean("recommend_subscriptions", true);
                if (bySubscriptions) {
                    for (VideoItem channel : SubscriptionStore.load(this))
                        enqueueSubscription(channel);
                }
                if (byHistory) {
                    List<String> queries = RecommendationEngine.queries(history);
                    if (!queries.isEmpty()) {
                        description.setText("Новые видео подписок и рекомендации · "
                                + String.join(" · ", queries));
                        for (String value : queries) searchAll(value, false);
                    }
                }
                if (pending == 0) {
                    display("Подпишитесь на каналы или посмотрите несколько видео — здесь появятся новые видео и рекомендации.");
                    return;
                }
                break;
            case SEARCH:
                heading.setText("Поиск");
                description.setText("Поиск на выбранных в настройках платформах");
                qualityButton = addAction(qualityButtonLabel(), this::cycleQualityFilter);
                if (query.getText().toString().trim().length() >= 2) searchAll(query.getText().toString().trim(), false);
                break;
            case FILMS:
                heading.setText("Фильмы");
                description.setText(filmGroup == FilmGroup.ALL
                        ? "Полнометражные видео и добавленные вами фильмы"
                        : "Полнометражные видео · " + filmSource());
                filmGroupAction("Все фильмы", FilmGroup.ALL);
                filmGroupAction("Кино на RUTUBE", FilmGroup.RUTUBE);
                filmGroupAction("Кино на VK", FilmGroup.VK);
                filmGroupAction("Кино на OK.ru", FilmGroup.OK);
                addAction("Художественные", () -> findFilms("художественный фильм"));
                addAction("Документальные", () -> findFilms("документальный фильм"));
                addAction("Мультфильмы", () -> findFilms("мультфильм полный"));
                rebuildBaseItems();
                if (filmGroup != FilmGroup.ALL
                        && !MobileBrowsePreferences.sourceEnabled(this, filmSource())) {
                    display("Площадка отключена в Настройки → Поиск."); return;
                }
                if ((filmGroup == FilmGroup.ALL || filmGroup == FilmGroup.OK)
                        && MobileBrowsePreferences.sourceEnabled(this, "OK"))
                    enqueue("OK", OkClient::newFilmsCatalog, false, true);
                if ((filmGroup == FilmGroup.ALL || filmGroup == FilmGroup.VK)
                        && MobileBrowsePreferences.sourceEnabled(this, "VK VIDEO"))
                    enqueue("VK VIDEO", () -> repository.search("VK VIDEO", "фильмы и сериалы"));
                if ((filmGroup == FilmGroup.ALL || filmGroup == FilmGroup.RUTUBE)
                        && MobileBrowsePreferences.sourceEnabled(this, "RUTUBE"))
                    enqueue("RUTUBE", RutubeTvCatalog::movies, false, true);
                break;
            case SUBSCRIPTIONS:
                heading.setText("Подписки");
                description.setText("Ваши каналы · сохранены на этом устройстве");
                addAction("Каналы", this::showChannels);
                List<VideoItem> channels = SubscriptionStore.load(this);
                for (VideoItem channel : channels) {
                    if (MobileBrowsePreferences.sourceEnabled(this, channel.source))
                        enqueue(channel.source, () -> repository.channel(channel));
                }
                if (channels.isEmpty()) {
                    display("Подпишитесь на канал: удерживайте OK на карточке видео\nи выберите «Подписаться»."); return;
                }
                break;
            case CHANNELS:
                heading.setText("Каналы");
                description.setText("Каналы, на которые вы подписаны");
                showChannels(); return;
            case LIVE:
                heading.setText("В эфире");
                description.setText(liveGroup == LiveGroup.ALL
                        ? "Прямые трансляции VK Видео, PeerTube и других площадок"
                        : "Телеканалы · " + (liveGroup == LiveGroup.RUTUBE_TV ? "RUTUBE"
                        : liveGroup == LiveGroup.VK_TV ? "VK Видео" : "OK.ru"));
                liveGroupAction("Все эфиры", LiveGroup.ALL);
                liveGroupAction("ТВ RUTUBE", LiveGroup.RUTUBE_TV);
                liveGroupAction("ТВ VK Видео", LiveGroup.VK_TV);
                liveGroupAction("ТВ OK.ru", LiveGroup.OK_TV);
                if (liveGroup != LiveGroup.ALL) {
                    String source = liveGroup == LiveGroup.RUTUBE_TV ? "RUTUBE"
                            : liveGroup == LiveGroup.VK_TV ? "VK VIDEO" : "OK";
                    if (!MobileBrowsePreferences.sourceEnabled(this, source)) {
                        display("Площадка отключена в Настройки → Поиск."); return;
                    }
                    if (liveGroup == LiveGroup.RUTUBE_TV)
                        enqueue(source, RutubeTvCatalog::load);
                    if (liveGroup == LiveGroup.OK_TV)
                        enqueue(source, OkClient::liveCatalog);
                    enqueue(source, () -> repository.live(source, "телеканал прямой эфир"));
                    enqueue(source, () -> repository.live(source, "тв онлайн"));
                    break;
                }
                addAction("Новости", () -> searchLive("новости"));
                addAction("Спорт", () -> searchLive("спорт"));
                addAction("Музыка", () -> searchLive("музыка"));
                if (MobileBrowsePreferences.enabledSources(this, true).length == 0
                        && !MobileBrowsePreferences.sourceEnabled(this, "OK")) {
                    display("Выбранные платформы не поддерживают эфиры. Откройте Настройки → Поиск.");
                    return;
                }
                if (MobileBrowsePreferences.sourceEnabled(this, PeerTubeClient.SOURCE))
                    enqueue(PeerTubeClient.SOURCE, () -> repository.live(PeerTubeClient.SOURCE, ""));
                if (MobileBrowsePreferences.sourceEnabled(this, "OK")) {
                    enqueue("OK", () -> OkClient.liveCatalog("livemusic"));
                    enqueue("OK", () -> OkClient.liveCatalog("livenews"));
                    enqueue("OK", () -> OkClient.liveCatalog("live_entertainment"));
                }
                searchAll("прямой эфир", true);
                searchAll("24/7", true);
                break;
            case HISTORY:
                heading.setText("История");
                description.setText("Недавно просмотренное · OK продолжит с сохранённого места");
                baseItems.addAll(history);
                break;
            case POPULAR:
                heading.setText("Популярное");
                description.setText("Подборка по числу просмотров на платформах");
                searchAll("популярное", false);
                break;
            case FAVORITES:
                heading.setText("Избранное");
                description.setText("Сохранённые видео");
                baseItems.addAll(FavoriteStore.load(this));
                break;
        }
        applySort();
        display(page == Page.SEARCH ? "Введите запрос для поиска видео."
                : page == Page.FILMS ? "Полнометражные видео пока не найдены." : "Пока здесь нет видео.");
        if (page == Page.SEARCH) query.requestFocus();
    }

    private void discover(String value) { query.setText(value); show(Page.SEARCH); }
    private void searchLive(String value) { liveGroup = LiveGroup.ALL; query.setText(value); show(Page.LIVE); submitSearch(); }
    private void selectLiveGroup(LiveGroup group) {
        liveGroup = group;
        focusKey = null; focusPosition = 0; focusPending = true;
        show(Page.LIVE);
    }

    private void liveGroupAction(String title, LiveGroup group) {
        Button button = addAction(title, () -> selectLiveGroup(group));
        if (group == liveGroup) {
            button.setBackground(chipBackground(true));
            button.setTextColor(TvAppearancePreferences.accent(this));
        }
    }

    private String filmSource() {
        switch (filmGroup) {
            case RUTUBE: return "RUTUBE";
            case VK: return "VK VIDEO";
            case OK: return "OK";
            default: return null;
        }
    }

    private void selectFilmGroup(FilmGroup group) {
        filmGroup = group;
        focusKey = null; focusPosition = 0; focusPending = true;
        show(Page.FILMS);
    }

    private void filmGroupAction(String title, FilmGroup group) {
        Button button = addAction(title, () -> selectFilmGroup(group));
        if (group == filmGroup) {
            button.setBackground(chipBackground(true));
            button.setTextColor(TvAppearancePreferences.accent(this));
        }
    }

    private void findFilms(String value) {
        cancel(); candidates.clear(); items.clear(); baseItems.clear();
        rebuildBaseItems();
        applySort();
        if (filmGroup == FilmGroup.ALL) {
            for (String source : new String[]{"RUTUBE", "VK VIDEO", "OK"})
                if (MobileBrowsePreferences.sourceEnabled(this, source))
                    enqueue(source, () -> repository.search(source, value));
        } else if (MobileBrowsePreferences.sourceEnabled(this, filmSource())) {
            String source = filmSource();
            enqueue(source, () -> repository.search(source, value));
        }
        display("Полнометражные видео пока не найдены.");
    }

    private void refreshSavedFilms() {
        if (page != Page.FILMS || activeChannel != null) return;
        rebuildBaseItems();
        applySort();
        display("Полнометражные видео пока не найдены.");
    }

    private void submitSearch() {
        String value = query.getText().toString().trim();
        if (value.length() < 2) { query.setError("Введите хотя бы два символа"); return; }
        timer.removeCallbacks(autoSearch);
        startSearch(value, true);
    }

    private void startSearch(String value, boolean dismissKeyboard) {
        if (value.length() < 2) return;
        if (dismissKeyboard) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(query.getWindowToken(), 0);
            query.clearFocus();
        }
        cancel(); candidates.clear(); items.clear(); baseItems.clear();
        searchAll(value, page == Page.LIVE);
        display("Ничего не найдено. Попробуйте другой запрос.");
    }

    private void showChannels() {
        focusKey = null; focusPosition = 0; focusPending = true;
        cancel(); channelsMode = true; activeChannel = null;
        baseItems.clear(); baseItems.addAll(SubscriptionStore.load(this));
        if (sortButton != null) sortButton.setVisibility(View.GONE);
        applySort();
        description.setText("OK — открыть канал · удерживать OK — управление подпиской");
        display("У вас пока нет подписок.");
    }

    private void updateCardFocus() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View view = grid.getChildAt(i);
            boolean focused = grid.hasFocus() && grid.getFirstVisiblePosition() + i == grid.getSelectedItemPosition();
            if (view.getTag() instanceof Card) {
                Card card = (Card) view.getTag();
                view.setBackground(focused ? card.focusedBackground : card.normalBackground);
                view.setForeground(focused ? card.focusedBorder : card.normalBorder);
                view.setElevation(focused ? cardDp(12) : 0);
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    view.setOutlineAmbientShadowColor(TvAppearancePreferences.accent(this));
                    view.setOutlineSpotShadowColor(TvAppearancePreferences.accent(this));
                }
                card.title.setTextColor(TvAppearancePreferences.text(this));
                int secondary = TvAppearancePreferences.secondary(this);
                card.channel.setTextColor(secondary);
                card.views.setTextColor(secondary);
                card.date.setTextColor(secondary);
                card.title.setSingleLine(focused);
                card.title.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
                card.title.setSelected(focused);
                view.setScaleX(1f); view.setScaleY(1f);
            }
        }
    }

    private void showChannel(VideoItem channel) {
        focusKey = null; focusPosition = 0; focusPending = true;
        cancel(); channelsMode = false; activeChannel = channel;
        searchRow.setVisibility(View.GONE);
        items.clear(); baseItems.clear(); candidates.clear();
        heading.setText(channel.channelName.isEmpty() ? "Канал " + channel.channelId : channel.channelName);
        description.setText(channel.source + " · Видео канала");
        actions.removeAllViews();
        addAction("Назад", () -> show(page));
        addSortAction();
        boolean subscribed = SubscriptionStore.contains(this, channel);
        addAction(subscribed ? "Отписаться" : "Подписаться", () -> {
            try { SubscriptionStore.set(this, channel, !subscribed); showChannel(channel); }
            catch (Exception error) { message(error.getMessage()); }
        });
        enqueue(channel.source, () -> repository.channel(channel));
        applySort();
        display("Платформа не вернула видео этого канала.");
    }

    private void searchAll(String value, boolean live) {
        String[] sources = MobileBrowsePreferences.enabledSources(this, live);
        for (String source : sources) enqueue(source,
                () -> live ? repository.live(source, value) : repository.search(source, value));
    }

    private void enqueue(String source, Load operation) {
        enqueue(source, operation, false);
    }

    private void enqueueSubscription(VideoItem channel) {
        if (MobileBrowsePreferences.sourceEnabled(this, channel.source))
            enqueue(channel.source, () -> repository.channel(channel), true);
    }

    private void enqueue(String source, Load operation, boolean subscription) {
        enqueue(source, operation, subscription, false);
    }

    private void enqueue(String source, Load operation, boolean subscription,
                         boolean trustedFilmCollection) {
        int current = generation;
        boolean filmMode = page == Page.FILMS && activeChannel == null;
        pending++;
        jobs.add(io.submit(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            List<VideoItem> loaded = new ArrayList<>();
            String error = null;
            try {
                for (VideoItem item : operation.run()) {
                    if (!filmMode || FilmCatalog.includes(item, trustedFilmCollection)) loaded.add(item);
                }
            }
            catch (Exception failure) { error = source; }
            if (BuildConfig.DEBUG) {
                int thumbnailCount = 0;
                for (VideoItem item : loaded)
                    if (item.thumbnail != null && !item.thumbnail.isEmpty()) thumbnailCount++;
                android.util.Log.i("RussTubeImagePerf", "catalog=" + source
                        + " urlReadyMs=" + (android.os.SystemClock.elapsedRealtime() - started)
                        + " thumbnails=" + thumbnailCount);
            }
            final List<VideoItem> result = loaded;
            final String problem = error;
            runOnUiThread(() -> {
                if (isFinishing() || current != generation) return;
                pending--;
                if (problem != null && !failed.contains(problem)) failed.add(problem);
                candidates.addAll(result);
                if (subscription) subscriptionCandidates.addAll(result);
                scheduleResults(page == Page.FILMS ? "Полнометражные видео пока не найдены. Выберите другую тему."
                        : page == Page.LIVE ? "Активные эфиры не найдены. Попробуйте тему или обновите позже."
                        : "Видео не найдены. Попробуйте поиск или обновите позже.");
                if (page == Page.SEARCH && qualityFilter > 0) inspectQualities(result);
            });
        }));
    }

    private void rebuildBaseItems() {
        baseItems.clear();
        if ((page == Page.HOME || page == Page.RECOMMENDATIONS) && activeChannel == null) {
            baseItems.addAll(HomeFeed.combine(subscriptionCandidates, candidates, history,
                    getSharedPreferences("browse", 0).getBoolean("recommendations", true)));
        } else {
            Map<String, VideoItem> unique = new LinkedHashMap<>();
            if (page == Page.FILMS && activeChannel == null)
                for (VideoItem item : SavedFilmsStore.load(this))
                    if (filmGroup == FilmGroup.ALL || filmSource().equals(item.source))
                        unique.put(item.stableKey(), item);
            for (VideoItem item : candidates) unique.put(item.stableKey(), item);
            baseItems.addAll(unique.values());
            if (page == Page.POPULAR) Collections.sort(baseItems,
                    (a, b) -> Long.compare(b.views, a.views));
        }
    }

    private String qualityButtonLabel() { return "Качество: " + QUALITY_LABELS[qualityFilter]; }

    private void cycleQualityFilter() {
        qualityFilter = (qualityFilter + 1) % QUALITY_LABELS.length;
        getSharedPreferences("browse", 0).edit().putInt("tv_quality_filter", qualityFilter).apply();
        if (qualityButton != null) qualityButton.setText(qualityButtonLabel());
        applySort();
        display("Видео указанного качества не найдены.");
        if (qualityFilter > 0) inspectQualities(new ArrayList<>(candidates));
    }

    private void inspectQualities(List<VideoItem> loaded) {
        List<VideoItem> missing = new ArrayList<>();
        for (VideoItem item : loaded) {
            if (item.maxWidth == 0 && qualityRequested.add(item.stableKey())) missing.add(item);
        }
        if (missing.isEmpty()) return;
        int current = generation;
        pending++;
        jobs.add(io.submit(() -> {
            List<VideoItem> inspected = SearchClient.inspectQualities(missing, 3);
            runOnUiThread(() -> {
                if (isFinishing() || current != generation) return;
                pending--;
                Map<String, VideoItem> byKey = new LinkedHashMap<>();
                for (VideoItem item : inspected) byKey.put(item.stableKey(), item);
                for (int i = 0; i < candidates.size(); i++) {
                    VideoItem replacement = byKey.get(candidates.get(i).stableKey());
                    if (replacement != null) candidates.set(i, replacement);
                }
                scheduleResults("Видео указанного качества не найдены.");
            });
        }));
        display("Проверяем качество видео…");
    }

    private void display(String emptyText) {
        adapter.notifyDataSetChanged();
        boolean loading = pending > 0;
        status.setText((loading ? "Загрузка…  ·  " : "") + "Найдено: " + items.size()
                + (failed.isEmpty() ? "" : "  ·  Недоступны: " + String.join(", ", failed)));
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(loading ? "Подбираем видео…" : emptyText);
        grid.post(() -> { restoreGridFocus(); updateCardFocus(); });
    }

    private void scheduleResults(String emptyText) {
        pendingResultsEmptyText = emptyText;
        timer.removeCallbacks(renderResults);
        timer.postDelayed(renderResults, items.isEmpty() ? 0 : 100);
    }

    private void rememberFocus() {
        int position = grid.getSelectedItemPosition();
        if (position >= 0 && position < items.size()) {
            focusKey = items.get(position).stableKey();
            focusPosition = position;
            focusPending = true;
        }
    }

    private void prefetchNear(int position, int direction) {
        if (position < 0 || items.isEmpty()) return;
        int width = grid.getColumnWidth();
        if (width <= 0) width = cardDp(Math.round(190 * TvScalePreferences.get(this, TvScalePreferences.GRID)));
        int columns = Math.max(1, grid.getNumColumns());
        int start = direction < 0 ? Math.max(0, position - columns * 2) : position + 1;
        int end = direction < 0 ? position : Math.min(items.size(), position + columns * 2 + 1);
        for (int i = start; i < end; i++)
            ((TubeApplication) getApplication()).imageLoader().prefetch(items.get(i).source,
                    items.get(i).thumbnail,
                    width, width * 9 / 16);
    }

    private void restoreGridFocus() {
        if (!focusPending) return;
        if (items.isEmpty()) {
            if (pending == 0 && !query.hasFocus()) {
                if (page == Page.SEARCH) query.requestFocus();
                else if (!menuExpanded) openNavigation();
            }
            return;
        }
        int position = -1;
        if (focusKey != null) for (int i = 0; i < items.size(); i++) {
            if (focusKey.equals(items.get(i).stableKey())) { position = i; break; }
        }
        if (position < 0 && focusKey != null && pending > 0) return;
        if (position < 0) position = Math.min(focusPosition, items.size() - 1);
        grid.setSelection(position);
        if (!menuExpanded) grid.requestFocus();
        focusPending = false;
    }

    private void cancel() {
        generation++;
        timer.removeCallbacks(renderResults);
        for (Future<?> job : jobs) job.cancel(true);
        jobs.clear(); io.purge(); pending = 0; failed.clear(); qualityRequested.clear();
    }

    private void settings() {
        startActivity(new Intent(this, TvSettingsActivity.class));
    }

    private void message(String value) { new AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK", null).show(); }
    private Button addAction(String title, Runnable action) {
        Button result = button(title, action);
        actions.addView(result, row(-2, 36, 0, 0));
        return result;
    }

    private void addSortAction() { sortButton = addAction(sortMode.label + "  ▾", this::chooseSort); }

    private void chooseSort() {
        VideoSort.Mode[] modes = VideoSort.Mode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label;
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(new SideSelectionPanel(this, "Порядок видео", labels,
                sortMode.ordinal(), choice -> {
                    sortMode = modes[choice];
                    getSharedPreferences("browse", 0).edit()
                            .putString("video_sort", sortMode.name()).apply();
                    if (sortButton != null) sortButton.setText(sortMode.label + "  ▾");
                    applySort();
                    display("Видео не найдены.");
                    dialog.dismiss();
                    if (sortButton != null) sortButton.requestFocus();
                }));
        dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setDimAmount(.48f);
        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        dialog.getWindow().setGravity(Gravity.RIGHT);
        dialog.getWindow().setLayout(Math.min(dp(500),
                getResources().getDisplayMetrics().widthPixels * 45 / 100), -1);
    }

    private void applySort() {
        items.clear();
        int minWidth = page == Page.SEARCH && activeChannel == null
                ? QUALITY_WIDTHS[qualityFilter] : 0;
        Set<String> unavailable = UnavailableVideoStore.keys(this);
        for (VideoItem item : baseItems) {
            if (page != Page.HISTORY && page != Page.FAVORITES
                    && !(page == Page.CHANNELS && activeChannel == null)
                    && !VideoVisibility.showInDiscovery(item)) continue;
            if (item.maxWidth >= minWidth && (page == Page.HISTORY || page == Page.FAVORITES
                    || channelsMode || !unavailable.contains(item.stableKey()))) items.add(item);
        }
        if (!channelsMode) VideoSort.apply(items, sortMode);
        if (page == Page.FILMS && activeChannel == null) {
            Map<String, VideoItem> byKey = new LinkedHashMap<>();
            for (VideoItem item : items) byKey.put(item.stableKey(), item);
            List<VideoItem> pinned = new ArrayList<>();
            for (VideoItem saved : SavedFilmsStore.load(this)) {
                VideoItem item = byKey.remove(saved.stableKey());
                if (item != null) pinned.add(item);
            }
            pinned.addAll(byKey.values());
            items.clear();
            items.addAll(pinned);
        }
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(13 * TvScalePreferences.get(this, TvScalePreferences.INTERFACE)); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setPadding(dp(12), 0, dp(12), 0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        b.setTextColor(TvAppearancePreferences.text(this));
        b.setBackground(chipBackground(false));
        b.setOnFocusChangeListener((v, focused) -> b.setBackground(chipBackground(focused)));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private GradientDrawable chipBackground(boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(focused ? TvAppearancePreferences.focus(this)
                : TvAppearancePreferences.surface(this));
        background.setCornerRadius(dp(9));
        background.setStroke(dp(1), focused ? TvAppearancePreferences.accent(this)
                : (TvAppearancePreferences.accent(this) & 0x44ffffff));
        return background;
    }

    private GradientDrawable cardBorder(boolean focused) {
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(cardDp(8));
        border.setStroke(cardDp(focused ? 3 : 1), focused
                ? TvAppearancePreferences.accent(this) : Color.TRANSPARENT);
        return border;
    }

    private Button navigationButton(String text, String icon, Runnable action) {
        Button button = button(text, action);
        button.setTextSize(16 * TvScalePreferences.get(this, TvScalePreferences.INTERFACE));
        button.setPadding(dp(16), 0, dp(8), 0);
        button.setCompoundDrawablePadding(dp(12));
        button.setCompoundDrawables(TvIcons.icon(icon, Color.rgb(175, 181, 191), dp(24)),
                null, null, null);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnFocusChangeListener((view, focused) -> {
            GradientDrawable background = new GradientDrawable();
            background.setColor(focused ? TvAppearancePreferences.focus(this) : Color.TRANSPARENT);
            background.setStroke(dp(2), focused ? TvAppearancePreferences.accent(this)
                    : Color.TRANSPARENT);
            background.setCornerRadius(dp(8));
            view.setBackground(background);
        });
        return button;
    }

    private void updateNavigation() {
        for (int i = 0; i < navigation.size(); i++) {
            Button button = navigation.get(i);
            boolean selected = NAV_PAGES[i] == page;
            button.setTextSize((selected ? 20 : 16) * TvScalePreferences.get(this, TvScalePreferences.INTERFACE));
            button.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            button.setTextColor(selected ? TvAppearancePreferences.text(this)
                    : TvAppearancePreferences.secondary(this));
            button.setCompoundDrawables(TvIcons.icon(NAV_ICONS[i],
                    selected ? TvAppearancePreferences.accent(this)
                            : TvAppearancePreferences.secondary(this), dp(24)),
                    null, null, null);
        }
    }

    private void openNavigation() {
        setMenuExpanded(true);
        int selected = 0;
        for (int i = 0; i < NAV_PAGES.length; i++) if (NAV_PAGES[i] == page) selected = i;
        navigation.get(selected).requestFocus();
    }

    private TextView label(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value);
        t.setTextSize(size * TvScalePreferences.get(this, TvScalePreferences.INTERFACE));
        t.setTextColor(color == Color.WHITE ? TvAppearancePreferences.text(this) : color);
        t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    private TextView cardLabel(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    private LinearLayout.LayoutParams row(int width, int height, int left, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width < 0 ? width : dp(width), dp(height));
        p.setMargins(dp(left), 0, dp(6), dp(bottom)); return p;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density
            * TvScalePreferences.get(this, TvScalePreferences.INTERFACE)); }
    private int cardDp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private interface Load { List<VideoItem> run() throws Exception; }

    private void goBack() {
        if (activeChannel != null || channelsMode) { show(page); return; }
        if (page == Page.SEARCH && query.hasFocus()) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(query.getWindowToken(), 0);
            query.clearFocus(); return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (!menuExpanded) {
            openNavigation();
            lastBackAt = now;
            return;
        }
        if (!getSharedPreferences("browse", 0).getBoolean("double_back_exit", true)
                || now - lastBackAt <= 2500) { finish(); return; }
        lastBackAt = now;
        android.widget.Toast.makeText(this, "Нажмите Назад ещё раз для выхода",
                android.widget.Toast.LENGTH_SHORT).show();
    }
    private void setMenuExpanded(boolean expanded) {
        menuExpanded = expanded;
        if (!expanded) lastBackAt = 0L;
        menuContainer.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams menuSize = (LinearLayout.LayoutParams) menuContainer.getLayoutParams();
        menuSize.width = dp(expanded ? 230 : 64);
        menuContainer.setLayoutParams(menuSize);
        for (int i = 0; i < navigation.size(); i++)
            navigation.get(i).setText(expanded ? NAV_NAMES[i] : "");
        settingsNavigation.setText(expanded ? "Настройки" : "");
        content.setPadding(dp(20), dp(4), dp(20), dp(4));
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!menuExpanded && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                && event.getAction() == KeyEvent.ACTION_DOWN && getCurrentFocus() == query
                && query.getSelectionStart() <= 0) {
            openNavigation(); return true;
        }
        if (menuExpanded && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            setMenuExpanded(false); if (!items.isEmpty()) grid.requestFocus(); else query.requestFocus(); return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() { goBack(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("page", page.name()); state.putString("query", query.getText().toString());
        state.putString("live_group", liveGroup.name());
        state.putString("film_group", filmGroup.name());
        state.putString("focus_key", focusKey);
        state.putInt("focus_position", focusPosition);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume();
        updates.onResume();
        if (firstResume) { firstResume = false; return; }
        int newInterfaceStep = TvScalePreferences.getStep(this, TvScalePreferences.INTERFACE);
        int newGridStep = TvScalePreferences.getStep(this, TvScalePreferences.GRID);
        if (newInterfaceStep != interfaceScaleStep) {
            rememberFocus();
            recreate();
            return;
        }
        if (appearanceTheme != TvAppearancePreferences.theme(this)
                || appearanceSymbols != TvAppearancePreferences.symbols(this)) {
            rememberFocus();
            recreate();
            return;
        }
        if (newGridStep != gridScaleStep) {
            gridScaleStep = newGridStep;
            grid.setColumnWidth(cardDp(Math.round(190 * TvScalePreferences.get(this, TvScalePreferences.GRID))));
            grid.requestLayout();
        }
        if (returningFromPlayer && page != Page.FAVORITES) {
            returningFromPlayer = false;
            cardRevision++;
            applySort();
            adapter.notifyDataSetChanged();
            grid.post(this::restoreGridFocus);
            return;
        }
        returningFromPlayer = false;
        sortMode = VideoSort.parse(getSharedPreferences("browse", 0)
                .getString("video_sort", VideoSort.Mode.NEWEST.name()));
        if (activeChannel != null) { showChannel(activeChannel); return; }
        if (page == Page.HISTORY || page == Page.HOME || page == Page.RECOMMENDATIONS
                || page == Page.SUBSCRIPTIONS || page == Page.CHANNELS || page == Page.FAVORITES) show(page);
        else { if (sortButton != null) sortButton.setText(sortMode.label + "  ▾");
            applySort(); display("Видео не найдены."); }
    }

    @Override protected void onPause() {
        updates.onPause();
        super.onPause();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updates.onActivityResult(requestCode);
    }
    @Override protected void onDestroy() {
        updates.onDestroy(); timer.removeCallbacks(tick); timer.removeCallbacks(autoSearch);
        cancel(); io.shutdownNow(); super.onDestroy();
    }

    private final class Cards extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public VideoItem getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return getItem(p).stableKey().hashCode(); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            long bindStarted = android.os.SystemClock.elapsedRealtime();
            boolean created = recycled == null;
            Card holder;
            if (recycled == null) {
                LinearLayout card = new LinearLayout(BrowseActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setClipToOutline(true);
                GradientDrawable background = new GradientDrawable();
                background.setColor(TvAppearancePreferences.surface(BrowseActivity.this));
                background.setCornerRadius(cardDp(8)); card.setBackground(background);
                GradientDrawable focusedBackground = new GradientDrawable();
                focusedBackground.setColor(TvAppearancePreferences.focus(BrowseActivity.this));
                focusedBackground.setStroke(cardDp(2),
                        TvAppearancePreferences.accent(BrowseActivity.this));
                focusedBackground.setCornerRadius(cardDp(8));
                FrameLayout artwork = new FrameLayout(BrowseActivity.this) {
                    @Override protected void onMeasure(int widthSpec, int heightSpec) {
                        int width = View.MeasureSpec.getSize(widthSpec);
                        super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                                Math.round(width * 9f / 16f), View.MeasureSpec.EXACTLY));
                    }
                };
                ImageView image = new ImageView(BrowseActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                artwork.addView(image, new FrameLayout.LayoutParams(-1, -1));
                ImageView platform = new ImageView(BrowseActivity.this);
                platform.setScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout.LayoutParams platformParams = new FrameLayout.LayoutParams(
                        cardDp(28), cardDp(28), Gravity.TOP | Gravity.START);
                platformParams.setMargins(cardDp(8), cardDp(8), 0, 0); artwork.addView(platform, platformParams);
                TextView duration = cardLabel("", 11, Color.WHITE);
                duration.setPadding(cardDp(6), 0, cardDp(6), 0); duration.setBackgroundColor(Color.rgb(16, 18, 22));
                FrameLayout.LayoutParams durationParams = new FrameLayout.LayoutParams(-2, cardDp(22), Gravity.BOTTOM | Gravity.END);
                durationParams.setMargins(0, 0, cardDp(8), cardDp(8)); artwork.addView(duration, durationParams);
                GradientDrawable normalBorder = cardBorder(false);
                GradientDrawable focusedBorder = cardBorder(true);
                card.setForeground(normalBorder);
                ProgressBar progress = new ProgressBar(BrowseActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(1000); progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(229, 57, 53)));
                progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
                artwork.addView(progress, new FrameLayout.LayoutParams(-1, cardDp(3), Gravity.BOTTOM));
                card.addView(artwork, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout details = new LinearLayout(BrowseActivity.this);
                details.setOrientation(LinearLayout.VERTICAL); details.setPadding(cardDp(12), cardDp(10), cardDp(12), cardDp(10));
                TextView title = cardLabel("", 14, TvAppearancePreferences.text(BrowseActivity.this));
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                details.addView(title, new LinearLayout.LayoutParams(-1, cardDp(40)));
                TextView channel = cardLabel("", 12, Color.rgb(174, 187, 204)); channel.setSingleLine(true);
                details.addView(channel, new LinearLayout.LayoutParams(-1, cardDp(19)));
                TextView views = cardLabel("", 12, Color.rgb(174, 187, 204)); views.setSingleLine(true);
                details.addView(views, new LinearLayout.LayoutParams(-1, cardDp(19)));
                TextView date = cardLabel("", 12, Color.rgb(174, 187, 204)); date.setSingleLine(true);
                details.addView(date, new LinearLayout.LayoutParams(-1, cardDp(19)));
                card.addView(details, new LinearLayout.LayoutParams(-1, -2));
                holder = new Card(image, title, channel, views, date, platform, duration,
                        progress, background, focusedBackground, normalBorder, focusedBorder);
                card.setTag(holder); recycled = card;
            } else holder = (Card) recycled.getTag();
            VideoItem item = getItem(position);
            if (holder.boundItem != item || holder.channelsMode != channelsMode
                    || holder.revision != cardRevision) {
                holder.boundItem = item;
                holder.channelsMode = channelsMode;
                holder.revision = cardRevision;
                holder.title.setText(channelsMode ? (item.channelName.isEmpty() ? "Канал " + item.channelId : item.channelName) : item.title);
                holder.channel.setText(channelsMode ? item.source
                        : item.source + " · " + VideoPresentation.channel(item));
                holder.views.setText(channelsMode ? "" : VideoPresentation.views(item));
                holder.date.setText(channelsMode ? "" : VideoPresentation.published(item));
                holder.platform.setImageResource(SourceIcon.drawable(item.source));
                holder.platform.setContentDescription(item.source);
                holder.duration.setText(item.isLive ? "В ЭФИРЕ" : VideoPresentation.duration(item.durationMs));
                holder.duration.setVisibility(item.isLive || item.durationMs > 0 ? View.VISIBLE : View.GONE);
                WatchProgressStore.Progress watched = WatchProgressStore.get(BrowseActivity.this, item);
                long total = watched.durationMs > 0 ? watched.durationMs : item.durationMs;
                holder.progress.setVisibility(watched.positionMs > 0 && total > 0 ? View.VISIBLE : View.GONE);
                if (total > 0) holder.progress.setProgress((int) Math.min(1000, watched.positionMs * 1000 / total));
                holder.title.setTextColor(TvAppearancePreferences.text(BrowseActivity.this));
                holder.channel.setTextColor(TvAppearancePreferences.secondary(BrowseActivity.this));
                holder.views.setTextColor(TvAppearancePreferences.secondary(BrowseActivity.this));
                holder.date.setTextColor(TvAppearancePreferences.secondary(BrowseActivity.this));
                holder.title.setSingleLine(false); holder.title.setSelected(false);
                holder.title.setEllipsize(TextUtils.TruncateAt.END);
                recycled.setBackground(holder.normalBackground);
                recycled.setForeground(holder.normalBorder);
                recycled.setElevation(0);
                recycled.setScaleX(1); recycled.setScaleY(1);
            }
            int width = grid.getColumnWidth();
            if (width <= 0) width = cardDp(Math.round(190 * TvScalePreferences.get(BrowseActivity.this, TvScalePreferences.GRID)));
            ((TubeApplication) getApplication()).imageLoader().load(item.source, item.thumbnail, holder.image,
                    width, width * 9 / 16);
            long bindMs = android.os.SystemClock.elapsedRealtime() - bindStarted;
            if (BuildConfig.DEBUG && bindMs > 12)
                android.util.Log.i("RussTubeTvPerf", "cardBindMs=" + bindMs
                        + " position=" + position + " created=" + created);
            return recycled;
        }
    }
    private static final class Card {
        final ImageView image;
        final TextView title, channel, views, date, duration;
        final ImageView platform;
        final ProgressBar progress;
        final GradientDrawable normalBackground, focusedBackground, normalBorder, focusedBorder;
        VideoItem boundItem;
        boolean channelsMode;
        int revision;
        Card(ImageView image, TextView title, TextView channel, TextView views, TextView date,
             ImageView platform, TextView duration, ProgressBar progress,
             GradientDrawable normalBackground, GradientDrawable focusedBackground,
             GradientDrawable normalBorder, GradientDrawable focusedBorder) {
            this.image = image; this.title = title; this.channel = channel; this.views = views;
            this.date = date; this.platform = platform; this.duration = duration; this.progress = progress;
            this.normalBackground = normalBackground; this.focusedBackground = focusedBackground;
            this.normalBorder = normalBorder; this.focusedBorder = focusedBorder;
        }
    }
}
