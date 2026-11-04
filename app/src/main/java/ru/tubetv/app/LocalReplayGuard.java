package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable monotonic counters prevent replay after reconnect or app restart. */
final class LocalReplayGuard {
    private static final String PREFS = "local_link_sequences";

    static synchronized long nextOutgoing(Context context, String peerId) throws Exception {
        return nextOutgoing(context.getSharedPreferences(PREFS, 0), peerId);
    }

    static synchronized long nextOutgoing(SharedPreferences prefs, String peerId) throws Exception {
        if (!LocalTrustedDevices.validId(peerId)) throw new Exception("Неизвестный адресат");
        String key = "out_" + peerId;
        long previous = prefs.getLong(key, 0);
        if (previous == Long.MAX_VALUE) throw new Exception("Счётчик сообщений исчерпан");
        long next = previous + 1;
        if (!prefs.edit().putLong(key, next).commit())
            throw new Exception("Не удалось сохранить счётчик отправки");
        return next;
    }

    static synchronized boolean acceptIncoming(Context context, String peerId,
                                               long sequence) throws Exception {
        return acceptIncoming(context.getSharedPreferences(PREFS, 0), peerId, sequence);
    }

    static synchronized boolean acceptIncoming(SharedPreferences prefs, String peerId,
                                               long sequence) throws Exception {
        if (!LocalTrustedDevices.validId(peerId) || sequence < 1) return false;
        String key = "in_" + peerId;
        if (sequence <= prefs.getLong(key, 0)) return false;
        if (!prefs.edit().putLong(key, sequence).commit())
            throw new Exception("Не удалось сохранить счётчик приёма");
        return true;
    }

    static synchronized void forget(Context context, String peerId) {
        context.getSharedPreferences(PREFS, 0).edit()
                .remove("in_" + peerId).remove("out_" + peerId).apply();
    }
}
