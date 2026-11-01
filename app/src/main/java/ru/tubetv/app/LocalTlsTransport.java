package ru.tubetv.app;

import android.content.Context;
import android.os.Build;
import android.util.Base64;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/** App-scoped, pinned TLS transport. It never uses the video-platform proxy. */
final class LocalTlsTransport {
    interface Listener {
        void discovered(String id, String name, String type, InetAddress address, int port);
        void lost(String id);
        void connected(String id);
        void disconnected(String id);
        void message(String id, LocalLinkProtocol.Message message, Session session);
        void error(String summary);
        boolean approvePairing(String id, String name, String type, String fingerprint);
    }

    interface PairResultListener {
        void complete(String deviceId, String name, String error);
    }
    interface ProbeListener { void complete(String fingerprint, String error); }

    static final class Endpoint {
        final InetAddress address;
        final int port;
        Endpoint(InetAddress address, int port) { this.address = address; this.port = port; }
    }
    static final class Discovered {
        final String id;
        final String name;
        final String type;
        final Endpoint endpoint;
        Discovered(String id, String name, String type, Endpoint endpoint) {
            this.id = id; this.name = name; this.type = type; this.endpoint = endpoint;
        }
    }

    final class Session implements AutoCloseable {
        final String peerId;
        private final SSLSocket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final AtomicBoolean closed = new AtomicBoolean();

        Session(String peerId, SSLSocket socket) throws Exception {
            this.peerId = peerId;
            this.socket = socket;
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }

        synchronized void send(String type, JSONObject payload) throws Exception {
            if (closed.get()) throw new Exception("Устройство отключено");
            long sequence = LocalReplayGuard.nextOutgoing(context, peerId);
            LocalLinkProtocol.write(output,
                    LocalLinkProtocol.create(ownId, type, sequence, payload));
        }

        void readLoop() throws Exception {
            long window = System.currentTimeMillis();
            int count = 0;
            while (running && !closed.get()) {
                LocalLinkProtocol.Message message = LocalLinkProtocol.read(input);
                if (message == null) break;
                long now = System.currentTimeMillis();
                if (now - window >= 1000) { window = now; count = 0; }
                if (++count > 30) throw new Exception("Слишком много локальных команд");
                if (!peerId.equals(message.deviceId)
                        || !LocalReplayGuard.acceptIncoming(context, peerId, message.sequence))
                    throw new Exception("Повторная или поддельная локальная команда");
                if ("PING".equals(message.type)) send("PONG", new JSONObject());
                else if (!"PONG".equals(message.type)) listener.message(peerId, message, this);
            }
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { socket.close(); } catch (Exception ignored) { }
            if (sessions.remove(peerId, this)) listener.disconnected(peerId);
        }
    }

    private final Context context;
    private final Listener listener;
    private final String ownId;
    private final String ownType;
    private final String ownName;
    private final LocalDiscovery discovery;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 4, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    private final Map<String, Discovered> foundDevices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile SSLServerSocket server;
    private volatile boolean running;
    private volatile String pairingNonce;
    private volatile String manualCode;
    private volatile long pairingExpiresAt;
    private int pairingAttempts;
    private boolean pairingInProgress;

    LocalTlsTransport(Context context, String type, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        ownId = LocalLinkIdentity.deviceId(context);
        ownType = type;
        ownName = Build.MANUFACTURER + " " + Build.MODEL;
        discovery = new LocalDiscovery(context);
    }

    synchronized void start() throws Exception {
        if (running) return;
        server = (SSLServerSocket) LocalLinkIdentity.sslContext()
                .getServerSocketFactory().createServerSocket(0);
        server.setNeedClientAuth(true);
        server.setSoTimeout(1000);
        running = true;
        try {
            discovery.advertise(server.getLocalPort(), ownId, ownName, ownType);
            discovery.browse(new LocalDiscovery.Listener() {
                @Override public void found(String id, String name, String type,
                                            InetAddress address, int port) {
                    if (id.equals(ownId)) return;
                    Endpoint endpoint = new Endpoint(address, port);
                    endpoints.put(id, endpoint);
                    foundDevices.put(id, new Discovered(id, name, type, endpoint));
                    listener.discovered(id, name, type, address, port);
                    if (ownId.compareTo(id) < 0 && LocalTrustedDevices.find(context, id) != null
                            && !sessions.containsKey(id)) connect(id, address, port);
                }
                @Override public void lost(String id) {
                    endpoints.remove(id);
                    foundDevices.remove(id);
                    listener.lost(id);
                }
            });
            workers.execute(this::acceptLoop);
            heartbeat.scheduleAtFixedRate(() -> {
                for (Session session : sessions.values()) {
                    try { session.send("PING", new JSONObject()); }
                    catch (Exception error) { session.close(); }
                }
            }, 10, 10, TimeUnit.SECONDS);
        } catch (Exception error) { stop(); throw error; }
    }

    synchronized void stop() {
        running = false;
        pairingNonce = null;
        manualCode = null;
        discovery.stop();
        if (server != null) { try { server.close(); } catch (Exception ignored) { } server = null; }
        for (Session session : sessions.values()) session.close();
        sessions.clear();
        workers.shutdownNow();
        heartbeat.shutdownNow();
    }

    int port() { return server == null ? 0 : server.getLocalPort(); }
    String deviceId() { return ownId; }
    String fingerprint() throws Exception { return LocalLinkIdentity.ownFingerprint(); }
    Session session(String peerId) { return sessions.get(peerId); }
    Endpoint endpoint(String peerId) { return endpoints.get(peerId); }
    java.util.List<Discovered> foundDevices() {
        return new java.util.ArrayList<>(foundDevices.values());
    }

    synchronized String openPairing() {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        pairingNonce = Base64.encodeToString(random,
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        manualCode = String.format(java.util.Locale.ROOT, "%08d",
                new SecureRandom().nextInt(100_000_000));
        pairingExpiresAt = System.currentTimeMillis() + 120_000;
        pairingAttempts = 0;
        pairingInProgress = false;
        return pairingNonce;
    }

    synchronized void closePairing() { pairingNonce = null; manualCode = null; }
    String manualCode() { return manualCode; }
    long pairingExpiresAt() { return pairingExpiresAt; }

    void connect(String peerId, InetAddress address, int port) {
        if (!running || sessions.containsKey(peerId)
                || LocalTrustedDevices.find(context, peerId) == null) return;
        try { workers.execute(() -> {
            SSLSocket socket = null;
            try {
                LocalTrustedDevices.Peer peer = LocalTrustedDevices.find(context, peerId);
                if (peer == null) return;
                socket = (SSLSocket) LocalLinkIdentity.sslContext().getSocketFactory()
                        .createSocket(address, port);
                socket.setSoTimeout(25_000);
                socket.startHandshake();
                requirePin(socket, peer.fingerprint);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                DataInputStream input = new DataInputStream(socket.getInputStream());
                LocalLinkProtocol.write(output, LocalLinkProtocol.create(ownId, "HELLO",
                        LocalReplayGuard.nextOutgoing(context, peerId), new JSONObject()));
                LocalLinkProtocol.Message reply = LocalLinkProtocol.read(input);
                if (reply == null || !"HELLO".equals(reply.type)
                        || !peerId.equals(reply.deviceId)
                        || !LocalReplayGuard.acceptIncoming(context, peerId, reply.sequence))
                    throw new Exception("Неверный ответ сопряжённого устройства");
                serve(peerId, socket);
                socket = null;
            } catch (Exception error) { listener.error("Соединение: " + error.getMessage()); }
            finally { if (socket != null) try { socket.close(); } catch (Exception ignored) { } }
        }); } catch (RuntimeException ignored) { }
    }

    void pair(String peerId, InetAddress address, int port, String expectedFingerprint,
              String nonce, PairResultListener callback) {
        if (!running || peerId != null && !LocalTrustedDevices.validId(peerId)
                || !LocalTrustedDevices.validFingerprint(expectedFingerprint)
                || nonce == null || !(nonce.length() >= 20 && nonce.length() <= 64
                || nonce.matches("\\d{8}"))) {
            callback.complete(null, null, "Неверные данные сопряжения");
            return;
        }
        try { workers.execute(() -> {
            try (SSLSocket socket = (SSLSocket) LocalLinkIdentity.sslContext()
                    .getSocketFactory().createSocket(address, port)) {
                socket.setSoTimeout(45_000);
                socket.startHandshake();
                requirePin(socket, expectedFingerprint);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                DataInputStream input = new DataInputStream(socket.getInputStream());
                JSONObject payload = new JSONObject().put("nonce", nonce)
                        .put("name", ownName).put("type", ownType);
                LocalLinkProtocol.write(output,
                        LocalLinkProtocol.create(ownId, "PAIR_REQUEST", 1, payload));
                LocalLinkProtocol.Message reply = LocalLinkProtocol.read(input);
                if (reply == null || !"PAIR_RESULT".equals(reply.type)
                        || !LocalTrustedDevices.validId(reply.deviceId)
                        || peerId != null && !peerId.equals(reply.deviceId)
                        || !reply.payload.optBoolean("approved", false))
                    throw new Exception("Сопряжение отклонено на телевизоре");
                String name = reply.payload.optString("name", "Телевизор");
                if (name.length() > 64 || !"TV".equals(reply.payload.optString("type")))
                    throw new Exception("Неверный ответ телевизора");
                String pairedId = reply.deviceId;
                LocalTrustedDevices.put(context, new LocalTrustedDevices.Peer(pairedId,
                        name, expectedFingerprint, "TV", true, true));
                endpoints.put(pairedId, new Endpoint(address, port));
                callback.complete(pairedId, name, null);
                connect(pairedId, address, port);
            } catch (Exception error) { callback.complete(null, null, error.getMessage()); }
        }); } catch (RuntimeException error) {
            callback.complete(null, null, "Сопряжение сейчас недоступно");
        }
    }

    void probe(InetAddress address, int port, ProbeListener callback) {
        try { workers.execute(() -> {
            try (SSLSocket socket = (SSLSocket) LocalLinkIdentity.sslContext()
                    .getSocketFactory().createSocket(address, port)) {
                socket.setSoTimeout(10_000);
                socket.startHandshake();
                callback.complete(peerFingerprint(socket), null);
            } catch (Exception error) { callback.complete(null, error.getMessage()); }
        }); } catch (RuntimeException error) { callback.complete(null, "Проверка недоступна"); }
    }

    private void acceptLoop() {
        while (running) {
            try {
                SSLSocket socket = (SSLSocket) server.accept();
                workers.execute(() -> accept(socket));
            } catch (SocketTimeoutException ignored) { }
            catch (Exception error) { if (running) listener.error("Локальный сервер: " + error.getMessage()); }
        }
    }

    private void accept(SSLSocket socket) {
        try {
            socket.setSoTimeout(25_000);
            socket.startHandshake();
            String pin = peerFingerprint(socket);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            LocalLinkProtocol.Message first = LocalLinkProtocol.read(input);
            if (first == null) return;
            if ("PAIR_REQUEST".equals(first.type)) {
                acceptPairing(first, pin, output);
                return;
            }
            LocalTrustedDevices.Peer peer = LocalTrustedDevices.find(context, first.deviceId);
            if (peer == null || !peer.fingerprint.equals(pin)
                    || !"HELLO".equals(first.type)
                    || !LocalReplayGuard.acceptIncoming(context, peer.id, first.sequence))
                throw new Exception("Неизвестное или отозванное устройство");
            LocalLinkProtocol.write(output, LocalLinkProtocol.create(ownId, "HELLO",
                    LocalReplayGuard.nextOutgoing(context, peer.id), new JSONObject()));
            serve(peer.id, socket);
            socket = null;
        } catch (Exception error) { listener.error("Локальный вход: " + error.getMessage()); }
        finally { if (socket != null) try { socket.close(); } catch (Exception ignored) { } }
    }

    private void acceptPairing(LocalLinkProtocol.Message request, String pin,
                               DataOutputStream output) throws Exception {
        String name = request.payload.optString("name", "Телефон");
        String type = request.payload.optString("type", "MOBILE");
        if (request.sequence != 1 || request.deviceId.equals(ownId)
                || name.length() > 64 || (!"MOBILE".equals(type) && !"TABLET".equals(type)))
            throw new Exception("Некорректное устройство");
        synchronized (this) {
            if (pairingInProgress || pairingNonce == null || System.currentTimeMillis() > pairingExpiresAt
                    || ++pairingAttempts > 4
                    || !(pairingNonce.equals(request.payload.optString("nonce"))
                    || manualCode.equals(request.payload.optString("nonce"))))
                throw new Exception("Код сопряжения истёк или неверен");
            pairingInProgress = true;
        }
        boolean approved;
        try { approved = listener.approvePairing(request.deviceId, name, type, pin); }
        finally { synchronized (this) { pairingInProgress = false; } }
        if (approved && pairingNonce == null) approved = false;
        if (approved) {
            LocalTrustedDevices.put(context, new LocalTrustedDevices.Peer(request.deviceId,
                    name, pin, type, true, true));
            pairingNonce = null;
            manualCode = null;
        }
        JSONObject result = new JSONObject().put("approved", approved)
                .put("name", ownName).put("type", ownType);
        LocalLinkProtocol.write(output, LocalLinkProtocol.create(ownId, "PAIR_RESULT", 1, result));
    }

    private void serve(String peerId, SSLSocket socket) throws Exception {
        Session current = new Session(peerId, socket);
        Session old = sessions.put(peerId, current);
        if (old != null) old.close();
        listener.connected(peerId);
        try { current.readLoop(); }
        finally { current.close(); }
    }

    static void requirePin(SSLSocket socket, String expected) throws Exception {
        if (!expected.equals(peerFingerprint(socket)))
            throw new Exception("Идентичность устройства изменилась");
    }

    private static String peerFingerprint(SSLSocket socket) throws Exception {
        Certificate[] chain = socket.getSession().getPeerCertificates();
        if (chain.length != 1) throw new Exception("Некорректный сертификат устройства");
        return LocalLinkIdentity.fingerprint(chain[0]);
    }
}
