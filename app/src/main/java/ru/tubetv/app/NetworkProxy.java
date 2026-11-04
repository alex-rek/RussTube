package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Proxy settings and per-platform routing for catalog, artwork and media requests. */
final class NetworkProxy {
    private static final String PREFS = "network_proxy";
    private static final String PASSWORD_KEY = "russtube-proxy-password-v1";
    private static final String ENCRYPTED_PASSWORD = "password_v1";
    private static final byte[] PASSWORD_CONTEXT =
            "RussTube proxy password v1".getBytes(StandardCharsets.UTF_8);
    static final String[] SOURCES = {"RUTUBE", "VK VIDEO", "PEERTUBE", "OK", "ДЗЕН"};
    static final String[] SOURCE_LABELS = {"RuTube", "VK Видео", "PeerTube", "OK", "Дзен"};
    private static volatile boolean installed;
    private static volatile Context appContext;
    private static volatile PasswordCache passwordCache;

    static void install(Context context) {
        if (installed) return;
        synchronized (NetworkProxy.class) {
            if (installed) return;
            Context app = context.getApplicationContext();
            appContext = app;
            ProxySelector fallback = ProxySelector.getDefault();
            ProxySelector.setDefault(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) {
                    Config config = load(app);
                    if (config.type == Proxy.Type.DIRECT || uri == null
                            || !("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))) {
                        return fallback == null ? Collections.singletonList(Proxy.NO_PROXY)
                                : fallback.select(uri);
                    }
                    String source = sourceForUrl(uri.toString());
                    if (source != null && !isSourceEnabled(app, source))
                        return Collections.singletonList(Proxy.NO_PROXY);
                    return Collections.singletonList(new Proxy(config.type,
                            new InetSocketAddress(config.host, config.port)));
                }

                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
                    if (fallback != null) fallback.connectFailed(uri, address, error);
                }
            });
            Authenticator.setDefault(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    Config config = load(app);
                    // Android does not consistently label SOCKS authentication as
                    // SOCKS5 or RequestorType.PROXY.
                    boolean socksRequest = config.type == Proxy.Type.SOCKS;
                    if ((!socksRequest && getRequestorType() != RequestorType.PROXY)
                            || config.user.isEmpty()
                            || !config.host.equalsIgnoreCase(getRequestingHost())
                            || config.port != getRequestingPort()) return null;
                    return new PasswordAuthentication(config.user, config.password.toCharArray());
                }
            });
            installed = true;
        }
    }

    static HttpURLConnection openConnection(String address) throws IOException {
        return openConnection(null, address);
    }

    static HttpURLConnection openConnection(String source, String address) throws IOException {
        URL url = new URL(address);
        Context context = appContext;
        Config config = context == null ? null : load(context);
        if (config == null || config.type == Proxy.Type.DIRECT
                || (source != null && !isSourceEnabled(context, source)))
            return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        if (config.type == Proxy.Type.HTTP && !config.user.isEmpty())
            return new AuthenticatedHttpProxyConnection(url, config);
        Proxy proxy = new Proxy(config.type,
                new InetSocketAddress(config.host, config.port));
        return (HttpURLConnection) url.openConnection(proxy);
    }

    static boolean isSourceEnabled(Context context, String source) {
        return isSourceEnabled(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), source);
    }

    static boolean isSourceEnabled(SharedPreferences prefs, String source) {
        return source == null || prefs.getBoolean("source_" + source, true);
    }

    static void setSourceEnabled(Context context, String source, boolean enabled) {
        setSourceEnabled(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), source, enabled);
    }

    static void setSourceEnabled(SharedPreferences prefs, String source, boolean enabled) {
        prefs.edit().putBoolean("source_" + source, enabled).apply();
    }

    /** Only recognizable first-party hosts are inferred; CDN requests carry their source explicitly. */
    static String sourceForUrl(String address) {
        try {
            String host = new URL(address).getHost().toLowerCase(java.util.Locale.ROOT);
            if (domain(host, "rutube.ru")) return "RUTUBE";
            if (domain(host, "vk.com") || domain(host, "vkvideo.ru")) return "VK VIDEO";
            if (domain(host, "ok.ru") || domain(host, "odnoklassniki.ru")) return "OK";
            if (domain(host, "dzen.ru") || domain(host, "zen.yandex.ru")) return "ДЗЕН";
            if (domain(host, "sepiasearch.org") || domain(host, "peerseek.video"))
                return "PEERTUBE";
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean domain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    static Config load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String kind = prefs.getString("type", "off");
        Proxy.Type type = "http".equals(kind) ? Proxy.Type.HTTP
                : "socks5".equals(kind) ? Proxy.Type.SOCKS : Proxy.Type.DIRECT;
        String host = prefs.getString("host", "").trim();
        int port = prefs.getInt("port", 0);
        if (host.isEmpty() || port < 1 || port > 65535) type = Proxy.Type.DIRECT;
        return new Config(type, host, port,
                prefs.getString("user", ""), readPassword(prefs));
    }

    static void save(Context context, String type, String host, int port,
                     String user, String password) {
        String protectedPassword;
        try { protectedPassword = password.isEmpty() ? "" : encryptPassword(password); }
        catch (Exception error) {
            throw new IllegalStateException("Не удалось защитить пароль прокси", error);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("type", type).putString("host", host.trim())
                .putInt("port", port).putString("user", user.trim())
                .putString(ENCRYPTED_PASSWORD, protectedPassword).remove("password").apply();
        passwordCache = new PasswordCache(protectedPassword, password);
    }

    private static String readPassword(SharedPreferences prefs) {
        String encrypted = prefs.getString(ENCRYPTED_PASSWORD, null);
        if (encrypted != null) {
            if (encrypted.isEmpty()) return "";
            PasswordCache cache = passwordCache;
            if (cache != null && encrypted.equals(cache.envelope)) return cache.plaintext;
            try {
                String plaintext = decryptPassword(encrypted);
                passwordCache = new PasswordCache(encrypted, plaintext);
                return plaintext;
            }
            catch (Exception error) { return ""; }
        }
        String stored = prefs.getString("password", "");
        if (stored == null || stored.isEmpty()) return "";
        try {
            // Existing installations stored this value unencrypted. Migrate it once.
            encrypted = encryptPassword(stored);
            if (!prefs.edit().putString(ENCRYPTED_PASSWORD, encrypted)
                    .remove("password").commit()) return "";
            passwordCache = new PasswordCache(encrypted, stored);
            return stored;
        } catch (Exception error) {
            // A missing or invalid Keystore key must not crash proxy selection.
            return "";
        }
    }

    private static synchronized SecretKey passwordKey() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        SecretKey key = (SecretKey) keys.getKey(PASSWORD_KEY, null);
        if (key != null) return key;
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(PASSWORD_KEY,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    private static String encryptPassword(String password) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, passwordKey());
        cipher.updateAAD(PASSWORD_CONTEXT);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        byte[] envelope = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, envelope, 0, iv.length);
        System.arraycopy(encrypted, 0, envelope, iv.length, encrypted.length);
        return Base64.encodeToString(envelope, Base64.NO_WRAP);
    }

    private static String decryptPassword(String stored) throws Exception {
        byte[] envelope = Base64.decode(stored, Base64.NO_WRAP);
        if (envelope.length < 12 + 16) throw new IllegalArgumentException("Invalid password data");
        byte[] iv = java.util.Arrays.copyOfRange(envelope, 0, 12);
        byte[] encrypted = java.util.Arrays.copyOfRange(envelope, 12, envelope.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, passwordKey(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(PASSWORD_CONTEXT);
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    static final class Config {
        final Proxy.Type type;
        final String host;
        final int port;
        final String user;
        final String password;
        Config(Proxy.Type type, String host, int port, String user, String password) {
            this.type = type; this.host = host; this.port = port;
            this.user = user; this.password = password;
        }
    }

    private static final class PasswordCache {
        final String envelope;
        final String plaintext;
        PasswordCache(String envelope, String plaintext) {
            this.envelope = envelope;
            this.plaintext = plaintext;
        }
    }

    private NetworkProxy() { }
}
