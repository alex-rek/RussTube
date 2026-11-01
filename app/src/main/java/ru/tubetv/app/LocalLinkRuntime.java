package ru.tubetv.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.net.InetAddress;

/** Foreground-only local link, shared by the existing activities. */
final class LocalLinkRuntime implements Application.ActivityLifecycleCallbacks {
    interface PairingAnswer { void answer(boolean approved); }
    interface UiListener {
        void changed();
        void approve(String id, String name, String type, String fingerprint,
                     PairingAnswer answer);
    }

    private final TubeApplication app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LocalTlsTransport transport;
    private LocalLibrarySync sync;
    private final java.util.concurrent.ExecutorService commands =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.Map<String, JSONObject> remoteStates =
            new java.util.concurrent.ConcurrentHashMap<>();
    private UiListener ui;
    private volatile Activity currentActivity;
    private long lastPublishedAt;
    private final Runnable pushLibrary = this::syncNow;
    private final SharedPreferences.OnSharedPreferenceChangeListener libraryChanged =
            (preferences, key) -> {
                if (!"channels".equals(key) && !"videos".equals(key)) return;
                main.removeCallbacks(pushLibrary);
                main.postDelayed(pushLibrary, 1200);
            };
    private int startedActivities;
    private String lastError = "";

    LocalLinkRuntime(TubeApplication app) {
        this.app = app;
        app.registerActivityLifecycleCallbacks(this);
        app.getSharedPreferences("subscriptions", 0)
                .registerOnSharedPreferenceChangeListener(libraryChanged);
        app.getSharedPreferences("favorites", 0)
                .registerOnSharedPreferenceChangeListener(libraryChanged);
    }

    synchronized boolean enabled() {
        return app.getSharedPreferences("local_link_settings", 0).getBoolean("enabled", false);
    }

    synchronized void setEnabled(boolean enabled) {
        app.getSharedPreferences("local_link_settings", 0).edit()
                .putBoolean("enabled", enabled).apply();
        if (enabled && startedActivities > 0) start();
        if (!enabled) stop();
        changed();
    }

    synchronized void setUi(UiListener listener) { ui = listener; changed(); }
    synchronized void clearUi(UiListener listener) { if (ui == listener) ui = null; }
    synchronized String lastError() { return lastError; }
    synchronized boolean running() { return transport != null; }
    synchronized LocalTlsTransport link() { return transport; }

    synchronized void start() {
        if (!enabled() || transport != null) return;
        LocalLibrarySync candidateSync = new LocalLibrarySync(app, new LocalLibrarySync.Listener() {
            @Override public void completed(String id, int channels, int favorites) { changed(); }
            @Override public void failed(String id, String reason) { error("Синхронизация: " + reason); }
        });
        LocalTlsTransport candidate = new LocalTlsTransport(app,
                BuildConfig.MOBILE_VARIANT ? "MOBILE" : "TV", new LocalTlsTransport.Listener() {
            @Override public void discovered(String id, String name, String type,
                                             InetAddress address, int port) { changed(); }
            @Override public void lost(String id) { changed(); }
            @Override public void connected(String id) {
                LocalTlsTransport.Session session = candidateSession(id);
                if (session != null) {
                    candidateSync.request(id, session);
                    candidateSync.push(id, session);
                }
                changed();
            }
            @Override public void disconnected(String id) { changed(); }
            @Override public void message(String id, LocalLinkProtocol.Message message,
                                          LocalTlsTransport.Session session) {
                if (message.type.startsWith("SYNC_")) candidateSync.receive(id, message, session);
                else if (BuildConfig.MOBILE_VARIANT && "PLAYBACK_STATE".equals(message.type)) {
                    remoteStates.put(id, message.payload);
                    changed();
                } else if (BuildConfig.MOBILE_VARIANT && "ERROR".equals(message.type)) {
                    LocalLinkRuntime.this.error("Пульт: "
                            + message.payload.optString("message", "Команда не выполнена"));
                } else if (!BuildConfig.MOBILE_VARIANT
                        && "PLAYBACK_COMMAND".equals(message.type)) {
                    handleTvCommand(id, message.payload, session);
                }
            }
            @Override public void error(String summary) { LocalLinkRuntime.this.error(summary); }
            @Override public boolean approvePairing(String id, String name, String type,
                                                     String fingerprint) {
                UiListener current;
                synchronized (LocalLinkRuntime.this) { current = ui; }
                if (current == null || BuildConfig.MOBILE_VARIANT) return false;
                java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicBoolean allowed =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                main.post(() -> current.approve(id, name, type, fingerprint, decision -> {
                    allowed.set(Boolean.TRUE.equals(decision)); ready.countDown();
                }));
                try { return ready.await(40, java.util.concurrent.TimeUnit.SECONDS)
                        && allowed.get(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); return false;
                }
            }
        });
        try {
            sync = candidateSync;
            transport = candidate;
            candidate.start();
            lastError = "";
        } catch (Exception error) {
            candidate.stop(); candidateSync.close();
            sync = null;
            transport = null;
            lastError = error.getMessage();
            android.util.Log.w("RussTubeLocalLink", "Local link start failed: "
                    + error.getClass().getSimpleName());
        }
        changed();
    }

    private synchronized LocalTlsTransport.Session candidateSession(String id) {
        return transport == null ? null : transport.session(id);
    }

    synchronized void stop() {
        if (transport != null) { transport.stop(); transport = null; }
        if (sync != null) { sync.close(); sync = null; }
        changed();
    }

    synchronized void syncNow() {
        if (transport == null || sync == null) return;
        for (LocalTrustedDevices.Peer peer : LocalTrustedDevices.load(app)) {
            LocalTlsTransport.Session session = transport.session(peer.id);
            if (session != null) {
                sync.request(peer.id, session);
                sync.push(peer.id, session);
            }
            else {
                LocalTlsTransport.Endpoint endpoint = transport.endpoint(peer.id);
                if (endpoint != null) transport.connect(peer.id, endpoint.address, endpoint.port);
            }
        }
    }

    synchronized void revoke(String id) throws Exception {
        LocalTrustedDevices.remove(app, id);
        LocalReplayGuard.forget(app, id);
        if (transport != null && transport.session(id) != null) transport.session(id).close();
        changed();
    }

    JSONObject remoteState(String id) { return remoteStates.get(id); }

    void sendCommand(String peerId, String command, JSONObject payload) {
        commands.execute(() -> {
            try {
                LocalTlsTransport active;
                synchronized (this) { active = transport; }
                LocalTlsTransport.Session session = active == null ? null : active.session(peerId);
                if (session == null) throw new Exception("Телевизор не подключён");
                JSONObject body = payload == null ? new JSONObject() : payload;
                body.put("command", command);
                session.send("PLAYBACK_COMMAND", body);
            } catch (Exception error) { error("Пульт: " + error.getMessage()); }
        });
    }

    void publishPlaybackState() {
        if (BuildConfig.MOBILE_VARIANT || System.currentTimeMillis() - lastPublishedAt < 2000)
            return;
        lastPublishedAt = System.currentTimeMillis();
        Activity activity = currentActivity;
        if (!(activity instanceof PlayerActivity)) return;
        try {
            JSONObject state = ((PlayerActivity) activity).localPlaybackState();
            commands.execute(() -> {
                LocalTlsTransport active;
                synchronized (this) { active = transport; }
                if (active == null) return;
                for (LocalTrustedDevices.Peer peer : LocalTrustedDevices.load(app)) {
                    LocalTlsTransport.Session session = active.session(peer.id);
                    if (session != null && peer.controlAllowed)
                        try { session.send("PLAYBACK_STATE", state); }
                        catch (Exception ignored) { }
                }
            });
        } catch (Exception ignored) { }
    }

    private void handleTvCommand(String peerId, JSONObject payload,
                                 LocalTlsTransport.Session session) {
        LocalTrustedDevices.Peer peer = LocalTrustedDevices.find(app, peerId);
        String command = payload.optString("command");
        if (peer == null || !peer.controlAllowed
                || "OPEN_VIDEO".equals(command) && !peer.launchAllowed) {
            sendCommandError(session, "Управление с этого устройства запрещено");
            return;
        }
        main.post(() -> {
            try {
                Activity activity = currentActivity;
                if (activity == null || activity.isFinishing())
                    throw new Exception("RussTube TV сейчас не открыт");
                JSONObject state;
                if ("OPEN_VIDEO".equals(command)) {
                    JSONObject video = payload.optJSONObject("video");
                    if (video == null) throw new Exception("Не передан ID видео");
                    VideoItem item = LocalVideoIdentity.toVideo(video);
                    VideoActions.play(activity, item);
                    state = new JSONObject().put("status", "BUFFERING")
                            .put("platform", item.source).put("title", item.title)
                            .put("positionMs", 0).put("durationMs", item.durationMs)
                            .put("live", item.isLive);
                } else if ("GET_CAPABILITIES".equals(command)) {
                    JSONObject capabilities = new JSONObject()
                            .put("commands", new org.json.JSONArray()
                                    .put("PLAY").put("PAUSE").put("TOGGLE_PLAY_PAUSE")
                                    .put("STOP").put("SEEK_TO").put("SEEK_FORWARD")
                                    .put("SEEK_BACKWARD").put("SET_PLAYBACK_SPEED")
                                    .put("OPEN_VIDEO").put("GET_PLAYBACK_STATE"));
                    commands.execute(() -> {
                        try { session.send("CAPABILITIES", capabilities); }
                        catch (Exception ignored) { }
                    });
                    return;
                } else if (command.startsWith("DPAD_") || "OK".equals(command)
                        || "BACK".equals(command)) {
                    int key = remoteKey(command);
                    if (key == KeyEvent.KEYCODE_UNKNOWN)
                        throw new Exception("Неизвестная кнопка пульта");
                    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                    activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
                    return;
                } else if (activity instanceof PlayerActivity) {
                    state = ((PlayerActivity) activity).applyLocalCommand(command, payload);
                } else if ("GET_PLAYBACK_STATE".equals(command)) {
                    state = new JSONObject().put("status", "IDLE");
                } else throw new Exception("Плеер не открыт");
                commands.execute(() -> {
                    try { session.send("PLAYBACK_STATE", state); }
                    catch (Exception ignored) { }
                });
            } catch (Exception error) { sendCommandError(session, error.getMessage()); }
        });
    }

    private static int remoteKey(String command) {
        switch (command) {
            case "DPAD_UP": return KeyEvent.KEYCODE_DPAD_UP;
            case "DPAD_DOWN": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "DPAD_LEFT": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "DPAD_RIGHT": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "OK": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "BACK": return KeyEvent.KEYCODE_BACK;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    private void sendCommandError(LocalTlsTransport.Session session, String reason) {
        commands.execute(() -> {
            try { session.send("ERROR", new JSONObject().put("message", reason)); }
            catch (Exception ignored) { }
        });
    }

    private synchronized void error(String message) {
        lastError = message;
        android.util.Log.w("RussTubeLocalLink", "Local link operation failed");
        changed();
    }
    private synchronized void changed() {
        UiListener current = ui;
        if (current != null) main.post(current::changed);
    }

    @Override public synchronized void onActivityStarted(Activity activity) {
        startedActivities++;
        if (startedActivities == 1) start();
    }
    @Override public synchronized void onActivityStopped(Activity activity) {
        startedActivities = Math.max(0, startedActivities - 1);
        if (startedActivities == 0) stop();
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityResumed(Activity activity) { currentActivity = activity; }
    @Override public void onActivityPaused(Activity activity) {
        if (currentActivity == activity) currentActivity = null;
    }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
