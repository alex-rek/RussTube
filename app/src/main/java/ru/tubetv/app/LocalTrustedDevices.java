package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Trust metadata is separate from the existing subscriptions and favorites. */
final class LocalTrustedDevices {
    private static final String PREFS = "local_link_peers";
    private static final String KEY = "peers";

    static final class Peer {
        final String id;
        final String name;
        final String fingerprint;
        final String type;
        final boolean controlAllowed;
        final boolean launchAllowed;

        Peer(String id, String name, String fingerprint, String type,
             boolean controlAllowed, boolean launchAllowed) {
            this.id = id;
            this.name = name;
            this.fingerprint = fingerprint;
            this.type = type;
            this.controlAllowed = controlAllowed;
            this.launchAllowed = launchAllowed;
        }
    }

    static synchronized List<Peer> load(Context context) {
        List<Peer> result = new ArrayList<>();
        String json = context.getSharedPreferences(PREFS, 0).getString(KEY, "[]");
        try {
            JSONArray data = new JSONArray(json);
            for (int i = 0; i < Math.min(data.length(), 32); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id");
                String pin = item.optString("fingerprint");
                if (!validId(id) || !validFingerprint(pin)) continue;
                result.add(new Peer(id, item.optString("name", "Устройство"), pin,
                        item.optString("type", "MOBILE"), item.optBoolean("control", true),
                        item.optBoolean("launch", true)));
            }
        } catch (Exception ignored) { }
        return result;
    }

    static synchronized Peer find(Context context, String id) {
        for (Peer peer : load(context)) if (peer.id.equals(id)) return peer;
        return null;
    }

    static synchronized void put(Context context, Peer peer) throws Exception {
        if (!validId(peer.id) || !validFingerprint(peer.fingerprint))
            throw new Exception("Некорректная идентичность устройства");
        List<Peer> peers = load(context);
        for (int i = peers.size() - 1; i >= 0; i--)
            if (peers.get(i).id.equals(peer.id)) peers.remove(i);
        if (peers.size() >= 32) throw new Exception("Максимум 32 сопряжённых устройства");
        peers.add(peer);
        save(context, peers);
    }

    static synchronized void remove(Context context, String id) throws Exception {
        List<Peer> peers = load(context);
        for (int i = peers.size() - 1; i >= 0; i--)
            if (peers.get(i).id.equals(id)) peers.remove(i);
        save(context, peers);
    }

    private static void save(Context context, List<Peer> peers) throws Exception {
        JSONArray data = new JSONArray();
        for (Peer peer : peers) data.put(new JSONObject().put("id", peer.id)
                .put("name", peer.name).put("fingerprint", peer.fingerprint)
                .put("type", peer.type).put("control", peer.controlAllowed)
                .put("launch", peer.launchAllowed));
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        if (!prefs.edit().putString(KEY, data.toString()).commit())
            throw new Exception("Не удалось сохранить сопряжённое устройство");
    }

    static boolean validId(String id) {
        try { return UUID.fromString(id).toString().equals(id); }
        catch (Exception error) { return false; }
    }

    static boolean validFingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
