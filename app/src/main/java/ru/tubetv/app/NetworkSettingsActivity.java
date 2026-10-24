package ru.tubetv.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.Proxy;

/** Network settings shared by the TV and mobile flavors. */
public final class NetworkSettingsActivity extends Activity {
    private RadioGroup types;
    private EditText host, port, user, password;
    private TextView connectionStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int background = BuildConfig.MOBILE_VARIANT ? MobileAuroraTheme.BACKGROUND : 0xff0b0f14;
        int accent = BuildConfig.MOBILE_VARIANT ? MobileAuroraTheme.TEAL : 0xffe53935;
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        NetworkProxy.Config saved = NetworkProxy.load(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(28));
        scroll.addView(root);
        TextView title = text("Сеть · Прокси", 26, Color.WHITE);
        root.addView(title);
        TextView note = text("Для поиска, обложек и видеопотоков HTTPS. Поддерживаются HTTP и SOCKS5; UDP-прокси для этих соединений не применяется.",
                14, 0xffaeb9cc);
        note.setPadding(0, dp(10), 0, dp(16));
        root.addView(note);

        types = new RadioGroup(this);
        types.setOrientation(RadioGroup.HORIZONTAL);
        addType("Без прокси", R.id.proxy_off);
        addType("HTTP", R.id.proxy_http);
        addType("SOCKS5", R.id.proxy_socks5);
        types.check(saved.type == Proxy.Type.HTTP ? R.id.proxy_http
                : saved.type == Proxy.Type.SOCKS ? R.id.proxy_socks5 : R.id.proxy_off);
        root.addView(types);

        host = field(root, "Адрес сервера", saved.host, InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        port = field(root, "Порт", saved.port == 0 ? "" : Integer.toString(saved.port),
                InputType.TYPE_CLASS_NUMBER);
        user = field(root, "Логин (необязательно)", saved.user, InputType.TYPE_CLASS_TEXT);
        password = field(root, "Пароль (необязательно)", saved.password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button save = new Button(this);
        save.setText("Сохранить");
        save.setAllCaps(false);
        save.setTextColor(BuildConfig.MOBILE_VARIANT ? MobileAuroraTheme.BACKGROUND : Color.WHITE);
        save.setBackgroundTintList(ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(52));
        saveParams.topMargin = dp(20);
        root.addView(save, saveParams);
        save.setOnClickListener(v -> save(true));
        if (BuildConfig.MOBILE_VARIANT) {
            Button verify = new Button(this);
            verify.setText("Сохранить и проверить соединение");
            verify.setAllCaps(false);
            verify.setTextColor(MobileAuroraTheme.TEXT);
            verify.setBackgroundTintList(ColorStateList.valueOf(MobileAuroraTheme.SURFACE));
            LinearLayout.LayoutParams verifyParams = new LinearLayout.LayoutParams(-1, dp(52));
            verifyParams.topMargin = dp(8);
            root.addView(verify, verifyParams);
            verify.setOnClickListener(v -> verify(verify));
            connectionStatus = text("", 14, 0xffaeb9cc);
            connectionStatus.setPadding(0, dp(10), 0, 0);
            root.addView(connectionStatus);
        }
        setContentView(scroll);
    }

    private void addType(String label, int id) {
        RadioButton button = new RadioButton(this);
        button.setId(id);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setButtonTintList(ColorStateList.valueOf(BuildConfig.MOBILE_VARIANT
                ? MobileAuroraTheme.TEAL : 0xffe53935));
        button.setTextSize(14);
        types.addView(button);
    }

    private EditText field(LinearLayout root, String hint, String value, int inputType) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(value);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xffaeb9cc);
        input.setInputType(inputType);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
        params.topMargin = dp(8);
        root.addView(input, params);
        return input;
    }

    private boolean save(boolean finishAfterSave) {
        String type = types.getCheckedRadioButtonId() == R.id.proxy_http ? "http"
                : types.getCheckedRadioButtonId() == R.id.proxy_socks5 ? "socks5" : "off";
        String address = host.getText().toString().trim();
        int number = 0;
        try { number = Integer.parseInt(port.getText().toString().trim()); }
        catch (NumberFormatException ignored) { }
        if (!"off".equals(type) && address.isEmpty()) {
            host.setError("Введите адрес прокси"); host.requestFocus(); return false;
        }
        if (!"off".equals(type) && (number < 1 || number > 65535)) {
            port.setError("Порт должен быть от 1 до 65535"); port.requestFocus(); return false;
        }
        NetworkProxy.save(this, type, address, number,
                user.getText().toString(), password.getText().toString());
        if (finishAfterSave) {
            Toast.makeText(this, "Настройки сети сохранены", Toast.LENGTH_SHORT).show();
            finish();
        }
        return true;
    }

    private void verify(Button button) {
        if (!save(false)) return;
        connectionStatus.setText("Проверяем соединение…");
        button.setEnabled(false);
        button.setText("Проверяем соединение…");
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
                if (isFinishing() || isDestroyed()) return;
                button.setEnabled(true);
                button.setText("Сохранить и проверить соединение");
                connectionStatus.setText(message);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        }, "proxy-check").start();
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
