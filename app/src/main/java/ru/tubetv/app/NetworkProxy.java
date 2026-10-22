package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

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
import java.util.Collections;
import java.util.List;

/** App-wide proxy settings for explicit HTTP connections and Media3's proxy reader. */
final class NetworkProxy {
    private static final String PREFS = "network_proxy";
    private static volatile boolean installed;
    private static volatile Context appContext;

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
                    return Collections.singletonList(new Proxy(config.type,
                            InetSocketAddress.createUnresolved(config.host, config.port)));
                }

                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
                    if (fallback != null) fallback.connectFailed(uri, address, error);
                }
            });
            Authenticator.setDefault(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    Config config = load(app);
                    if (getRequestorType() != RequestorType.PROXY || config.user.isEmpty()
                            || !config.host.equalsIgnoreCase(getRequestingHost())
                            || config.port != getRequestingPort()) return null;
                    return new PasswordAuthentication(config.user, config.password.toCharArray());
                }
            });
            installed = true;
        }
    }

    static HttpURLConnection openConnection(String address) throws IOException {
        URL url = new URL(address);
        Context context = appContext;
        Config config = context == null ? null : load(context);
        if (config == null || config.type == Proxy.Type.DIRECT)
            return (HttpURLConnection) url.openConnection();
        Proxy proxy = new Proxy(config.type,
                InetSocketAddress.createUnresolved(config.host, config.port));
        return (HttpURLConnection) url.openConnection(proxy);
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
                prefs.getString("user", ""), prefs.getString("password", ""));
    }

    static void save(Context context, String type, String host, int port,
                     String user, String password) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("type", type).putString("host", host.trim())
                .putInt("port", port).putString("user", user.trim())
                .putString("password", password).apply();
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

    private NetworkProxy() { }
}
