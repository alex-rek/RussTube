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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.Proxy;

/** TV settings with persistent categories and a focused parameter column. */
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
    private LinearLayout categoryRail;
    private final TextView[] categoryRows = new TextView[CATEGORIES.length];
    private int category = -1;
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
        dashboardRoot.setOrientation(LinearLayout.VERTICAL);
        dashboardRoot.setBackgroundColor(TvAppearancePreferences.background(this));
        dashboardRoot.setPadding(dp(24), dp(12), dp(24), dp(12));
        LinearLayout top = new LinearLayout(this);
        TextView title = text("Настройки", 24, Color.WHITE);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.tv_banner);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setContentDescription("RussTubeTV");
        top.addView(logo, new LinearLayout.LayoutParams(dp(220), dp(48)));
        dashboardRoot.addView(top);
        LinearLayout columns = new LinearLayout(this);
        categoryRail = new LinearLayout(this);
        categoryRail.setOrientation(LinearLayout.VERTICAL);
        categoryRail.setBackgroundColor(TvAppearancePreferences.surface(this));
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int index = i;
            TextView row = text(SYMBOLS[i] + "   " + CATEGORIES[i], 16, Color.WHITE);
            row.setPadding(dp(12), dp(6), dp(8), dp(6));
            row.setMinHeight(dp(48));
            row.setFocusable(true);
            row.setOnFocusChangeListener((v, focused) -> updateCategorySelection());
            row.setOnClickListener(v -> openCategory(index));
            categoryRows[i] = row;
            categoryRail.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView categoryScroll = new ScrollView(this);
        categoryScroll.setFillViewport(true);
        categoryScroll.addView(categoryRail);
        columns.addView(categoryScroll, new LinearLayout.LayoutParams(Math.min(dp(220),
                Math.round(getResources().getDisplayMetrics().widthPixels * .23f)), -1));
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(TvAppearancePreferences.surface(this));
        LinearLayout.LayoutParams drawerParams = new LinearLayout.LayoutParams(0, -1, 1);
        drawerParams.leftMargin = dp(14);
        columns.addView(drawer, drawerParams);
        dashboardRoot.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
        screen = new FrameLayout(this);
        screen.addView(dashboardRoot, new FrameLayout.LayoutParams(-1, -1));
        shade = new View(this);
        shade.setBackgroundColor(0x99000000);
        shade.setVisibility(View.GONE);
        shade.setOnClickListener(v -> closeSelection());
        shade.setFocusable(false);
        screen.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
        openCategory(state == null ? 0 : Math.max(0, Math.min(CATEGORIES.length - 1,
                state.getInt("category", 0))));
        categoryRows[category].requestFocus();
        if (state != null) {
            // A pending selection is deliberately discarded; saved values remain intact.
        }
    }

    private int rowBackground(boolean focused) {
        return focused ? TvAppearancePreferences.focus(this) : TvAppearancePreferences.surface(this);
    }

    private void updateCategorySelection() {
        for (int i = 0; i < categoryRows.length; i++) {
            TextView row = categoryRows[i];
            boolean focused = row.hasFocus();
            row.setBackgroundColor(rowBackground(focused));
            row.setTextColor(i == category ? TvAppearancePreferences.accent(this)
                    : TvAppearancePreferences.text(this));
        }
    }

    private void openCategory(int selected) {
        category = selected;
        if (selected == 4) loadProxyDraft();
        renderPanel(0);
        updateCategorySelection();
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
        close.setBackgroundColor(rowBackground(false));
        close.setOnClickListener(v -> categoryRows[category].requestFocus());
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
        section("Оформление");
        int theme = TvAppearancePreferences.theme(this);
        row("Тема", TvAppearancePreferences.THEMES[theme] + "  ›", () ->
                openChoices("Тема", TvAppearancePreferences.THEMES, theme, 0, choice -> {
                    TvAppearancePreferences.setTheme(this, choice);
                    screen.post(this::recreate);
                }));
        boolean symbols = TvAppearancePreferences.symbols(this);
        row("Фоновая символика", symbols ? "Включено" : "Выключено", () -> {
            TvAppearancePreferences.setSymbols(this, !symbols);
            renderPanel(1);
        });
        boolean animations = TvAppearancePreferences.animations(this);
        row("Анимации", animations ? "Включены" : "Выключены", () -> {
            TvAppearancePreferences.setAnimations(this, !animations);
            renderPanel(2);
        });
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
                current.ordinal(), 5, choice -> getSharedPreferences("browse", 0).edit()
                        .putString("video_sort", modes[choice].name()).apply()));
        note("Выбор применяется к ленте после возврата в каталог.");
    }

    private void openScale(String title, String key) {
        if (selectionPanel != null) return;
        selectionOrigin = getCurrentFocus();
        String[] labels = new String[TvScalePreferences.LAST_STEP + 1];
        for (int i = 0; i < labels.length; i++) labels[i] = TvScalePreferences.label(i);
        selectionPanel = new SideSelectionPanel(this, title, labels,
                TvScalePreferences.getStep(this, key), selected -> {
                    TvScalePreferences.setStep(this, key, selected);
                    closeSelection();
                    renderPanel(TvScalePreferences.GRID.equals(key) ? 4 : 3);
                });
        showSelection();
    }

    private void closeSelection() {
        if (selectionPanel == null) return;
        SideSelectionPanel panel = selectionPanel;
        selectionPanel = null;
        screen.removeView(panel);
        shade.setVisibility(View.GONE);
        dashboardRoot.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (selectionOrigin != null && selectionOrigin.isAttachedToWindow())
            selectionOrigin.requestFocus();
        selectionOrigin = null;
    }

    private void showSelection() {
        shade.setVisibility(View.VISIBLE);
        dashboardRoot.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        int width = Math.min(dp(500), Math.round(getResources().getDisplayMetrics().widthPixels * .47f));
        screen.addView(selectionPanel, new FrameLayout.LayoutParams(width, -1, Gravity.RIGHT));
        if (TvAppearancePreferences.animations(this)) {
            selectionPanel.setTranslationX(width);
            selectionPanel.animate().translationX(0).setDuration(180).start();
        }
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
        if (selectionPanel != null) return;
        selectionOrigin = getCurrentFocus();
        selectionPanel = new SideSelectionPanel(this, title, labels, selected, choice -> {
            action.apply(choice);
            closeSelection();
            renderPanel(focusRow);
        });
        showSelection();
    }

    private void searchSettings() {
        section("Платформы для поиска и эфиров");
        int selected = MobileBrowsePreferences.enabledSources(this, false).length;
        row("Платформы", selected + " выбрано  ›", this::openPlatforms);
        boolean enabled = getSharedPreferences("browse", 0)
                .getBoolean("recommendations", true);
        section("Рекомендации");
        row("По истории просмотров", enabled ? "Включено" : "Выключено", () -> {
            getSharedPreferences("browse", 0).edit()
                    .putBoolean("recommendations", !enabled).apply();
            renderPanel(1);
        });
        boolean subscriptions = getSharedPreferences("browse", 0)
                .getBoolean("recommend_subscriptions", true);
        row("По подпискам", subscriptions ? "Включено" : "Выключено", () -> {
            getSharedPreferences("browse", 0).edit()
                    .putBoolean("recommend_subscriptions", !subscriptions).apply();
            renderPanel(2);
        });
        note("История остаётся на устройстве. Поиск тем выполняется на видеоплатформах.");
    }

    private void openPlatforms() {
        if (selectionPanel != null) return;
        String[] names = {"RUTUBE", "VK Видео", "OK", "Дзен", "PeerTube"};
        boolean[] saved = new boolean[FeedRepository.SOURCES.length];
        for (int i = 0; i < saved.length; i++)
            saved[i] = MobileBrowsePreferences.sourceEnabled(this, FeedRepository.SOURCES[i]);
        selectionOrigin = getCurrentFocus();
        selectionPanel = new SideSelectionPanel(this, "Платформы", names, saved, selected -> {
            if (!MobileBrowsePreferences.setSources(this, selected)) {
                Toast.makeText(this, "Выберите хотя бы одну платформу", Toast.LENGTH_LONG).show();
                return;
            }
            closeSelection();
            renderPanel(0);
        }, this::closeSelection);
        showSelection();
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
        section("Площадки через прокси");
        note("Отмеченные площадки используют прокси для поиска, обложек и видео. Остальные — напрямую.");
        for (int i = 0; i < NetworkProxy.SOURCES.length; i++) {
            String source = NetworkProxy.SOURCES[i];
            boolean enabled = NetworkProxy.isSourceEnabled(this, source);
            int focusRow = i + 5; // Mode row followed by four editable server fields.
            row(NetworkProxy.SOURCE_LABELS[i], enabled ? "☑" : "☐", () -> {
                captureProxyDraft();
                NetworkProxy.setSourceEnabled(this, source, !enabled);
                renderPanel(focusRow);
            });
        }
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
        line.setBackgroundColor(rowBackground(false));
        TextView name = text(title, 17, Color.WHITE);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView state = text(value, 15, TvAppearancePreferences.accent(this));
        state.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        line.addView(state);
        line.setOnFocusChangeListener((v, focused) -> {
            line.setBackgroundColor(rowBackground(focused));
            name.setTextColor(TvAppearancePreferences.text(this));
            state.setTextColor(TvAppearancePreferences.accent(this));
        });
        line.setOnClickListener(v -> action.run());
        line.setMinimumHeight(dp(58));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(3);
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
        result.setTextColor(color == Color.WHITE ? TvAppearancePreferences.text(this)
                : color == SECONDARY ? TvAppearancePreferences.secondary(this) : color);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private void closePanel() {
        if (category >= 0) categoryRows[category].requestFocus();
    }

    @Override public void onBackPressed() {
        if (selectionPanel != null) closeSelection();
        else if (getCurrentFocus() != null && !categoryRail.isFocused()
                && !categoryRail.hasFocus()) closePanel();
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
