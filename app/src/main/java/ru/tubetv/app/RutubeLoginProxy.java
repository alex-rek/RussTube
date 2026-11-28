package ru.tubetv.app;

import android.app.Activity;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.net.Proxy;
import java.util.Locale;

/** WebView has its own network stack and does not use NetworkProxy's ProxySelector. */
final class RutubeLoginProxy {
    interface FailureHandler { void onFailure(String message); }

    private boolean overrideRequested;

    void prepare(Activity activity, Runnable ready, FailureHandler failure) {
        NetworkProxy.Config config = NetworkProxy.load(activity);
        boolean useProxy = config.type != Proxy.Type.DIRECT
                && NetworkProxy.isSourceEnabled(activity, "RUTUBE");
        if (useProxy && config.type == Proxy.Type.SOCKS && !config.user.isEmpty()) {
            failure.onFailure("WebView не поддерживает вход через SOCKS5 с логином. "
                    + "Используйте HTTP-прокси или системный прокси для страницы входа.");
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (useProxy) failure.onFailure("WebView на этом устройстве не поддерживает прокси. "
                    + "Для входа настройте системный прокси или обновите Android WebView.");
            else ready.run();
            return;
        }
        try {
            ProxyController controller = ProxyController.getInstance();
            if (!useProxy) {
                overrideRequested = false;
                controller.clearProxyOverride(activity::runOnUiThread, ready);
                return;
            }
            ProxyConfig proxy = new ProxyConfig.Builder()
                    .addProxyRule(proxyRule(config)).build();
            overrideRequested = true;
            // The override affects all WebViews in this process. Load only after it is applied.
            controller.setProxyOverride(proxy, activity::runOnUiThread, ready);
        } catch (RuntimeException error) {
            overrideRequested = false;
            failure.onFailure("Не удалось настроить прокси для страницы входа RUTUBE: "
                    + error.getClass().getSimpleName());
        }
    }

    void clear(Activity activity) {
        if (!overrideRequested
                || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return;
        overrideRequested = false;
        try {
            ProxyController.getInstance().clearProxyOverride(activity::runOnUiThread, () -> { });
        } catch (RuntimeException ignored) { }
    }

    static String proxyRule(NetworkProxy.Config config) {
        String scheme = config.type == Proxy.Type.SOCKS ? "socks://" : "http://";
        String host = config.host;
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return scheme + host + ":" + config.port;
    }

    static boolean matchesHttpProxyAuth(NetworkProxy.Config config, String host) {
        return config.type == Proxy.Type.HTTP && !config.user.isEmpty()
                && host != null && host.toLowerCase(Locale.ROOT)
                .equals(config.host.toLowerCase(Locale.ROOT));
    }
}
