package ru.tubetv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.Proxy;

/** Category dashboard following the TV settings navigation used by PeerTubeTV. */
public final class TvSettingsActivity extends Activity {
    private static final String[] CATEGORIES = {
            "Общие", "Интерфейс", "Плеер", "Поиск", "Сеть", "О программе"
    };
    private static final String[] SYMBOLS = {"⚙", "▦", "▶", "⌕", "◎", "ℹ"};
    private static final int PANEL = 0xff171a21;
    private static final int SECONDARY = 0xffaeb9cc;
    private FrameLayout screen;
    private LinearLayout dashboardRoot;
    private View shade;
    private LinearLayout drawer;
    private LinearLayout drawerContent;
    private GridView grid;
    private int category = -1;
    private int drawerWidth;
    private SideSelectionPanel selectionPanel;
    private View selectionOrigin;
    private EditText proxyHost, proxyPort, proxyUser, proxyPassword;
    private TextView proxyStatus;
    private String proxyType;
    private String draftHost, draftPort, draftUser, draftPassword;
    private UpdateCoordinator updates;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        updates = new UpdateCoordinator(this);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        dashboardRoot = new LinearLayout(this);
        LinearLayout root = dashboardRoot;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0b0f14);
        root.setPadding(dp(48), dp(28), dp(48), dp(24));
        TextView title = new TextView(this);
        title.setText("Настройки"); title.setTextSize(30 * TvScalePreferences.get(this, TvScalePreferences.INTERFACE)); title.setTextColor(Color.WHITE);
        root.addView(title);
        TextView hint = new TextView(this);
        hint.setText("Выберите раздел · Назад — вернуться в каталог");
        hint.setTextSize(14 * TvScalePreferences.get(this, TvScalePreferences.INTERFACE)); hint.setTextColor(0xffaeb9cc);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, dp(40));
        hintParams.bottomMargin = dp(16);
        root.addView(hint, hintParams);
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(16));
        grid.setVerticalSpacing(dp(16));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(android.R.color.transparent);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setFocusable(true);
        grid.setOnFocusChangeListener((view, focused) ->
                updateCategorySelection(focused ? grid.getSelectedItemPosition() : -1));
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return CATEGORIES.length; }
            @Override public Object getItem(int position) { return CATEGORIES[position]; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View reused, ViewGroup parent) {
                LinearLayout tile = new LinearLayout(TvSettingsActivity.this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                // GridView owns D-pad selection; focusable children swallow its key events.
                tile.setFocusable(false);
                tile.setBackground(tileBackground(false));
                TextView symbol = new TextView(TvSettingsActivity.this);
                symbol.setText(SYMBOLS[position]); symbol.setTextSize(34 * TvScalePreferences.get(TvSettingsActivity.this, TvScalePreferences.INTERFACE));
                symbol.setTextColor(0xffe53935); symbol.setGravity(Gravity.CENTER);
                tile.addView(symbol, new LinearLayout.LayoutParams(-1, dp(54)));
                TextView label = new TextView(TvSettingsActivity.this);
                label.setText(CATEGORIES[position]); label.setTextSize(19 * TvScalePreferences.get(TvSettingsActivity.this, TvScalePreferences.INTERFACE));
                label.setTextColor(Color.WHITE); label.setGravity(Gravity.CENTER);
                tile.addView(label, new LinearLayout.LayoutParams(-1, dp(48)));
                tile.setOnClickListener(v -> openCategory(position));
                return tile;
            }
        });
        grid.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int position, long id) {
                updateCategorySelection(position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
                updateCategorySelection(-1);
            }
        });
        grid.setOnItemClickListener((parent, view, position, id) -> openCategory(position));
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        screen = new FrameLayout(this);
        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        shade = new View(this);
        shade.setBackgroundColor(0x99000000);
        shade.setVisibility(View.GONE);
        shade.setOnClickListener(v -> closePanel());
        shade.setFocusable(false);
        screen.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        drawerWidth = Math.min(dp(520), Math.round(getResources().getDisplayMetrics().widthPixels * .48f));
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(PANEL);
        drawer.setElevation(dp(16));
        drawer.setVisibility(View.GONE);
        screen.addView(drawer, new FrameLayout.LayoutParams(drawerWidth, -1, Gravity.RIGHT));
        setContentView(screen);
        grid.setSelection(0);
        grid.requestFocus();
        grid.post(() -> {
            if (category < 0) {
                grid.setSelection(0);
                grid.requestFocus();
                updateCategorySelection(0);
            }
        });
        if (state != null) {
            int restored = state.getInt("category", -1);
            if (restored >= 0 && restored < CATEGORIES.length)
                screen.post(() -> openCategory(restored));
        }
    }

    private GradientDrawable tileBackground(boolean focused) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(focused ? Color.WHITE : 0xff171a21);
        result.setCornerRadius(dp(14));
        return result;
    }

    private void updateCategorySelection(int selected) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            LinearLayout tile = (LinearLayout) grid.getChildAt(i);
            boolean active = grid.hasFocus() && grid.getFirstVisiblePosition() + i == selected;
            tile.setBackground(tileBackground(active));
            ((TextView) tile.getChildAt(1)).setTextColor(active ? Color.BLACK : Color.WHITE);
        }
    }

    private void openCategory(int selected) {
        category = selected;
        if (selected == 4) loadProxyDraft();
        shade.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        dashboardRoot.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setFocusable(false);
        renderPanel(0);
        drawer.post(() -> {
            if (category == selected) focusRow(0);
        });
        drawer.setTranslationX(drawerWidth);
        drawer.animate().translationX(0).setDuration(180).start();
    }

    private void renderPanel(int focusIndex) {
        drawer.removeAllViews();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(22), dp(10), dp(16), dp(10));
        TextView title = text(CATEGORIES[category], 26, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView close = text("‹", 36, Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setFocusable(true);
        close.setBackground(tileBackground(false));
        close.setOnClickListener(v -> closePanel());
        header.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
        drawer.addView(header);
        View divider = new View(this);
        divider.setBackgroundColor(0xff343a45);
        drawer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        ScrollView scroll = new ScrollView(this);
        drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);
        drawerContent.setPadding(dp(22), dp(18), dp(22), dp(28));
        scroll.addView(drawerContent);
        drawer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (category) {
            case 0: general(); break;
            case 1: interfaceSettings(); break;
            case 2: playerSettings(); break;
            case 3: searchSettings(); break;
            case 4: networkSettings(); break;
            default: about(); break;
        }
        focusRow(focusIndex);
    }

    private void focusRow(int focusIndex) {
        View focus = null;
        int row = 0;
        for (int i = 0; i < drawerContent.getChildCount(); i++) {
            View child = drawerContent.getChildAt(i);
            if (!child.isFocusable()) continue;
            if (row++ == focusIndex) { focus = child; break; }
        }
        if (focus != null) focus.requestFocus();
        else ((LinearLayout) drawer.getChildAt(0)).getChildAt(1).requestFocus();
    }

    private void general() {
        boolean enabled = getSharedPreferences("browse", 0)
                .getBoolean("double_back_exit", true);
        section("Выход из приложения");
        row("Выход двумя нажатиями Назад", enabled ? "Включено" : "Выключено", () -> {
            getSharedPreferences("browse", 0).edit()
                    .putBoolean("double_back_exit", !enabled).apply();
            renderPanel(0);
        });
        note("Первое нажатие открывает меню, второе завершает приложение.");
        section("Рекомендации");
        row("Вернуть недоступные видео", String.valueOf(UnavailableVideoStore.count(this)), () ->
                new AlertDialog.Builder(this).setTitle("Вернуть видео в каталог?")
                        .setMessage("Ранее неработающие видео снова появятся в рекомендациях и поиске.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Вернуть", (dialog, which) -> {
                            UnavailableVideoStore.clear(this);
                            renderPanel(1);
                        }).show());
    }

    private void interfaceSettings() {
        section("Размеры");
        row("Масштаб интерфейса", TvScalePreferences.label(
                TvScalePreferences.getStep(this, TvScalePreferences.INTERFACE)),
                () -> openScale("Масштаб интерфейса", TvScalePreferences.INTERFACE));
        row("Масштаб сетки видео", TvScalePreferences.label(
                TvScalePreferences.getStep(this, TvScalePreferences.GRID)),
                () -> openScale("Масштаб сетки видео", TvScalePreferences.GRID));
        section("Порядок видео");
        VideoSort.Mode current = VideoSort.parse(getSharedPreferences("browse", 0)
                .getString("video_sort", VideoSort.Mode.NEWEST.name()));
        VideoSort.Mode[] modes = VideoSort.Mode.values();
        String[] sortLabels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) sortLabels[i] = modes[i].label;
        row("Порядок видео", current.label, () -> openChoices("Порядок видео", sortLabels,
                current.ordinal(), 2, choice -> getSharedPreferences("browse", 0).edit()
                        .putString("video_sort", modes[choice].name()).apply()));
        note("Выбор применяется к ленте после возврата в каталог.");
    }

    private void openScale(String title, String key) {
        selectionOrigin = getCurrentFocus();
        String[] labels = new String[TvScalePreferences.LAST_STEP + 1];
        for (int i = 0; i < labels.length; i++) labels[i] = TvScalePreferences.label(i);
        selectionPanel = new SideSelectionPanel(this, title, labels,
                TvScalePreferences.getStep(this, key), selected -> {
                    TvScalePreferences.setStep(this, key, selected);
                    closeSelection();
                    renderPanel(TvScalePreferences.GRID.equals(key) ? 1 : 0);
                });
        int width = Math.min(dp(390), Math.round(getResources().getDisplayMetrics().widthPixels * .42f));
        screen.addView(selectionPanel, new FrameLayout.LayoutParams(width, -1, Gravity.RIGHT));
        selectionPanel.setTranslationX(width);
        selectionPanel.animate().translationX(0).setDuration(180).start();
    }

    private void closeSelection() {
        if (selectionPanel == null) return;
        SideSelectionPanel panel = selectionPanel;
        selectionPanel = null;
        panel.animate().translationX(panel.getWidth()).setDuration(160)
                .withEndAction(() -> screen.removeView(panel)).start();
        if (selectionOrigin != null && selectionOrigin.isAttachedToWindow())
            selectionOrigin.requestFocus();
        selectionOrigin = null;
    }

    private void playerSettings() {
        section("Воспроизведение");
        float[] speeds = {.75f, 1f, 1.25f, 1.5f, 2f};
        String[] labels = {"0,75×", "1×", "1,25×", "1,5×", "2×"};
        int speedIndex = 1;
        for (int i = 0; i < speeds.length; i++)
            if (Math.abs(StateStore.playbackSpeed(this) - speeds[i]) < .01f) speedIndex = i;
        int selectedSpeed = speedIndex;
        row("Скорость по умолчанию", labels[speedIndex], () ->
                openChoices("Скорость по умолчанию", labels, selectedSpeed, 0,
                        choice -> StateStore.savePlaybackSpeed(this, speeds[choice])));
        section("Часы");
        boolean clock = TvPlayerPreferences.showClock(this);
        row("Время в правом верхнем углу", clock ? "Включено" : "Выключено", () -> {
            TvPlayerPreferences.setShowClock(this, !clock);
            renderPanel(1);
        });
        row("Размер часов", TvPlayerPreferences.CLOCK_SIZES[TvPlayerPreferences.clockSize(this)],
                () -> openChoices("Размер часов", TvPlayerPreferences.CLOCK_SIZES,
                        TvPlayerPreferences.clockSize(this), 2,
                        choice -> TvPlayerPreferences.setClockSize(this, choice)));
        section("Субтитры");
        row("Язык", CaptionPreferences.LANGUAGES[CaptionPreferences.language(this)],
                () -> openChoices("Язык субтитров", CaptionPreferences.LANGUAGES,
                        CaptionPreferences.language(this), 3,
                        choice -> CaptionPreferences.setLanguage(this, choice)));
        row("Размер", CaptionPreferences.SIZES[CaptionPreferences.size(this)],
                () -> openChoices("Размер субтитров", CaptionPreferences.SIZES,
                        CaptionPreferences.size(this), 4,
                        choice -> CaptionPreferences.setSize(this, choice)));
        row("Цвет", CaptionPreferences.COLORS[CaptionPreferences.color(this)],
                () -> openChoices("Цвет субтитров", CaptionPreferences.COLORS,
                        CaptionPreferences.color(this), 5,
                        choice -> CaptionPreferences.setColor(this, choice)));
    }

    private interface ChoiceAction { void apply(int choice); }

    private void openChoices(String title, String[] labels, int selected, int focusRow,
                             ChoiceAction action) {
        selectionOrigin = getCurrentFocus();
        selectionPanel = new SideSelectionPanel(this, title, labels, selected, choice -> {
            action.apply(choice);
            closeSelection();
            renderPanel(focusRow);
        });
        int width = Math.min(dp(390), Math.round(getResources().getDisplayMetrics().widthPixels * .42f));
        screen.addView(selectionPanel, new FrameLayout.LayoutParams(width, -1, Gravity.RIGHT));
        selectionPanel.setTranslationX(width);
        selectionPanel.animate().translationX(0).setDuration(180).start();
    }

    private void searchSettings() {
        boolean enabled = getSharedPreferences("browse", 0)
                .getBoolean("recommendations", true);
        section("Рекомендации");
        row("По истории просмотров", enabled ? "Включено" : "Выключено", () -> {
            getSharedPreferences("browse", 0).edit()
                    .putBoolean("recommendations", !enabled).apply();
            renderPanel(0);
        });
        boolean subscriptions = getSharedPreferences("browse", 0)
                .getBoolean("recommend_subscriptions", true);
        row("По подпискам", subscriptions ? "Включено" : "Выключено", () -> {
            getSharedPreferences("browse", 0).edit()
                    .putBoolean("recommend_subscriptions", !subscriptions).apply();
            renderPanel(1);
        });
        note("История остаётся на устройстве. Поиск тем выполняется на видеоплатформах.");
    }

    private void networkSettings() {
        section("Прокси");
        note("Для поиска, обложек и видеопотоков HTTPS. Доступны HTTP и SOCKS5; UDP не используется.");
        String[] types = {"off", "http", "socks5"};
        String[] names = {"Без прокси", "HTTP", "SOCKS5"};
        int selectedType = 0;
        for (int i = 0; i < types.length; i++)
            if (types[i].equals(proxyType)) selectedType = i;
        int currentType = selectedType;
        row("Режим прокси", names[selectedType], () -> openChoices("Режим прокси",
                names, currentType, 0, choice -> {
                    captureProxyDraft();
                    proxyType = types[choice];
                }));
        section("Сервер");
        proxyHost = field("Адрес", draftHost, InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        proxyPort = field("Порт", draftPort, InputType.TYPE_CLASS_NUMBER);
        proxyUser = field("Логин (необязательно)", draftUser, InputType.TYPE_CLASS_TEXT);
        proxyPassword = field("Пароль (необязательно)", draftPassword,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        row("Сохранить прокси", "✓", this::saveProxy);
        row("Сохранить и проверить соединение", "↻", this::checkProxy);
        proxyStatus = text("", 14, SECONDARY);
        proxyStatus.setPadding(0, dp(8), 0, dp(12));
        drawerContent.addView(proxyStatus);
    }

    private void loadProxyDraft() {
        NetworkProxy.Config saved = NetworkProxy.load(this);
        proxyType = saved.type == Proxy.Type.HTTP ? "http"
                : saved.type == Proxy.Type.SOCKS ? "socks5" : "off";
        draftHost = saved.host;
        draftPort = saved.port == 0 ? "" : Integer.toString(saved.port);
        draftUser = saved.user;
        draftPassword = saved.password;
    }

    private void captureProxyDraft() {
        if (proxyHost == null) return;
        draftHost = proxyHost.getText().toString().trim();
        draftPort = proxyPort.getText().toString().trim();
        draftUser = proxyUser.getText().toString();
        draftPassword = proxyPassword.getText().toString();
    }

    private void saveProxy() {
        validateAndSaveProxy(true);
    }

    private boolean validateAndSaveProxy(boolean notify) {
        captureProxyDraft();
        int port = 0;
        try { port = Integer.parseInt(draftPort); }
        catch (NumberFormatException ignored) { }
        if (!"off".equals(proxyType) && draftHost.isEmpty()) {
            proxyHost.setError("Введите адрес прокси"); proxyHost.requestFocus(); return false;
        }
        if (!"off".equals(proxyType) && (port < 1 || port > 65535)) {
            proxyPort.setError("Порт должен быть от 1 до 65535"); proxyPort.requestFocus(); return false;
        }
        NetworkProxy.save(this, proxyType, draftHost, port, draftUser, draftPassword);
        if (notify) Toast.makeText(this, "Настройки сети сохранены", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void checkProxy() {
        if (!validateAndSaveProxy(false)) return;
        proxyStatus.setText("Проверяем соединение…");
        new Thread(() -> {
            String result;
            HttpURLConnection connection = null;
            try {
                connection = NetworkProxy.openConnection("https://example.com/");
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                int code = connection.getResponseCode();
                result = code >= 200 && code < 400 ? "Соединение работает"
                        : "Прокси ответил кодом " + code;
            } catch (Exception error) {
                String detail = error.getMessage();
                result = "Соединение не удалось: " + error.getClass().getSimpleName()
                        + (detail == null || detail.isEmpty() ? "" : " — " + detail);
            } finally {
                if (connection != null) connection.disconnect();
            }
            String message = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || proxyStatus == null) return;
                proxyStatus.setText(message);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        }, "tv-proxy-check").start();
    }

    private void about() {
        section("RussTubeTV " + BuildConfig.VERSION_NAME);
        note("Поиск видео на RUTUBE, VK Видео, OK, Дзене и PeerTube.");
        note("История и подписки хранятся на устройстве.");
        note("Artic Fox app, California. 2026.");
        row("Проверить обновление", "↻", updates::checkNow);
    }

    private void section(String value) {
        TextView title = text(value, 18, Color.WHITE);
        title.setPadding(0, dp(10), 0, dp(10));
        drawerContent.addView(title);
    }

    private void note(String value) {
        TextView title = text(value, 14, SECONDARY);
        title.setPadding(dp(3), dp(6), dp(3), dp(16));
        drawerContent.addView(title);
    }

    private void row(String title, String value, Runnable action) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(14), dp(8), dp(14), dp(8));
        line.setFocusable(true);
        line.setFocusableInTouchMode(true);
        line.setBackground(tileBackground(false));
        TextView name = text(title, 17, Color.WHITE);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView state = text(value, 15, 0xffe53935);
        state.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        line.addView(state);
        line.setOnFocusChangeListener((v, focused) -> {
            line.setBackground(tileBackground(focused));
            name.setTextColor(focused ? Color.BLACK : Color.WHITE);
            state.setTextColor(focused ? Color.BLACK : 0xffe53935);
        });
        line.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(58));
        params.bottomMargin = dp(8);
        drawerContent.addView(line, params);
    }

    private EditText field(String hint, String value, int type) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(SECONDARY);
        input.setHint(hint);
        input.setInputType(type);
        input.setText(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(56));
        params.bottomMargin = dp(8);
        drawerContent.addView(input, params);
        return input;
    }

    private TextView text(String value, int size, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size * TvScalePreferences.get(this, TvScalePreferences.INTERFACE));
        result.setTextColor(color);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private void closePanel() {
        if (category < 0) return;
        int selected = category;
        drawer.animate().translationX(drawerWidth).setDuration(160).withEndAction(() -> {
            if (category < 0) {
                drawer.setVisibility(View.GONE);
                shade.setVisibility(View.GONE);
            }
        }).start();
        category = -1;
        dashboardRoot.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        grid.setFocusable(true);
        grid.setSelection(selected);
        grid.requestFocus();
        grid.post(() -> updateCategorySelection(grid.getSelectedItemPosition()));
    }

    @Override public void onBackPressed() {
        if (selectionPanel != null) closeSelection();
        else if (category >= 0) closePanel();
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        updates.onResume();
    }

    @Override protected void onPause() {
        updates.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        updates.onDestroy();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updates.onActivityResult(requestCode);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("category", category);
        super.onSaveInstanceState(state);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density
            * TvScalePreferences.get(this, TvScalePreferences.INTERFACE)); }
}
