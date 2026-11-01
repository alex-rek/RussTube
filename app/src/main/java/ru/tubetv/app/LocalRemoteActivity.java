package ru.tubetv.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

/** In-app TV remote. D-pad events are delivered only to RussTube TV. */
public final class LocalRemoteActivity extends Activity implements LocalLinkRuntime.UiListener {
    static final String PEER_ID = "peer_id";
    private LocalLinkRuntime runtime;
    private String peerId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 500); }
    };
    private TextView title;
    private TextView status;
    private TextView time;
    private SeekBar progress;
    private boolean seeking;
    private long snapshotAt;
    private JSONObject snapshot;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        peerId = getIntent().getStringExtra(PEER_ID);
        if (!LocalTrustedDevices.validId(peerId)
                || LocalTrustedDevices.find(this, peerId) == null) { finish(); return; }
        runtime = ((TubeApplication) getApplication()).localLink();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(MobileAuroraTheme.BACKGROUND);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(18), dp(24), dp(18), dp(24));
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        setContentView(scroll);
        LocalTrustedDevices.Peer peer = LocalTrustedDevices.find(this, peerId);
        root.addView(label(peer == null ? "Телевизор" : peer.name, 22));
        title = label("Видео не запущено", 20);
        root.addView(title);
        status = label("Подключение…", 15);
        root.addView(status);
        progress = new SeekBar(this);
        root.addView(progress);
        time = label("0:00 / 0:00", 15);
        root.addView(time);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                long duration = snapshot == null ? 0 : snapshot.optLong("durationMs");
                if (duration > 0 && !snapshot.optBoolean("live"))
                    commandValue("SEEK_TO", "positionMs", duration * bar.getProgress() / 1000);
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { }
        });
        LinearLayout playback = horizontal();
        playback.addView(button("◀◀", () -> command("SEEK_BACKWARD")), weight());
        playback.addView(button("▶ / ❚❚", () -> command("TOGGLE_PLAY_PAUSE")), weight());
        playback.addView(button("▶▶", () -> command("SEEK_FORWARD")), weight());
        root.addView(playback);
        LinearLayout second = horizontal();
        second.addView(button("Стоп", () -> command("STOP")), weight());
        second.addView(button("Скорость", this::chooseSpeed), weight());
        second.addView(button("Обновить", () -> command("GET_PLAYBACK_STATE")), weight());
        root.addView(second);
        root.addView(label("Навигация RussTube TV", 18));
        LinearLayout up = horizontal();
        up.addView(button("↑", () -> command("DPAD_UP")), weight());
        root.addView(up);
        LinearLayout middle = horizontal();
        middle.addView(button("←", () -> command("DPAD_LEFT")), weight());
        middle.addView(button("OK", () -> command("OK")), weight());
        middle.addView(button("→", () -> command("DPAD_RIGHT")), weight());
        root.addView(middle);
        LinearLayout bottom = horizontal();
        bottom.addView(button("Назад", () -> command("BACK")), weight());
        bottom.addView(button("↓", () -> command("DPAD_DOWN")), weight());
        root.addView(bottom);
        root.addView(button("Отключиться", () -> {
            LocalTlsTransport link = runtime.link();
            if (link != null && link.session(peerId) != null) link.session(peerId).close();
            finish();
        }));
    }

    @Override protected void onResume() {
        super.onResume();
        if (runtime == null) return;
        runtime.setUi(this);
        command("GET_PLAYBACK_STATE");
        handler.post(ticker);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(ticker);
        if (runtime != null) runtime.clearUi(this);
        super.onPause();
    }

    @Override public void changed() {
        snapshot = runtime.remoteState(peerId);
        snapshotAt = System.currentTimeMillis();
        refresh();
    }

    @Override public void approve(String id, String name, String type, String fingerprint,
                                  LocalLinkRuntime.PairingAnswer answer) { answer.answer(false); }

    private void refresh() {
        if (runtime == null || title == null) return;
        LocalTlsTransport link = runtime.link();
        boolean online = link != null && link.session(peerId) != null;
        status.setText(online ? (snapshot == null ? "Подключено" : snapshot.optString("status"))
                : "Телевизор не в сети");
        if (snapshot == null) return;
        title.setText(snapshot.optString("title", "Видео не запущено"));
        long position = snapshot.optLong("positionMs");
        long duration = snapshot.optLong("durationMs");
        if ("PLAYING".equals(snapshot.optString("status")))
            position += System.currentTimeMillis() - snapshotAt;
        if (duration > 0) position = Math.min(position, duration);
        time.setText(clock(position) + " / " + clock(duration));
        progress.setEnabled(duration > 0 && !snapshot.optBoolean("live"));
        if (!seeking) progress.setProgress(duration <= 0 ? 0
                : (int) (Math.max(0, position) * 1000 / duration));
    }

    private void chooseSpeed() {
        String[] labels = {"0,75×", "1×", "1,25×", "1,5×", "2×"};
        float[] speeds = {.75f, 1f, 1.25f, 1.5f, 2f};
        new android.app.AlertDialog.Builder(this).setTitle("Скорость на ТВ")
                .setItems(labels, (dialog, which) ->
                        commandValue("SET_PLAYBACK_SPEED", "speed", speeds[which]))
                .show();
    }

    private void command(String value) { command(value, new JSONObject()); }
    private void command(String value, JSONObject body) { runtime.sendCommand(peerId, value, body); }
    private void commandValue(String command, String key, Object value) {
        try { command(command, new JSONObject().put(key, value)); }
        catch (Exception ignored) { }
    }
    private static String clock(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    private LinearLayout horizontal() { return new LinearLayout(this); }
    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, dp(54), 1);
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER_VERTICAL); view.setMinHeight(dp(42));
        return view;
    }
    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
