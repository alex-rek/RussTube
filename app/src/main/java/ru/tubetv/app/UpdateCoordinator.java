package ru.tubetv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;

/** Activity-owned prompts; downloading and verification run in UpdateManager's executor. */
final class UpdateCoordinator {
    private static final int INSTALL_SOURCE_REQUEST = 4107;

    private final Activity activity;
    private UpdateManager.Release pendingRelease;
    private File pendingApk;
    private AlertDialog progress;
    private AlertDialog checking;
    private boolean resumed;
    private boolean promptVisible;
    private boolean permissionRequested;
    private String offeredVersion;
    private String pendingError;

    UpdateCoordinator(Activity activity) { this.activity = activity; }

    void onResume() {
        resumed = true;
        if (pendingError != null) {
            String error = pendingError;
            pendingError = null;
            showError(error);
        }
        if (pendingApk != null && !permissionRequested) install(pendingApk);
        check();
        offer();
    }

    void onPause() { resumed = false; }

    void onDestroy() {
        resumed = false;
        if (progress != null) progress.dismiss();
        if (checking != null) checking.dismiss();
    }

    void onActivityResult(int requestCode) {
        if (requestCode != INSTALL_SOURCE_REQUEST) return;
        permissionRequested = false;
        if (pendingApk == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls()) install(pendingApk);
        else {
            pendingApk = null;
            showError("Разрешение на установку обновления не выдано");
        }
    }

    private void check() {
        UpdateManager.check(activity, release -> activity.runOnUiThread(() -> {
            if (!alive() || release.version.equals(offeredVersion)) return;
            pendingRelease = release;
            offer();
        }));
    }

    void checkNow() {
        if (checking != null || !alive()) return;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Проверка обновлений")
                .setMessage("Проверяем GitHub…")
                .setCancelable(false)
                .create();
        checking = dialog;
        dialog.show();
        UpdateManager.checkNow(activity, (release, error) -> activity.runOnUiThread(() -> {
            if (checking != dialog) return;
            checking = null;
            dialog.dismiss();
            if (!alive() || !resumed) return;
            if (error != null) {
                showError("Не удалось проверить обновления: " + error);
            } else if (release == null) {
                new AlertDialog.Builder(activity)
                        .setTitle("Обновлений нет")
                        .setMessage("Установлена последняя версия "
                                + (BuildConfig.MOBILE_VARIANT ? "RussTube" : "RussTubeTV") + ".")
                        .setPositiveButton("OK", null).show();
            } else {
                offeredVersion = null;
                pendingRelease = release;
                offer();
            }
        }));
    }

    private void offer() {
        if (!resumed || !alive() || pendingRelease == null || promptVisible) return;
        UpdateManager.Release release = pendingRelease;
        pendingRelease = null;
        if (release.version.equals(offeredVersion)) return;
        offeredVersion = release.version;
        promptVisible = true;
        String appName = BuildConfig.MOBILE_VARIANT ? "RussTube" : "RussTubeTV";
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Доступна версия " + release.version)
                .setMessage("Скачать и установить обновление " + appName + "?")
                .setNegativeButton("Позже", null)
                .setPositiveButton("Обновить", (ignored, which) -> download(release))
                .create();
        dialog.setOnDismissListener(ignored -> promptVisible = false);
        dialog.show();
    }

    private void download(UpdateManager.Release release) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Обновление " + (BuildConfig.MOBILE_VARIANT ? "RussTube" : "RussTubeTV"))
                .setMessage("Скачиваю APK…")
                .setCancelable(false)
                .create();
        progress = dialog;
        dialog.show();
        UpdateManager.download(activity, release, new UpdateManager.DownloadCallback() {
            @Override public void onProgress(int percent) {
                activity.runOnUiThread(() -> {
                    if (alive() && progress == dialog) dialog.setMessage(percent >= 0
                            ? "Скачиваю APK: " + percent + "%" : "Скачиваю APK…");
                });
            }

            @Override public void onReady(File apk) {
                activity.runOnUiThread(() -> {
                    if (!alive()) return;
                    finishProgress(dialog);
                    install(apk);
                });
            }

            @Override public void onError(String message) {
                activity.runOnUiThread(() -> {
                    if (!alive()) return;
                    finishProgress(dialog);
                    showError(message);
                });
            }
        });
    }

    private void finishProgress(AlertDialog dialog) {
        dialog.dismiss();
        if (progress == dialog) progress = null;
    }

    private void install(File apk) {
        pendingApk = apk;
        if (!resumed || !alive() || permissionRequested) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                permissionRequested = true;
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(settings, INSTALL_SOURCE_REQUEST);
            } catch (Exception error) {
                permissionRequested = false;
                pendingApk = null;
                showError("Не удалось открыть разрешение установки приложений");
            }
            return;
        }
        pendingApk = null;
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.setClipData(ClipData.newRawUri("RussTube update", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception error) {
            showError("Не удалось открыть системный установщик: " + error.getMessage());
        }
    }

    private void showError(String message) {
        if (!alive()) return;
        if (!resumed) { pendingError = message; return; }
        new AlertDialog.Builder(activity).setTitle("Не удалось обновить RussTube")
                .setMessage(message).setPositiveButton("Закрыть", null).show();
    }

    private boolean alive() { return !activity.isFinishing() && !activity.isDestroyed(); }
}
