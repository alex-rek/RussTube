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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Touch-first RussTube interface; search, stores and playback are shared with TV. */
public final class MobileBrowseActivity extends Activity {
    private enum Page { HOME, SEARCH, LIVE, SUBSCRIPTIONS, LIBRARY, HISTORY, FAVORITES, CHANNELS, POPULAR }
    private static final int BACKGROUND = 0xff0b0f14;
    private static final int PANEL = 0xff171a21;
    private static final int SECONDARY = 0xffaeb9cc;
    private static final int ACCENT = 0xffe53935;
    private static final Page[] TABS = {Page.HOME, Page.LIVE, Page.SUBSCRIPTIONS, Page.LIBRARY};
    private static final String[] TAB_LABELS = {"Главная", "В эфире", "Подписки", "Вы"};
    private static final String[] TAB_ICONS = {"home", "live", "subscriptions", "history"};

    private final ThreadPoolExecutor io = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128));
    private final List<Future<?>> jobs = new ArrayList<>();
    private final List<VideoItem> items = new ArrayList<>();
    private final List<VideoItem> baseItems = new ArrayList<>();
    private final List<VideoItem> candidates = new ArrayList<>();
    private final List<VideoItem> history = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    private final FeedRepository repository = new FeedRepository();
    private Page page = Page.HOME;
    private VideoItem activeChannel;
    private int generation;
    private int pending;
    private boolean firstResume = true;
    private UpdateCoordinator updates;
    private boolean skipResumeReload;
    private String searchQuery = "";
    private VideoSort.Mode sortMode = VideoSort.Mode.NEWEST;
    private TextView heading;
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
    private LinearLayout navigation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        updates = new UpdateCoordinator(this);
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

        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), 0, dp(12), 0);
        TextView mark = label("▶", 19, Color.WHITE);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(ACCENT, 7));
        toolbar.addView(mark, size(34, 27));
        TextView brand = label("RussTube", 22, Color.WHITE);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, dp(56), 1);
        brandParams.leftMargin = dp(7);
        toolbar.addView(brand, brandParams);
        TextView search = icon("search", "Поиск", () -> show(Page.SEARCH));
        toolbar.addView(search, size(46, 46));
        TextView refresh = label("↻", 26, Color.WHITE);
        refresh.setGravity(Gravity.CENTER);
        refresh.setContentDescription("Обновить");
        refresh.setOnClickListener(v -> refresh());
        toolbar.addView(refresh, size(42, 46));
        root.addView(toolbar, size(-1, 56));

        landscapeControls = new HorizontalScrollView(this);
        landscapeControls.setHorizontalScrollBarEnabled(false);
        LinearLayout compactRow = new LinearLayout(this);
        compactRow.setGravity(Gravity.CENTER_VERTICAL);
        compactRow.setPadding(dp(10), 0, dp(10), 0);
        String[] sections = {"Главная", "В эфире", "Подписки", "Вы"};
        for (int i = 0; i < sections.length; i++) {
            final Page target = TABS[i];
            compactAction(compactRow, sections[i], () -> show(target));
        }
        landscapeQuery = new EditText(this);
        landscapeQuery.setSingleLine(true);
        landscapeQuery.setTextSize(14);
        landscapeQuery.setTextColor(Color.WHITE);
        landscapeQuery.setHintTextColor(SECONDARY);
        landscapeQuery.setHint("Поиск видео");
        landscapeQuery.setPadding(dp(10), 0, dp(10), 0);
        landscapeQuery.setBackground(round(0xff222630, 10));
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
        compactAction(compactRow, "Искать", () -> {
            query.setText(landscapeQuery.getText()); submitSearch();
        });
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
        searchRow.setPadding(dp(16), dp(2), dp(16), dp(8));
        query = new EditText(this);
        query.setSingleLine(true);
        query.setTextSize(16);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(SECONDARY);
        query.setHint("Видео или канал на всех платформах");
        query.setPadding(dp(14), 0, dp(10), 0);
        query.setBackground(round(0xff222630, 12));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                submitSearch(); return true;
            }
            return false;
        });
        searchRow.addView(query, new LinearLayout.LayoutParams(0, dp(46), 1));
        TextView submit = icon("search", "Искать", this::submitSearch);
        searchRow.addView(submit, size(48, 46));
        root.addView(searchRow, size(-1, 56));

        heading = label("", 22, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(dp(16), 0, dp(16), 0);
        root.addView(heading, size(-1, 42));

        topics = new HorizontalScrollView(this);
        topics.setHorizontalScrollBarEnabled(false);
        LinearLayout topicRow = new LinearLayout(this);
        topicRow.setPadding(dp(16), dp(1), dp(16), dp(9));
        String[] labels = {"Популярное", "Новости", "Наука", "Музыка", "Игры", "Спорт", "Путешествия"};
        for (String value : labels) {
            TextView chip = label(value, 14, Color.WHITE);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(13), 0, dp(13), 0);
            chip.setBackground(round(PANEL, 9));
            LinearLayout.LayoutParams params = size(-2, 36);
            params.rightMargin = dp(8);
            topicRow.addView(chip, params);
            chip.setOnClickListener(v -> {
                if ("Популярное".equals(value)) show(Page.POPULAR);
                else { searchQuery = value; query.setText(value); show(Page.SEARCH); }
            });
        }
        topics.addView(topicRow);
        root.addView(topics, size(-1, 46));

        actions = new LinearLayout(this);
        actions.setPadding(dp(16), 0, dp(16), dp(4));
        root.addView(actions, size(-1, 49));
        sortRow = new LinearLayout(this);
        sortRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        sortRow.setPadding(dp(16), 0, dp(16), 0);
        sortButton = label("", 14, Color.WHITE);
        sortButton.setGravity(Gravity.CENTER);
        sortButton.setPadding(dp(14), 0, dp(14), 0);
        sortButton.setBackground(round(PANEL, 10));
        sortButton.setOnClickListener(v -> chooseSort());
        sortRow.addView(sortButton, size(-2, 38));
        root.addView(sortRow, size(-1, 48));
        status = label("", 12, SECONDARY);
        status.setPadding(dp(16), 0, dp(16), 0);
        root.addView(status, size(-1, 28));

        FrameLayout feed = new FrameLayout(this);
        list = new GridView(this);
        list.setNumColumns(1);
        list.setHorizontalSpacing(dp(10));
        list.setVerticalSpacing(dp(8));
        list.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(12));
        adapter = new FeedAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= items.size()) return;
            VideoItem item = items.get(position);
            if (page == Page.CHANNELS) showChannel(item);
            else VideoActions.play(this, item);
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
        root.addView(feed, new LinearLayout.LayoutParams(-1, 0, 1));

        navigation = new LinearLayout(this);
        navigation.setBackgroundColor(0xff11151b);
        navigation.setPadding(dp(4), dp(2), dp(4), dp(2));
        root.addView(navigation, size(-1, 62));
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
        cancel();
        page = target;
        activeChannel = null;
        items.clear(); baseItems.clear(); candidates.clear(); history.clear();
        for (WatchHistoryStore.Entry entry : WatchHistoryStore.load(this)) history.add(entry.item);
        actions.removeAllViews();
        landscapeQuery.setText(query.getText());
        searchRow.setVisibility(target == Page.SEARCH ? View.VISIBLE : View.GONE);
        topics.setVisibility(target == Page.HOME || target == Page.SEARCH || target == Page.POPULAR
                ? View.VISIBLE : View.GONE);
        switch (target) {
            case HOME:
                heading.setText("Главная");
                if (getSharedPreferences("browse", 0).getBoolean("recommendations", true)
                        && !history.isEmpty()) {
                    List<String> queries = RecommendationEngine.queries(history);
                    for (String value : queries) searchAll(value, false);
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
            case LIVE:
                heading.setText("В эфире");
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
                baseItems.addAll(history);
                break;
            case FAVORITES:
                heading.setText("Избранное");
                baseItems.addAll(FavoriteStore.load(this));
                break;
        }
        actions.setVisibility(actions.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        sortRow.setVisibility(target == Page.CHANNELS ? View.GONE : View.VISIBLE);
        updateSortLabel();
        applySort();
        updateNavigation();
        list.setSelection(0);
        render();
        if (target == Page.SEARCH && searchQuery.isEmpty()
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            query.requestFocus();
            query.postDelayed(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(query, InputMethodManager.SHOW_IMPLICIT), 120);
        }
        applyLayout();
    }

    private void showChannel(VideoItem channel) {
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
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(query.getWindowToken(), 0);
        query.clearFocus();
        landscapeQuery.clearFocus();
        show(Page.SEARCH);
    }

    private void refresh() {
        if (activeChannel != null) showChannel(activeChannel);
        else show(page);
    }

    private void settings() {
        boolean enabled = getSharedPreferences("browse", 0).getBoolean("recommendations", true);
        new AlertDialog.Builder(this).setTitle("Настройки RussTube")
                .setMultiChoiceItems(new String[]{"Рекомендации по истории просмотров"},
                        new boolean[]{enabled}, (dialog, index, checked) ->
                                getSharedPreferences("browse", 0).edit()
                                        .putBoolean("recommendations", checked).apply())
                .setNeutralButton("Сеть", (dialog, which) ->
                        startActivity(new Intent(this, NetworkSettingsActivity.class)))
                .setPositiveButton("Готово", (dialog, which) -> {
                    if (page == Page.HOME) show(Page.HOME);
                }).show();
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
            boolean failure = false;
            try { loaded = operation.run(); }
            catch (Exception error) { failure = true; }
            final List<VideoItem> result = loaded;
            final boolean failedRequest = failure;
            runOnUiThread(() -> {
                if (isFinishing() || current != generation) return;
                pending--;
                if (failedRequest && !failed.contains(source)) failed.add(source);
                candidates.addAll(result);
                baseItems.clear();
                if (page == Page.HOME && !history.isEmpty() && activeChannel == null) {
                    baseItems.addAll(RecommendationEngine.rank(candidates, history));
                } else {
                    Map<String, VideoItem> unique = new LinkedHashMap<>();
                    for (VideoItem item : candidates) unique.put(item.stableKey(), item);
                    baseItems.addAll(unique.values());
                }
                if (page == Page.POPULAR) Collections.sort(baseItems,
                        (a, b) -> Long.compare(b.views, a.views));
                applySort();
                render();
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
                    list.setSelection(0);
                    render();
                    dialog.dismiss();
                }).setNegativeButton("Отмена", null).show();
    }

    private void updateSortLabel() {
        sortButton.setText(sortMode.label + "  ▾");
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
        toolbar.setVisibility(landscape ? View.GONE : View.VISIBLE);
        landscapeControls.setVisibility(landscape ? View.VISIBLE : View.GONE);
        searchRow.setVisibility(!landscape && page == Page.SEARCH ? View.VISIBLE : View.GONE);
        heading.setVisibility(landscape ? View.GONE : View.VISIBLE);
        topics.setVisibility(!landscape && activeChannel == null
                && (page == Page.HOME || page == Page.SEARCH || page == Page.POPULAR)
                ? View.VISIBLE : View.GONE);
        actions.setVisibility(!landscape && actions.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        sortRow.setVisibility(!landscape && (page != Page.CHANNELS || activeChannel != null)
                ? View.VISIBLE : View.GONE);
        status.setVisibility(landscape ? View.GONE : View.VISIBLE);
        navigation.setVisibility(landscape ? View.GONE : View.VISIBLE);
        list.setNumColumns(landscape ? 2 : 1);
        list.setHorizontalSpacing(dp(landscape ? 10 : 0));
        adapter.notifyDataSetChanged();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        int first = list.getFirstVisiblePosition();
        super.onConfigurationChanged(configuration);
        skipResumeReload = true;
        if (!landscapeQuery.hasFocus()) landscapeQuery.setText(query.getText());
        applyLayout();
        list.setSelection(first);
    }

    private void applySort() {
        items.clear();
        items.addAll(baseItems);
        if (page != Page.CHANNELS || activeChannel != null) VideoSort.apply(items, sortMode);
    }

    private void render() {
        adapter.notifyDataSetChanged();
        status.setText((pending > 0 ? "Загрузка…  ·  " : "") + "Видео: " + items.size()
                + (failed.isEmpty() ? "" : "  ·  Недоступны: " + TextUtils.join(", ", failed)));
        empty.setText(pending > 0 ? "Ищем видео…"
                : page == Page.SEARCH && searchQuery.isEmpty() ? "Введите запрос для поиска видео"
                : page == Page.SUBSCRIPTIONS ? "Подпишитесь на канал через меню видео"
                : page == Page.LIVE ? "Сейчас нет подтверждённых эфиров"
                : page == Page.FAVORITES ? "Сохранённые видео появятся здесь"
                : page == Page.LIBRARY || page == Page.HISTORY ? "История просмотра пока пуста"
                : page == Page.CHANNELS ? "Подписок пока нет" : "Видео пока не найдены");
    }

    private void updateNavigation() {
        navigation.removeAllViews();
        Page selected = page == Page.SEARCH || page == Page.POPULAR ? Page.HOME
                : page == Page.HISTORY || page == Page.FAVORITES || page == Page.CHANNELS ? Page.LIBRARY : page;
        for (int i = 0; i < TABS.length; i++) {
            Page target = TABS[i];
            boolean active = selected == target;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            ImageView image = new ImageView(this);
            image.setImageDrawable(TvIcons.icon(TAB_ICONS[i], active ? ACCENT : SECONDARY, dp(23)));
            tab.addView(image, size(24, 24));
            TextView name = label(TAB_LABELS[i], 11, active ? Color.WHITE : SECONDARY);
            name.setGravity(Gravity.CENTER);
            tab.addView(name, size(-1, 22));
            navigation.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
            tab.setOnClickListener(v -> show(target));
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
        TextView button = label("", 20, Color.WHITE);
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
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        return drawable;
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
        if (firstResume) { firstResume = false; return; }
        if (skipResumeReload) { skipResumeReload = false; render(); return; }
        if (activeChannel != null) showChannel(activeChannel);
        else if (page == Page.HOME || page == Page.HISTORY || page == Page.LIBRARY
                || page == Page.FAVORITES || page == Page.SUBSCRIPTIONS || page == Page.CHANNELS) show(page);
        else render();
    }

    @Override protected void onPause() {
        updates.onPause();
        super.onPause();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updates.onActivityResult(requestCode);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("page", page.name()); state.putString("query", searchQuery);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (activeChannel != null) { show(page); return; }
        if (page == Page.HISTORY || page == Page.FAVORITES || page == Page.CHANNELS) {
            show(Page.LIBRARY); return;
        }
        if (page != Page.HOME) { show(Page.HOME); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        updates.onDestroy(); cancel(); io.shutdownNow(); super.onDestroy();
    }

    private interface Load { List<VideoItem> run() throws Exception; }

    private final class FeedAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public VideoItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return getItem(position).stableKey().hashCode(); }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Card card;
            if (recycled == null) {
                LinearLayout layout = new LinearLayout(MobileBrowseActivity.this);
                layout.setOrientation(LinearLayout.VERTICAL);
                FrameLayout artwork = new FrameLayout(MobileBrowseActivity.this) {
                    @Override protected void onMeasure(int widthSpec, int heightSpec) {
                        int width = View.MeasureSpec.getSize(widthSpec);
                        super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                                Math.round(width * 9f / 16f), View.MeasureSpec.EXACTLY));
                    }
                };
                artwork.setBackgroundColor(PANEL);
                ImageView image = new ImageView(MobileBrowseActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                artwork.addView(image, new FrameLayout.LayoutParams(-1, -1));
                TextView source = label("", 11, Color.WHITE);
                source.setPadding(dp(7), 0, dp(7), 0); source.setBackground(round(0xdd171a21, 4));
                FrameLayout.LayoutParams sourceParams = new FrameLayout.LayoutParams(-2, dp(22), Gravity.TOP | Gravity.START);
                sourceParams.setMargins(dp(10), dp(10), 0, 0); artwork.addView(source, sourceParams);
                TextView duration = label("", 11, Color.WHITE);
                duration.setPadding(dp(6), 0, dp(6), 0); duration.setBackground(round(0xee101216, 4));
                FrameLayout.LayoutParams durationParams = new FrameLayout.LayoutParams(-2, dp(22), Gravity.BOTTOM | Gravity.END);
                durationParams.setMargins(0, 0, dp(10), dp(10)); artwork.addView(duration, durationParams);
                ProgressBar progress = new ProgressBar(MobileBrowseActivity.this, null,
                        android.R.attr.progressBarStyleHorizontal);
                progress.setMax(1000);
                progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
                progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
                artwork.addView(progress, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));
                layout.addView(artwork, size(-1, -2));

                LinearLayout info = new LinearLayout(MobileBrowseActivity.this);
                info.setPadding(dp(12), dp(11), dp(10), dp(14));
                TextView avatar = label("", 17, Color.WHITE);
                avatar.setTypeface(Typeface.DEFAULT_BOLD); avatar.setGravity(Gravity.CENTER);
                avatar.setBackground(round(0xff33465a, 22));
                info.addView(avatar, size(42, 42));
                LinearLayout text = new LinearLayout(MobileBrowseActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);
                text.setPadding(dp(11), 0, dp(4), 0);
                TextView title = label("", 15, Color.WHITE);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
                text.addView(title, size(-1, -2));
                TextView meta = label("", 12, SECONDARY);
                meta.setMaxLines(2); meta.setEllipsize(TextUtils.TruncateAt.END);
                text.addView(meta, size(-1, -2));
                info.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
                TextView more = label("⋮", 25, Color.WHITE);
                more.setGravity(Gravity.CENTER);
                more.setContentDescription("Действия с видео");
                info.addView(more, size(38, 42));
                layout.addView(info, size(-1, -2));
                layout.setTag(card = new Card(image, source, duration, progress, avatar, title, meta, more));
                recycled = layout;
            } else card = (Card) recycled.getTag();
            VideoItem item = getItem(position);
            card.source.setText(item.isLive ? "● В ЭФИРЕ · " + item.source : item.source);
            card.duration.setText(item.isLive ? "В ЭФИРЕ" : VideoPresentation.duration(item.durationMs));
            card.duration.setVisibility(item.isLive || item.durationMs > 0 ? View.VISIBLE : View.GONE);
            card.title.setText(page == Page.CHANNELS ? VideoPresentation.channel(item) : item.title);
            String channel = VideoPresentation.channel(item);
            card.avatar.setText(channel.isEmpty() ? "?" : channel.substring(0, 1).toUpperCase());
            List<String> details = new ArrayList<>();
            details.add(item.source + " · " + channel);
            if (!VideoPresentation.views(item).isEmpty()) details.add(VideoPresentation.views(item));
            if (!VideoPresentation.published(item).isEmpty()) details.add(VideoPresentation.published(item));
            card.meta.setText(TextUtils.join(" · ", details));
            WatchProgressStore.Progress watched = WatchProgressStore.get(MobileBrowseActivity.this, item);
            long total = watched.durationMs > 0 ? watched.durationMs : item.durationMs;
            card.progress.setVisibility(watched.positionMs > 0 && total > 0 ? View.VISIBLE : View.GONE);
            if (total > 0) card.progress.setProgress((int) Math.min(1000, watched.positionMs * 1000 / total));
            card.more.setOnClickListener(v -> VideoActions.show(MobileBrowseActivity.this, item,
                    () -> showChannel(item)));
            ((TubeApplication) getApplication()).imageLoader().load(item.thumbnail, card.image);
            return recycled;
        }
    }

    private static final class Card {
        final ImageView image;
        final TextView source, duration, avatar, title, meta, more;
        final ProgressBar progress;
        Card(ImageView image, TextView source, TextView duration, ProgressBar progress,
             TextView avatar, TextView title, TextView meta, TextView more) {
            this.image = image; this.source = source; this.duration = duration;
            this.progress = progress; this.avatar = avatar; this.title = title;
            this.meta = meta; this.more = more;
        }
    }
}
