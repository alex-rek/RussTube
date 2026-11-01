package ru.tubetv.app;

import android.content.Context;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Additive two-way merge of only the existing subscription and favorite stores. */
final class LocalLibrarySync {
    interface Listener {
        void completed(String deviceId, int channelsAdded, int favoritesAdded);
        void failed(String deviceId, String reason);
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    LocalLibrarySync(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void request(String deviceId, LocalTlsTransport.Session session) {
        io.execute(() -> {
            try { session.send("SYNC_REQUEST", new JSONObject()); }
            catch (Exception error) { listener.failed(deviceId, error.getMessage()); }
        });
    }

    void push(String deviceId, LocalTlsTransport.Session session) {
        io.execute(() -> {
            try { session.send("SYNC_CHANGES", LocalLibraryCodec.export(context)); }
            catch (Exception error) { listener.failed(deviceId, error.getMessage()); }
        });
    }

    void receive(String deviceId, LocalLinkProtocol.Message message,
                 LocalTlsTransport.Session session) {
        if (!"SYNC_REQUEST".equals(message.type) && !"SYNC_CHANGES".equals(message.type)
                && !"SYNC_ACK".equals(message.type)) return;
        io.execute(() -> {
            try {
                switch (message.type) {
                    case "SYNC_REQUEST":
                        session.send("SYNC_CHANGES", LocalLibraryCodec.export(context));
                        break;
                    case "SYNC_CHANGES":
                        MobileLibraryTransfer.ImportData data =
                                LocalLibraryCodec.importData(message.payload);
                        MobileLibraryTransfer.Result result = MobileLibraryTransfer.merge(context, data);
                        session.send("SYNC_ACK", new JSONObject()
                                .put("forMessage", message.id)
                                .put("subscriptionsAdded", result.channels)
                                .put("favoritesAdded", result.favorites));
                        context.getSharedPreferences("local_link_sync", 0).edit()
                                .putLong("last_" + deviceId, System.currentTimeMillis()).apply();
                        listener.completed(deviceId, result.channels, result.favorites);
                        break;
                    case "SYNC_ACK":
                        context.getSharedPreferences("local_link_sync", 0).edit()
                                .putLong("last_" + deviceId, System.currentTimeMillis()).apply();
                        break;
                    default: break;
                }
            } catch (Exception error) { listener.failed(deviceId, error.getMessage()); }
        });
    }

    void close() { io.shutdownNow(); }
}
