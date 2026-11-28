package ru.tubetv.app;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Optional RUTUBE website session. No password or cookie is copied into app preferences. */
final class RutubeAccountSession {
    private static final String PREFS = "rutube_account";
    // The website uses the visitor endpoint for its active RUTUBE session.
    private static final String PROFILE_URL = "https://rutube.ru/api/v3/accounts/visitor/";

    static String savedName(Context context) {
        return context.getSharedPreferences(PREFS, 0).getString("name", "");
    }

    static boolean isSignedIn(Context context) {
        return !savedName(context).isEmpty();
    }

    static String verify(Context context) throws Exception {
        CookieManager.getInstance().flush();
        HttpURLConnection connection = NetworkProxy.openConnection("RUTUBE", PROFILE_URL);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        String cookie = cookies(PROFILE_URL);
        if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        try {
            if (connection.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                forget(context);
                return "";
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new Exception("RUTUBE: HTTP " + connection.getResponseCode());
            JSONObject profile = new JSONObject(BoundedHttpReader.readText(
                    connection.getInputStream(), BoundedHttpReader.JSON_LIMIT));
            if (profile.optLong("id", 0) <= 0)
                throw new Exception("RUTUBE не подтвердил профиль");
            String name = profile.optString("name", "RUTUBE").trim();
            if (name.isEmpty()) name = "RUTUBE";
            if (name.length() > 80) name = name.substring(0, 80);
            context.getSharedPreferences(PREFS, 0).edit().putString("name", name).apply();
            return name;
        } finally {
            connection.disconnect();
        }
    }

    static void applyToApi(Context context, HttpURLConnection connection) {
        URL url = connection.getURL();
        if (!isFirstPartyApi(url) || !isSignedIn(context)) return;
        String cookie = cookies(url.toString());
        if (cookie.isEmpty()) return;
        // Explicit Cookie headers must never follow a redirect to another host.
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Cookie", cookie);
    }

    static boolean isFirstPartyApi(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return "https".equalsIgnoreCase(url.getProtocol())
                && host.equals("rutube.ru")
                && url.getPath().startsWith("/api/");
    }

    static void signOut(Context context, Runnable complete) {
        forget(context);
        try {
            WebStorage.getInstance().deleteAllData();
            CookieManager manager = CookieManager.getInstance();
            manager.removeAllCookies(ignored -> {
                manager.flush();
                complete.run();
            });
        } catch (RuntimeException error) {
            complete.run();
        }
    }

    private static void forget(Context context) {
        context.getSharedPreferences(PREFS, 0).edit().remove("name").apply();
    }

    private static String cookies(String url) {
        try {
            String value = CookieManager.getInstance().getCookie(url);
            return value == null ? "" : value;
        } catch (RuntimeException error) {
            return "";
        }
    }
}
