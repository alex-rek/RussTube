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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Native, D-pad-first browsing shell with a persistent navigation rail. */
public final class BrowseActivity extends Activity {
    private enum Page { HOME, SEARCH, RECOMMENDATIONS, LIVE, SUBSCRIPTIONS, CHANNELS, HISTORY, POPULAR, FAVORITES }
    private static final Page[] NAV_PAGES = {Page.HOME, Page.POPULAR, Page.RECOMMENDATIONS, Page.LIVE,
            Page.SUBSCRIPTIONS, Page.CHANNELS, Page.HISTORY, Page.FAVORITES};
    private static final String[] NAV_NAMES = {"Главная", "Популярное", "Рекомендации", "В эфире",
            "Подписки", "Каналы", "История", "Избранное"};
    private static final String[] NAV_ICONS = {"home", "popular", "recommend", "live",
            "subscriptions", "channels", "history", "favorite"};
    private Page page = Page.HOME;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128));
    private final List<Future<?>> jobs = new ArrayList<>();
    private final List<VideoItem> items = new ArrayList<>();
    private final List<VideoItem> baseItems = new ArrayList<>();
    private final List<VideoItem> candidates = new ArrayList<>();
    private final List<VideoItem> history = new ArrayList<>();
    private final List<Button> navigation = new ArrayList<>();
    private final FeedRepository repository = new FeedRepository();
    private int generation;
    private int pending;
    private final List<String> failed = new ArrayList<>();
    private boolean channelsMode;
    private boolean firstResume = true;
    private VideoItem activeChannel;
    private VideoSort.Mode sortMode = VideoSort.Mode.NEWEST;
    private Button sortButton;
    private TextView heading;
    private TextView description;
    private TextView status;
    private TextView empty;
    private LinearLayout searchRow;
    private LinearLayout actions;
    private EditText query;
    private GridView grid;
    private Cards adapter;
    private LinearLayout rail;
    private ScrollView menuContainer;
    private LinearLayout content;
    private boolean menuExpanded;
    private long lastBackAt;
    private TextView clock;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(new SimpleDateFormat("HH:mm\nEEE, d MMM", new Locale("ru")).format(new Date()));
            timer.postDelayed(this, 30000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        sortMode = VideoSort.parse(getSharedPreferences("browse", 0)
                .getString("video_sort", VideoSort.Mode.NEWEST.name()));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(createContent());
        if (state != null) {
            try { page = Page.valueOf(state.getString("page", "HOME")); }
            catch (IllegalArgumentException ignored) { page = Page.HOME; }
            query.setText(state.getString("query", ""));
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
        root.setBackgroundColor(Color.rgb(11, 15, 20));
        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(0, dp(64), 0, dp(8));
        rail.setBackgroundColor(Color.rgb(37, 42, 50));
        for (int i = 0; i < NAV_NAMES.length; i++) {
            final Page target = NAV_PAGES[i];
            Button button = navigationButton(NAV_NAMES[i], NAV_ICONS[i], () -> {
                setMenuExpanded(false); show(target);
                if (!items.isEmpty()) grid.requestFocus();
            });
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            rail.addView(button, new LinearLayout.LayoutParams(-1, dp(44)));
            navigation.add(button);
        }
        Button settingsButton = navigationButton("Настройки", "settings", this::settings);
        settingsButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        rail.addView(settingsButton, new LinearLayout.LayoutParams(-1, dp(44)));
        menuContainer = new ScrollView(this);
        menuContainer.setFillViewport(true); menuContainer.setVerticalScrollBarEnabled(false);
        menuContainer.addView(rail, new ScrollView.LayoutParams(-1, -2));
        root.addView(menuContainer, new LinearLayout.LayoutParams(dp(230), -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(76), dp(4), dp(20), dp(4));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton search = new ImageButton(this);
        search.setImageDrawable(TvIcons.icon("search", Color.WHITE, dp(24)));
        search.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.rgb(229, 57, 53)); search.setBackground(circle);
        search.setOnClickListener(v -> show(Page.SEARCH));
        search.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN) {
                openNavigation(); return true;
            }
            return false;
        });
        search.setOnFocusChangeListener((v, focused) -> { circle.setStroke(dp(focused ? 2 : 0), Color.WHITE); });
        search.setContentDescription("Поиск видео");
        toolbar.addView(search, row(46, 46, 0, 0));
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        clock = label("", 12, Color.rgb(205, 210, 220));
        clock.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        toolbar.addView(clock, row(110, 44, 0, 0));
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.ic_launcher);
        toolbar.addView(mark, row(36, 36, 14, 0));
        TextView brand = label("RussTubeTV", 24, Color.WHITE); brand.setTypeface(Typeface.DEFAULT_BOLD);
        toolbar.addView(brand, row(150, 44, 0, 0));
        content.addView(toolbar, row(-1, 44, 0, 20));
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
        query.setTextSize(17);
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
        searchRow.addView(query, new LinearLayout.LayoutParams(0, dp(42), 1));
        searchRow.addView(button("Найти", this::submitSearch), row(100, 42, 8, 0));
        content.addView(searchRow, row(-1, 48, 0, 6));
        actions = new LinearLayout(this);
        content.addView(actions, row(-1, 40, 0, 10));
        status = label("", 11, Color.rgb(161, 175, 195));
        content.addView(status, row(-1, 24, 0, 4));
        FrameLayout results = new FrameLayout(this);
        grid = new GridView(this);
        grid.setId(View.generateViewId());
        float width = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        grid.setNumColumns(width >= 1150 ? 4 : 3);
        grid.setHorizontalSpacing(dp(14));
        grid.setVerticalSpacing(dp(16));
        grid.setPadding(dp(5), dp(5), dp(5), dp(10));
        grid.setClipToPadding(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setDrawSelectorOnTop(true);
        adapter = new Cards();
        grid.setAdapter(adapter);
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int p, long id) { updateCardFocus(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { updateCardFocus(); }
        });
        grid.setOnFocusChangeListener((v, focused) -> updateCardFocus());
        grid.setOnItemClickListener((p, v, position, id) -> {
            if (position < items.size()) {
                VideoItem item = items.get(position);
                if (channelsMode) showChannel(item); else VideoActions.play(this, item);
            }
        });
        grid.setOnItemLongClickListener((p, v, position, id) -> {
            if (position >= items.size()) return false;
            VideoItem item = items.get(position);
            VideoActions.show(this, item, () -> showChannel(item));
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
                    VideoActions.show(this, item, () -> showChannel(item));
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
        cancel();
        page = target;
        activeChannel = null;
        channelsMode = false;
        candidates.clear(); items.clear(); baseItems.clear(); history.clear();
        for (WatchHistoryStore.Entry entry : WatchHistoryStore.load(this)) history.add(entry.item);
        updateNavigation();
        searchRow.setVisibility(page == Page.SEARCH || page == Page.LIVE ? View.VISIBLE : View.GONE);
        actions.removeAllViews();
        addAction("Обновить", () -> show(page));
        if (page != Page.CHANNELS) addSortAction();
        switch (page) {
            case HOME:
            case RECOMMENDATIONS:
                heading.setText(page == Page.HOME ? "Главная" : "Рекомендации");
                description.setText("Видео по вашим интересам · RUTUBE, VK Видео, OK, Дзен");
                addAction("Наука", () -> discover("наука"));
                addAction("Путешествия", () -> discover("путешествия"));
                addAction("Технологии", () -> discover("технологии"));
                if (!getSharedPreferences("browse", 0).getBoolean("recommendations", true)) {
                    display("Рекомендации отключены. Их можно включить в настройках."); return;
                }
                List<String> queries = RecommendationEngine.queries(history);
                if (queries.isEmpty()) {
                    display("Посмотрите несколько видео — здесь появятся рекомендации.\nНачните с поиска или выберите интересную тему выше.");
                    return;
                }
                description.setText("На основе просмотров · " + String.join(" · ", queries));
                for (String value : queries) searchAll(value, false);
                break;
            case SEARCH:
                heading.setText("Поиск");
                description.setText("Единый поиск на четырёх платформах");
                addAction("Фильтры качества", () -> startActivity(new Intent(this, MainActivity.class)
                        .putExtra("search_query", query.getText().toString())));
                if (query.getText().toString().trim().length() >= 2) searchAll(query.getText().toString().trim(), false);
                break;
            case SUBSCRIPTIONS:
                heading.setText("Подписки");
                description.setText("Ваши каналы · сохранены на этом устройстве");
                addAction("Каналы", this::showChannels);
                List<VideoItem> channels = SubscriptionStore.load(this);
                for (VideoItem channel : channels) enqueue(channel.source, () -> repository.channel(channel));
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
                description.setText("Эфиры RUTUBE и VK Видео · только подтверждённые активные трансляции");
                addAction("Новости", () -> searchLive("новости"));
                addAction("Спорт", () -> searchLive("спорт"));
                addAction("Музыка", () -> searchLive("музыка"));
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
        display(page == Page.SEARCH ? "Введите запрос для поиска видео." : "Пока здесь нет видео.");
        if (page == Page.SEARCH) query.requestFocus();
    }

    private void discover(String value) { query.setText(value); show(Page.SEARCH); }
    private void searchLive(String value) { query.setText(value); show(Page.LIVE); submitSearch(); }

    private void submitSearch() {
        String value = query.getText().toString().trim();
        if (value.length() < 2) { query.setError("Введите хотя бы два символа"); return; }
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0);
        query.clearFocus();
        cancel(); candidates.clear(); items.clear(); baseItems.clear();
        searchAll(value, page == Page.LIVE);
        display("Ничего не найдено. Попробуйте другой запрос.");
    }

    private void showChannels() {
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
                GradientDrawable background = new GradientDrawable();
                background.setColor(focused ? Color.WHITE : Color.rgb(23, 26, 33));
                background.setCornerRadius(dp(12)); view.setBackground(background);
                card.title.setTextColor(focused ? Color.BLACK : Color.WHITE);
                int secondary = focused ? Color.DKGRAY : Color.rgb(174, 187, 204);
                card.channel.setTextColor(secondary);
                card.views.setTextColor(secondary);
                card.date.setTextColor(secondary);
                card.title.setSingleLine(focused);
                card.title.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
                card.title.setSelected(focused);
                view.setScaleX(focused ? 1.05f : 1f); view.setScaleY(focused ? 1.05f : 1f);
            }
        }
    }

    private void showChannel(VideoItem channel) {
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
        String[] sources = live ? new String[]{"RUTUBE", "VK VIDEO"} : FeedRepository.SOURCES;
        for (String source : sources) enqueue(source,
                () -> live ? repository.live(source, value) : repository.search(source, value));
    }

    private void enqueue(String source, Load operation) {
        int current = generation;
        pending++;
        jobs.add(io.submit(() -> {
            List<VideoItem> loaded = new ArrayList<>();
            String error = null;
            try { loaded = operation.run(); }
            catch (Exception failure) { error = source; }
            final List<VideoItem> result = loaded;
            final String problem = error;
            runOnUiThread(() -> {
                if (isFinishing() || current != generation) return;
                pending--;
                if (problem != null && !failed.contains(problem)) failed.add(problem);
                candidates.addAll(result);
                baseItems.clear();
                if ((page == Page.HOME || page == Page.RECOMMENDATIONS) && activeChannel == null) baseItems.addAll(RecommendationEngine.rank(candidates, history));
                else {
                    Map<String, VideoItem> unique = new LinkedHashMap<>();
                    for (VideoItem item : candidates) unique.put(item.stableKey(), item);
                    baseItems.addAll(unique.values());
                    if (page == Page.POPULAR) Collections.sort(baseItems,
                            (a, b) -> Long.compare(b.views, a.views));
                }
                applySort();
                display(page == Page.LIVE ? "Активные эфиры не найдены. Попробуйте тему или обновите позже."
                        : "Видео не найдены. Попробуйте поиск или обновите позже.");
            });
        }));
    }

    private void display(String emptyText) {
        adapter.notifyDataSetChanged();
        boolean loading = pending > 0;
        status.setText((loading ? "Загрузка…  ·  " : "") + "Найдено: " + items.size()
                + (failed.isEmpty() ? "" : "  ·  Недоступны: " + String.join(", ", failed)));
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(loading ? "Подбираем видео…" : emptyText);
        grid.post(this::updateCardFocus);
    }

    private void cancel() {
        generation++;
        for (Future<?> job : jobs) job.cancel(true);
        jobs.clear(); io.purge(); pending = 0; failed.clear();
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
        new AlertDialog.Builder(this).setTitle("Порядок видео")
                .setSingleChoiceItems(labels, sortMode.ordinal(), (dialog, choice) -> {
                    sortMode = modes[choice];
                    getSharedPreferences("browse", 0).edit()
                            .putString("video_sort", sortMode.name()).apply();
                    if (sortButton != null) sortButton.setText(sortMode.label + "  ▾");
                    applySort();
                    grid.setSelection(0);
                    display("Видео не найдены.");
                    dialog.dismiss();
                }).setNegativeButton("Отмена", null).show();
    }

    private void applySort() {
        items.clear();
        items.addAll(baseItems);
        if (!channelsMode) VideoSort.apply(items, sortMode);
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(13); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setPadding(dp(12), 0, dp(12), 0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        b.setBackgroundResource(R.drawable.navigation_item);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Button navigationButton(String text, String icon, Runnable action) {
        Button button = button(text, action);
        button.setTextSize(16);
        button.setPadding(dp(16), 0, dp(8), 0);
        button.setCompoundDrawablePadding(dp(12));
        button.setCompoundDrawables(TvIcons.icon(icon, Color.rgb(175, 181, 191), dp(24)),
                null, null, null);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnFocusChangeListener((view, focused) -> {
            GradientDrawable background = new GradientDrawable();
            background.setColor(focused ? Color.rgb(57, 63, 73) : Color.TRANSPARENT);
            view.setBackground(background);
        });
        return button;
    }

    private void updateNavigation() {
        for (int i = 0; i < navigation.size(); i++) {
            Button button = navigation.get(i);
            boolean selected = NAV_PAGES[i] == page;
            button.setTextSize(selected ? 20 : 16);
            button.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            button.setTextColor(selected ? Color.WHITE : Color.rgb(185, 191, 200));
            button.setCompoundDrawables(TvIcons.icon(NAV_ICONS[i],
                    selected ? Color.rgb(229, 57, 53) : Color.rgb(175, 181, 191), dp(24)),
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
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    private LinearLayout.LayoutParams row(int width, int height, int left, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width < 0 ? width : dp(width), dp(height));
        p.setMargins(dp(left), 0, dp(6), dp(bottom)); return p;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
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
        menuContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
        content.setPadding(dp(expanded ? 20 : 76), dp(4), dp(20), dp(4));
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
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume();
        if (firstResume) { firstResume = false; return; }
        sortMode = VideoSort.parse(getSharedPreferences("browse", 0)
                .getString("video_sort", VideoSort.Mode.NEWEST.name()));
        if (activeChannel != null) { showChannel(activeChannel); return; }
        if (page == Page.HISTORY || page == Page.HOME || page == Page.RECOMMENDATIONS
                || page == Page.SUBSCRIPTIONS || page == Page.CHANNELS || page == Page.FAVORITES) show(page);
        else { if (sortButton != null) sortButton.setText(sortMode.label + "  ▾");
            applySort(); display("Видео не найдены."); }
    }
    @Override protected void onDestroy() { timer.removeCallbacks(tick); cancel(); io.shutdownNow(); super.onDestroy(); }

    private final class Cards extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public VideoItem getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return getItem(p).stableKey().hashCode(); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Card holder;
            if (recycled == null) {
                LinearLayout card = new LinearLayout(BrowseActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setClipToOutline(true);
                GradientDrawable background = new GradientDrawable();
                background.setColor(Color.rgb(23, 26, 33)); background.setCornerRadius(dp(12)); card.setBackground(background);
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
                TextView platform = label("", 11, Color.WHITE);
                platform.setPadding(dp(7), 0, dp(7), 0);
                platform.setBackgroundColor(Color.rgb(28, 31, 38));
                FrameLayout.LayoutParams platformParams = new FrameLayout.LayoutParams(-2, dp(24), Gravity.TOP | Gravity.START);
                platformParams.setMargins(dp(8), dp(8), 0, 0); artwork.addView(platform, platformParams);
                TextView duration = label("", 11, Color.WHITE);
                duration.setPadding(dp(6), 0, dp(6), 0); duration.setBackgroundColor(Color.rgb(16, 18, 22));
                FrameLayout.LayoutParams durationParams = new FrameLayout.LayoutParams(-2, dp(22), Gravity.BOTTOM | Gravity.END);
                durationParams.setMargins(0, 0, dp(8), dp(8)); artwork.addView(duration, durationParams);
                TextView more = label("⋮", 23, Color.WHITE); more.setGravity(Gravity.CENTER);
                more.setBackgroundColor(Color.rgb(28, 31, 38));
                FrameLayout.LayoutParams moreParams = new FrameLayout.LayoutParams(dp(24), dp(28), Gravity.TOP | Gravity.END);
                moreParams.setMargins(0, dp(8), dp(8), 0); artwork.addView(more, moreParams);
                ProgressBar progress = new ProgressBar(BrowseActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(1000); progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(229, 57, 53)));
                progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
                artwork.addView(progress, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));
                card.addView(artwork, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout details = new LinearLayout(BrowseActivity.this);
                details.setOrientation(LinearLayout.VERTICAL); details.setPadding(dp(12), dp(10), dp(12), dp(10));
                TextView title = label("", 14, Color.WHITE); title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                details.addView(title, new LinearLayout.LayoutParams(-1, dp(40)));
                TextView channel = label("", 12, Color.rgb(174, 187, 204)); channel.setSingleLine(true);
                details.addView(channel, new LinearLayout.LayoutParams(-1, dp(19)));
                TextView views = label("", 12, Color.rgb(174, 187, 204)); views.setSingleLine(true);
                details.addView(views, new LinearLayout.LayoutParams(-1, dp(19)));
                TextView date = label("", 12, Color.rgb(174, 187, 204)); date.setSingleLine(true);
                details.addView(date, new LinearLayout.LayoutParams(-1, dp(19)));
                card.addView(details, new LinearLayout.LayoutParams(-1, -2));
                holder = new Card(image, title, channel, views, date, platform, duration, progress);
                card.setTag(holder); recycled = card;
            } else holder = (Card) recycled.getTag();
            VideoItem item = getItem(position);
            holder.title.setText(channelsMode ? (item.channelName.isEmpty() ? "Канал " + item.channelId : item.channelName) : item.title);
            holder.channel.setText(channelsMode ? item.source
                    : item.source + " · " + VideoPresentation.channel(item));
            holder.views.setText(channelsMode ? "" : VideoPresentation.views(item));
            holder.date.setText(channelsMode ? "" : VideoPresentation.published(item));
            holder.platform.setText(item.isLive && !channelsMode ? "● В ЭФИРЕ" : item.source);
            holder.duration.setText(item.isLive ? "В ЭФИРЕ" : VideoPresentation.duration(item.durationMs));
            holder.duration.setVisibility(item.isLive || item.durationMs > 0 ? View.VISIBLE : View.GONE);
            WatchProgressStore.Progress watched = WatchProgressStore.get(BrowseActivity.this, item);
            long total = watched.durationMs > 0 ? watched.durationMs : item.durationMs;
            holder.progress.setVisibility(watched.positionMs > 0 && total > 0 ? View.VISIBLE : View.GONE);
            if (total > 0) holder.progress.setProgress((int) Math.min(1000, watched.positionMs * 1000 / total));
            holder.title.setTextColor(Color.WHITE);
            holder.channel.setTextColor(Color.rgb(174, 187, 204));
            holder.views.setTextColor(Color.rgb(174, 187, 204));
            holder.date.setTextColor(Color.rgb(174, 187, 204));
            holder.title.setSingleLine(false); holder.title.setSelected(false);
            holder.title.setEllipsize(TextUtils.TruncateAt.END);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.rgb(23, 26, 33)); background.setCornerRadius(dp(12)); recycled.setBackground(background);
            recycled.setScaleX(1); recycled.setScaleY(1);
            ((TubeApplication) getApplication()).imageLoader().load(item.thumbnail, holder.image);
            return recycled;
        }
    }
    private static final class Card {
        final ImageView image;
        final TextView title, channel, views, date, platform, duration;
        final ProgressBar progress;
        Card(ImageView image, TextView title, TextView channel, TextView views, TextView date,
             TextView platform, TextView duration, ProgressBar progress) {
            this.image = image; this.title = title; this.channel = channel; this.views = views;
            this.date = date; this.platform = platform; this.duration = duration; this.progress = progress;
        }
    }
}
