package ru.tubetv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.net.InetAddress;
import java.util.List;

/** Local pairing and trusted-device management, shared by Mobile and TV. */
public final class LocalLinkActivity extends Activity implements LocalLinkRuntime.UiListener {
    private LocalLinkRuntime runtime;
    private LinearLayout content;
    private AlertDialog pairingDialog;

    @Override protected void onCreate(Bundle state) {
        if (BuildConfig.MOBILE_VARIANT && TvAppearancePreferences.theme(this) == 2)
            setTheme(R.style.AppThemeLight);
        super.onCreate(state);
        runtime = ((TubeApplication) getApplication()).localLink();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BuildConfig.MOBILE_VARIANT
                ? TvAppearancePreferences.background(this) : 0xff071629);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(18), dp(24), dp(30));
        scroll.addView(content);
        setContentView(scroll);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        runtime.setUi(this);
    }

    @Override protected void onPause() {
        runtime.clearUi(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (pairingDialog != null) pairingDialog.dismiss();
        super.onDestroy();
    }

    @Override public void changed() { if (!isFinishing()) render(); }

    private void render() {
        View previousFocus = getCurrentFocus();
        String previousLabel = previousFocus instanceof TextView
                ? ((TextView) previousFocus).getText().toString() : "";
        content.removeAllViews();
        heading(BuildConfig.MOBILE_VARIANT ? "Устройства" : "Синхронизация");
        action("Локальное соединение: " + (runtime.enabled() ? "Включено" : "Выключено"),
                () -> runtime.setEnabled(!runtime.enabled()));
        if (!runtime.enabled()) {
            note("Обмен работает в домашней сети, когда RussTube открыт на обоих устройствах.");
            restoreFocus(previousLabel);
            return;
        }
        note(runtime.running() ? "Поиск устройств в локальной сети активен"
                : "Локальное соединение недоступно: " + runtime.lastError());
        LocalTlsTransport link = runtime.link();
        if (link == null) { restoreFocus(previousLabel); return; }
        if (BuildConfig.MOBILE_VARIANT) {
            action("Добавить телевизор по QR-коду", this::scanQr);
            action("Ввести данные сопряжения вручную", this::manualPair);
        } else {
            action("Подключить устройство", this::showPairing);
        }
        action("Синхронизировать сейчас", runtime::syncNow);
        heading("Сопряжённые устройства");
        List<LocalTrustedDevices.Peer> peers = LocalTrustedDevices.load(this);
        if (peers.isEmpty()) note("Устройств пока нет");
        for (LocalTrustedDevices.Peer peer : peers) {
            boolean online = link.session(peer.id) != null;
            action(peer.name + " — " + (online ? "подключено" : "не в сети"), () ->
                    new AlertDialog.Builder(this).setTitle(peer.name)
                            .setItems(BuildConfig.MOBILE_VARIANT
                                            ? new String[]{"Пульт", "Синхронизировать", "Удалить сопряжение"}
                                            : new String[]{"Синхронизировать",
                                            "Управление: " + (peer.controlAllowed ? "разрешено" : "запрещено"),
                                            "Запуск видео: " + (peer.launchAllowed ? "разрешён" : "запрещён"),
                                            "Удалить сопряжение"},
                                    (dialog, which) -> {
                                        if (BuildConfig.MOBILE_VARIANT && which == 0)
                                            startActivity(new Intent(this, LocalRemoteActivity.class)
                                                    .putExtra(LocalRemoteActivity.PEER_ID, peer.id));
                                        else if (which == (BuildConfig.MOBILE_VARIANT ? 1 : 0))
                                            runtime.syncNow();
                                        else if (!BuildConfig.MOBILE_VARIANT && (which == 1 || which == 2)) {
                                            try {
                                                LocalTrustedDevices.put(this, new LocalTrustedDevices.Peer(
                                                        peer.id, peer.name, peer.fingerprint, peer.type,
                                                        which == 1 ? !peer.controlAllowed : peer.controlAllowed,
                                                        which == 2 ? !peer.launchAllowed : peer.launchAllowed));
                                                render();
                                            } catch (Exception error) { toast(error.getMessage()); }
                                        }
                                        else confirmRevoke(peer);
                                    }).show());
            long last = getSharedPreferences("local_link_sync", 0)
                    .getLong("last_" + peer.id, 0);
            if (last > 0) note("Последний обмен: " + android.text.format.DateFormat
                    .format("dd.MM.yyyy HH:mm", last));
        }
        if (!BuildConfig.MOBILE_VARIANT) {
            heading("Обнаружены в сети");
            for (LocalTlsTransport.Discovered device : link.foundDevices())
                note(device.name + " — " + device.type);
        }
        if (!runtime.lastError().isEmpty()) note("Последняя ошибка: " + runtime.lastError());
        restoreFocus(previousLabel);
    }

    private void restoreFocus(String label) {
        if (BuildConfig.MOBILE_VARIANT) return;
        View first = null;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (!child.isFocusable()) continue;
            if (first == null) first = child;
            if (child instanceof TextView
                    && ((TextView) child).getText().toString().equals(label)) {
                child.requestFocus(); return;
            }
        }
        if (first != null) first.requestFocus();
    }

    private void confirmRevoke(LocalTrustedDevices.Peer peer) {
        new AlertDialog.Builder(this).setTitle("Удалить сопряжение?")
                .setMessage(peer.name + " больше не сможет обмениваться данными с этим устройством.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    try { runtime.revoke(peer.id); }
                    catch (Exception error) { toast(error.getMessage()); }
                }).show();
    }

    private void showPairing() {
        try {
            LocalTlsTransport link = runtime.link();
            if (link == null) throw new Exception("Локальное соединение ещё не запущено");
            String nonce = link.openPairing();
            LocalPairingCode code = LocalPairingCode.forTv(link, nonce);
            String encoded = code.encode();
            BitMatrix matrix = new MultiFormatWriter().encode(encoded,
                    BarcodeFormat.QR_CODE, 280, 280);
            Bitmap bitmap = Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(),
                    Bitmap.Config.ARGB_8888);
            for (int y = 0; y < matrix.getHeight(); y++)
                for (int x = 0; x < matrix.getWidth(); x++)
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dp(18), dp(14), dp(18), dp(12));
            layout.addView(image, new LinearLayout.LayoutParams(dp(280), dp(280)));
            TextView details = label("Ручной ввод: " + code.host + ":" + code.port
                    + "  ·  код " + link.manualCode()
                    + "\nОтпечаток для сверки: " + code.fingerprint.substring(0, 16)
                    + "\nКод действует 2 минуты. Подтвердите запрос с телефона на экране ТВ.", 16);
            layout.addView(details);
            pairingDialog = new AlertDialog.Builder(this).setTitle("Подключить устройство")
                    .setView(layout).setNegativeButton("Закрыть", null).create();
            pairingDialog.setOnDismissListener(dialog -> link.closePairing());
            pairingDialog.show();
        } catch (Exception error) { toast(error.getMessage()); }
    }

    private void scanQr() {
        new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Сканируйте QR-код на телевизоре")
                .setBeepEnabled(false).initiateScan();
    }

    private void manualPair() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText address = new EditText(this);
        address.setHint("Адрес ТВ: порт (например 192.168.1.5:12345)");
        fields.addView(address);
        EditText code = new EditText(this);
        code.setHint("8-значный код с экрана ТВ");
        code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        fields.addView(code);
        new AlertDialog.Builder(this).setTitle("Ручное подключение")
                .setMessage("После соединения сравните отпечаток на обоих экранах.")
                .setView(fields).setNegativeButton("Отмена", null)
                .setPositiveButton("Подключить", (dialog, which) ->
                        pairManual(address.getText().toString().trim(),
                                code.getText().toString().trim()))
                .show();
    }

    private void pairManual(String addressAndPort, String code) {
        try {
            int colon = addressAndPort.lastIndexOf(':');
            if (colon < 1 || !code.matches("\\d{8}"))
                throw new Exception("Укажите адрес, порт и 8-значный код");
            String host = addressAndPort.substring(0, colon);
            int port = Integer.parseInt(addressAndPort.substring(colon + 1));
            if (!LocalPairingCode.localHost(host) || port < 1 || port > 65535)
                throw new Exception("Нужен доступный локальный IP-адрес ТВ");
            InetAddress address = InetAddress.getByName(host);
            LocalTlsTransport link = runtime.link();
            if (link == null) throw new Exception("Включите локальное соединение");
            link.probe(address, port, (fingerprint, error) -> runOnUiThread(() -> {
                if (error != null) { toast(error); return; }
                new AlertDialog.Builder(this).setTitle("Сверьте отпечаток")
                        .setMessage(fingerprint.substring(0, 16)
                                + "\nОн должен совпадать с отпечатком на ТВ.")
                        .setNegativeButton("Не совпадает", null)
                        .setPositiveButton("Совпадает", (dialog, which) ->
                                pairAt(link, null, address, port, fingerprint, code))
                        .show();
            }));
        } catch (Exception error) { toast(error.getMessage()); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) pairCode(result.getContents());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void pairCode(String text) {
        try {
            LocalPairingCode code = LocalPairingCode.parse(text);
            LocalTlsTransport link = runtime.link();
            if (link == null) throw new Exception("Включите локальное соединение");
            InetAddress address = InetAddress.getByName(code.host);
            pairAt(link, code.deviceId, address, code.port, code.fingerprint, code.nonce);
        } catch (Exception error) { toast(error.getMessage()); }
    }

    private void pairAt(LocalTlsTransport link, String deviceId, InetAddress address, int port,
                        String fingerprint, String nonce) {
            link.pair(deviceId, address, port, fingerprint, nonce,
                    (id, name, error) -> runOnUiThread(() -> {
                        if (error != null) toast(error);
                        else { toast("Подключено: " + name); render(); }
                    }));
    }

    @Override public void approve(String id, String name, String type, String fingerprint,
                                  LocalLinkRuntime.PairingAnswer answer) {
        if (isFinishing()) { answer.answer(false); return; }
        new AlertDialog.Builder(this).setTitle("Подключить " + name + "?")
                .setMessage("Тип: " + type + "\nКод устройства: " + fingerprint.substring(0, 16)
                        + "\nРазрешить обмен подписками и избранным?")
                .setNegativeButton("Отклонить", (dialog, which) -> answer.answer(false))
                .setPositiveButton("Подключить", (dialog, which) -> answer.answer(true))
                .setOnCancelListener(dialog -> answer.answer(false)).show();
    }

    private void heading(String value) {
        TextView view = label(value, 22);
        view.setTextColor(TvAppearancePreferences.text(this));
        content.addView(view, new LinearLayout.LayoutParams(-1, dp(54)));
    }

    private void note(CharSequence value) {
        TextView view = label(value.toString(), 15);
        view.setTextColor(TvAppearancePreferences.secondary(this));
        view.setPadding(dp(8), dp(4), dp(8), dp(8));
        content.addView(view);
    }

    private void action(String value, Runnable runnable) {
        TextView view = label(value, 17);
        view.setTextColor(TvAppearancePreferences.text(this));
        view.setPadding(dp(18), 0, dp(18), 0);
        view.setBackgroundColor(TvAppearancePreferences.surface(this));
        view.setFocusable(true);
        view.setOnFocusChangeListener((v, focused) ->
                view.setBackgroundColor(focused ? TvAppearancePreferences.focus(this)
                        : TvAppearancePreferences.surface(this)));
        view.setOnClickListener(v -> runnable.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.bottomMargin = dp(8);
        content.addView(view, params);
    }

    private TextView label(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
