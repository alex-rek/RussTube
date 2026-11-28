package ru.tubetv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The account owner signs in on RUTUBE's own page; RussTube never reads credentials. */
public final class RutubeAccountActivity extends Activity {
    private static final String ACCOUNT_PAGE = "https://rutube.ru/my/";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RutubeLoginProxy loginProxy = new RutubeLoginProxy();
    private WebView web;
    private TextView status;
    private TextView site;
    private TextView check;
    private TextView signOut;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TvAppearancePreferences.background(this));
        root.setPadding(dp(12), dp(12), dp(12), dp(8));

        TextView title = label("Аккаунт RUTUBE", 22);
        root.addView(title);
        status = label("Откройте страницу входа RUTUBE и войдите в аккаунт.", 15);
        status.setTextColor(TvAppearancePreferences.secondary(this));
        root.addView(status);
        site = label("Сайт: rutube.ru", 13);
        site.setTextColor(TvAppearancePreferences.secondary(this));
        root.addView(site);

        LinearLayout actions = new LinearLayout(this);
        check = action("Проверить вход", () -> verify(true));
        actions.addView(check, new LinearLayout.LayoutParams(0, dp(52), 1));
        signOut = action("Выйти", this::confirmSignOut);
        actions.addView(signOut, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView close = action("Закрыть", this::finish);
        actions.addView(close, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(actions);
        updateStatus();

        try { web = new WebView(this); }
        catch (RuntimeException unavailable) {
            status.setText("На устройстве нет компонента для входа через сайт RUTUBE.");
            setContentView(root);
            return;
        }
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url,
                                                android.graphics.Bitmap favicon) {
                String host = Uri.parse(url).getHost();
                site.setText("Сайт: " + (host == null ? "" : host));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"https".equalsIgnoreCase(request.getUrl().getScheme());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("https://");
            }
            @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                            String host, String realm) {
                NetworkProxy.Config proxy = NetworkProxy.load(RutubeAccountActivity.this);
                if (NetworkProxy.isSourceEnabled(RutubeAccountActivity.this, "RUTUBE")
                        && RutubeLoginProxy.matchesHttpProxyAuth(proxy, host)) {
                    handler.proceed(proxy.user, proxy.password);
                } else handler.cancel();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  WebResourceError error) {
                if (!request.isForMainFrame()) return;
                status.setText("Страница входа не загрузилась: "
                        + error.getDescription() + ". Проверьте соединение и прокси RUTUBE.");
            }
        });
        if (!BuildConfig.MOBILE_VARIANT) {
            web.setFocusable(true);
            web.setOnKeyListener((view, keyCode, event) -> {
                int mapped;
                int meta = event.getMetaState();
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) mapped = KeyEvent.KEYCODE_TAB;
                else if (keyCode == KeyEvent.KEYCODE_DPAD_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    mapped = KeyEvent.KEYCODE_TAB;
                    meta |= KeyEvent.META_SHIFT_ON;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
                    mapped = KeyEvent.KEYCODE_ENTER;
                else return false;
                return web.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(),
                        event.getAction(), mapped, event.getRepeatCount(), meta));
            });
        }
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        loginProxy.prepare(this, () -> {
            if (!isFinishing() && !isDestroyed() && web != null) web.loadUrl(ACCOUNT_PAGE);
        }, message -> status.setText(message));
        check.requestFocus();
    }

    private void verify(boolean closeOnSuccess) {
        check.setEnabled(false);
        status.setText("Проверяем вход на RUTUBE…");
        worker.execute(() -> {
            String name = null;
            String error = null;
            try { name = RutubeAccountSession.verify(this); }
            catch (Exception failure) { error = failure.getMessage(); }
            String profileName = name;
            String message = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                check.setEnabled(true);
                if (message != null) status.setText("Не удалось проверить вход: " + message);
                else if (profileName == null || profileName.isEmpty())
                    status.setText("Вход ещё не завершён. Войдите на странице RUTUBE ниже.");
                else {
                    updateStatus();
                    Toast.makeText(this, "Вход выполнен: " + profileName, Toast.LENGTH_SHORT).show();
                    if (closeOnSuccess) finish();
                }
            });
        });
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this).setTitle("Выйти из RUTUBE?")
                .setMessage("Локальные подписки и избранное RussTube сохранятся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Выйти", (dialog, which) -> {
                    if (web != null) {
                        web.loadUrl("about:blank");
                        web.clearHistory();
                        web.clearCache(true);
                    }
                    RutubeAccountSession.signOut(this, () -> runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        updateStatus();
                        if (web != null) web.loadUrl(ACCOUNT_PAGE);
                    }));
                }).show();
    }

    private void updateStatus() {
        String name = RutubeAccountSession.savedName(this);
        status.setText(name.isEmpty() ? "Войдите на странице RUTUBE и нажмите «Проверить вход»."
                : "Вход выполнен: " + name);
        signOut.setVisibility(name.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private TextView action(String text, Runnable click) {
        TextView row = label(text, 15);
        row.setGravity(Gravity.CENTER);
        row.setFocusable(true);
        row.setBackgroundColor(TvAppearancePreferences.panelSurface(this));
        row.setOnClickListener(v -> click.run());
        row.setOnFocusChangeListener((v, focused) -> row.setBackgroundColor(focused
                ? TvAppearancePreferences.focus(this) : TvAppearancePreferences.panelSurface(this)));
        return row;
    }

    private TextView label(String text, int size) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(TvAppearancePreferences.text(this));
        label.setPadding(dp(8), dp(5), dp(8), dp(5));
        return label;
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        loginProxy.clear(this);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
