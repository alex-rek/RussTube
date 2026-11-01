package ru.tubetv.app;

import org.json.JSONObject;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Short-lived QR payload. The nonce is one-time; no private key is encoded. */
final class LocalPairingCode {
    final String deviceId;
    final String fingerprint;
    final String host;
    final int port;
    final String nonce;
    final long expiresAt;

    LocalPairingCode(String deviceId, String fingerprint, String host, int port,
                     String nonce, long expiresAt) {
        this.deviceId = deviceId; this.fingerprint = fingerprint; this.host = host;
        this.port = port; this.nonce = nonce; this.expiresAt = expiresAt;
    }

    static LocalPairingCode forTv(LocalTlsTransport transport, String nonce) throws Exception {
        String host = localAddress();
        if (host.isEmpty()) throw new Exception("Локальный IP-адрес не найден");
        return new LocalPairingCode(transport.deviceId(), transport.fingerprint(), host,
                transport.port(), nonce, transport.pairingExpiresAt());
    }

    String encode() throws Exception {
        return new JSONObject().put("app", "RussTube").put("version", 1)
                .put("deviceId", deviceId).put("fingerprint", fingerprint)
                .put("host", host).put("port", port).put("nonce", nonce)
                .put("expiresAt", expiresAt).toString();
    }

    static LocalPairingCode parse(String json) throws Exception {
        if (json == null || json.length() > 2048) throw new Exception("Слишком длинный код");
        JSONObject data = new JSONObject(json);
        String id = data.optString("deviceId");
        String pin = data.optString("fingerprint");
        String host = data.optString("host");
        String nonce = data.optString("nonce");
        int port = data.optInt("port", 0);
        if (!"RussTube".equals(data.optString("app")) || data.optInt("version") != 1
                || !LocalTrustedDevices.validId(id)
                || !LocalTrustedDevices.validFingerprint(pin)
                || !localHost(host) || port < 1 || port > 65535
                || nonce.length() < 20 || nonce.length() > 64)
            throw new Exception("Неверный код сопряжения RussTube");
        return new LocalPairingCode(id, pin, host, port, nonce, data.optLong("expiresAt"));
    }

    private static String localAddress() throws Exception {
        for (NetworkInterface net : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!net.isUp() || net.isLoopback()) continue;
            for (InetAddress address : Collections.list(net.getInetAddresses())) {
                if (address instanceof java.net.Inet4Address && address.isSiteLocalAddress())
                    return address.getHostAddress();
            }
        }
        return "";
    }

    static boolean localHost(String host) {
        if (host == null || host.length() > 64 || host.isEmpty()
                || !host.matches("[0-9a-fA-F:.%]+")) return false;
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (Exception ignored) { return false; }
    }
}
