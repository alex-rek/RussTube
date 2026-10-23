package ru.tubetv.app;

import android.app.Activity;
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
    private EditText proxyHost, proxyPort, proxyUser, proxyPassword;
    private String proxyType;
    private String draftHost, draftPort, draftUser, draftPassword;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        dashboardRoot = new LinearLayout(this);
        LinearLayout root = dashboardRoot;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0b0f14);
        root.setPadding(dp(48), dp(28), dp(48), dp(24));
        TextView title = new TextView(this);
        title.setText("Настройки"); title.setTextSize(30); title.setTextColor(Color.WHITE);
        root.addView(title);
        TextView hint = new TextView(this);
        hint.setText("Выберите раздел · Назад — вернуться в каталог");
        hint.setTextSize(14); hint.setTextColor(0xffaeb9cc);
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
                symbol.setText(SYMBOLS[position]); symbol.setTextSize(34);
                symbol.setTextColor(0xffe53935); symbol.setGravity(Gravity.CENTER);
                tile.addView(symbol, new LinearLayout.LayoutParams(-1, dp(54)));
                TextView label = new TextView(TvSettingsActivity.this);
                label.setText(CATEGORIES[position]); label.setTextSize(19);
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
    }

    private void interfaceSettings() {
        section("Порядок видео");
        VideoSort.Mode current = VideoSort.parse(getSharedPreferences("browse", 0)
                .getString("video_sort", VideoSort.Mode.NEWEST.name()));
        VideoSort.Mode[] modes = VideoSort.Mode.values();
        for (int i = 0; i < modes.length; i++) {
            final int index = i;
            row(modes[i].label, modes[i] == current ? "✓" : "", () -> {
                getSharedPreferences("browse", 0).edit()
                        .putString("video_sort", modes[index].name()).apply();
                renderPanel(index);
            });
        }
        note("Выбор применяется к ленте после возврата в каталог.");
    }

    private void playerSettings() {
        section("Скорость по умолчанию");
        float[] speeds = {.75f, 1f, 1.25f, 1.5f, 2f};
        String[] labels = {"0,75×", "1×", "1,25×", "1,5×", "2×"};
        float current = StateStore.playbackSpeed(this);
        for (int i = 0; i < speeds.length; i++) {
            final int index = i;
            row(labels[i], Math.abs(current - speeds[i]) < .01f ? "✓" : "", () -> {
                StateStore.savePlaybackSpeed(this, speeds[index]);
                renderPanel(index);
            });
        }
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
        note("История остаётся на устройстве. Поиск тем выполняется на видеоплатформах.");
    }

    private void networkSettings() {
        section("Прокси");
        note("Для поиска, обложек и видеопотоков HTTPS. Доступны HTTP и SOCKS5; UDP не используется.");
        String[] types = {"off", "http", "socks5"};
        String[] names = {"Без прокси", "HTTP", "SOCKS5"};
        for (int i = 0; i < types.length; i++) {
            final int index = i;
            row(names[i], types[i].equals(proxyType) ? "✓" : "", () -> {
                captureProxyDraft();
                proxyType = types[index];
                renderPanel(index);
            });
        }
        section("Сервер");
        proxyHost = field("Адрес", draftHost, InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        proxyPort = field("Порт", draftPort, InputType.TYPE_CLASS_NUMBER);
        proxyUser = field("Логин (необязательно)", draftUser, InputType.TYPE_CLASS_TEXT);
        proxyPassword = field("Пароль (необязательно)", draftPassword,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        row("Сохранить прокси", "✓", this::saveProxy);
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
        captureProxyDraft();
        int port = 0;
        try { port = Integer.parseInt(draftPort); }
        catch (NumberFormatException ignored) { }
        if (!"off".equals(proxyType) && draftHost.isEmpty()) {
            proxyHost.setError("Введите адрес прокси"); proxyHost.requestFocus(); return;
        }
        if (!"off".equals(proxyType) && (port < 1 || port > 65535)) {
            proxyPort.setError("Порт должен быть от 1 до 65535"); proxyPort.requestFocus(); return;
        }
        NetworkProxy.save(this, proxyType, draftHost, port, draftUser, draftPassword);
        Toast.makeText(this, "Настройки сети сохранены", Toast.LENGTH_SHORT).show();
    }

    private void about() {
        section("RussTubeTV " + BuildConfig.VERSION_NAME);
        note("Поиск видео на RUTUBE, VK Видео, OK, Дзене и PeerTube.");
        note("История и подписки хранятся на устройстве.");
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
        result.setTextSize(size);
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
        if (category >= 0) closePanel();
        else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("category", category);
        super.onSaveInstanceState(state);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
