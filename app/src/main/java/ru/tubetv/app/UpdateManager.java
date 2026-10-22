package ru.tubetv.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateManager {
    private static final String RELEASES_URL =
            "https://api.github.com/repos/alex-rek/RussTube/releases?per_page=20";
    private static final String DOWNLOAD_PREFIX =
            "https://github.com/alex-rek/RussTube/releases/download/";
    private static final String USER_AGENT = "RussTube/" + BuildConfig.VERSION_NAME + " Android";
    private static final int TIMEOUT_MS = 10_000;
    private static final long CHECK_INTERVAL_MS = 5 * 60_000L;
    private static final long MAX_APK_SIZE = 150L * 1024L * 1024L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean CHECK_RUNNING = new AtomicBoolean();
    private static volatile Release cachedUpdate;
    private static volatile long lastCheckStartedAt;

    interface CheckCallback { void onUpdate(Release release); }
    interface DownloadCallback {
        void onProgress(int percent);
        void onReady(File apk);
        void onError(String message);
    }

    static final class Release {
        final String version;
        final String apkUrl;
        final String digest;
        final String checksumUrl;
        final long size;

        Release(String version, String apkUrl, String digest, String checksumUrl, long size) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.digest = digest;
            this.checksumUrl = checksumUrl;
            this.size = size;
        }
    }

    static void check(Context context, CheckCallback callback) {
        Context app = context.getApplicationContext();
        Release cached = cachedUpdate;
        if (cached != null) {
            callback.onUpdate(cached);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastCheckStartedAt != 0L && now - lastCheckStartedAt < CHECK_INTERVAL_MS) return;
        if (!CHECK_RUNNING.compareAndSet(false, true)) return;
        lastCheckStartedAt = now;
        EXECUTOR.execute(() -> {
            try {
                Release release = findUpdate(readText(RELEASES_URL, BoundedHttpReader.JSON_LIMIT),
                        BuildConfig.MOBILE_VARIANT, currentVersion(app));
                if (release == null) return;
                cachedUpdate = release;
                callback.onUpdate(release);
            } catch (Exception ignored) {
                // An update check must never delay startup or show a network error.
            } finally {
                CHECK_RUNNING.set(false);
            }
        });
    }

    static void download(Context context, Release release, DownloadCallback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            File partial = null;
            try {
                String expected = release.digest;
                if ((expected == null || expected.isEmpty()) && release.checksumUrl != null) {
                    String checksum = readText(release.checksumUrl, 4096).trim();
                    int separator = checksum.indexOf(' ');
                    expected = normalizeDigest(separator < 0 ? checksum : checksum.substring(0, separator));
                }
                if (expected == null || expected.length() != 64) {
                    throw new Exception("GitHub не отдал контрольную сумму APK");
                }
                File directory = new File(app.getCacheDir(), "updates");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new Exception("Не удалось создать каталог обновления");
                }
                String filename = (BuildConfig.MOBILE_VARIANT ? "RussTube-Mobile-" : "RussTube-TV-")
                        + release.version + ".apk";
                partial = new File(directory, filename + ".part");
                File ready = new File(directory, filename);
                if (partial.exists()) partial.delete();

                HttpURLConnection connection = open(release.apkUrl);
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                long total = release.size > 0 ? release.size : connection.getContentLength();
                long loaded = 0;
                int reported = -1;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(partial)) {
                    if (total > MAX_APK_SIZE) throw new Exception("APK обновления слишком большой");
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        loaded += count;
                        if (loaded > MAX_APK_SIZE) throw new Exception("APK обновления слишком большой");
                        output.write(buffer, 0, count);
                        sha256.update(buffer, 0, count);
                        int percent = total > 0 ? (int) Math.min(100L, loaded * 100L / total) : -1;
                        if (percent != reported) {
                            reported = percent;
                            callback.onProgress(percent);
                        }
                    }
                } finally {
                    connection.disconnect();
                }
                String actual = hex(sha256.digest());
                if (!expected.equalsIgnoreCase(actual)) throw new Exception("SHA-256 обновления не совпадает");
                PackageInfo archive = app.getPackageManager().getPackageArchiveInfo(partial.getAbsolutePath(), 0);
                if (archive == null || !app.getPackageName().equals(archive.packageName)
                        || !release.version.equals(cleanVersion(archive.versionName))
                        || archive.versionCode <= BuildConfig.VERSION_CODE) {
                    throw new Exception("Загружен APK другого приложения или версии");
                }
                if (ready.exists() && !ready.delete()) throw new Exception("Не удалось заменить старое обновление");
                if (!partial.renameTo(ready)) throw new Exception("Не удалось подготовить APK обновления");
                callback.onReady(ready);
            } catch (Exception error) {
                if (partial != null) partial.delete();
                String message = error.getMessage();
                callback.onError(message == null ? "Не удалось скачать обновление" : message);
            }
        });
    }

    private static String currentVersion(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return cleanVersion(info.versionName);
    }

    static Release findUpdate(String json, boolean mobile, String installedVersion) throws Exception {
        JSONArray releases = new JSONArray(json);
        Release newest = null;
        String prefix = mobile ? "RussTube-Mobile-" : "RussTube-TV-";
        for (int i = 0; i < releases.length(); i++) {
            JSONObject entry = releases.optJSONObject(i);
            if (entry == null || entry.optBoolean("draft")) continue;
            String tag = entry.optString("tag_name", "");
            String version = cleanVersion(tag);
            if (!tag.matches("v[0-9]+(?:\\.[0-9]+){1,3}")
                    || compareVersions(version, installedVersion) <= 0
                    || (newest != null && compareVersions(version, newest.version) <= 0)) continue;
            JSONArray assets = entry.optJSONArray("assets");
            if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset == null || !"uploaded".equals(asset.optString("state"))) continue;
                String name = asset.optString("name", "");
                if (!name.equals(prefix + version + ".apk")
                        && !name.equals(prefix + version + "-debug.apk")) continue;
                String url = asset.optString("browser_download_url", "");
                long size = asset.optLong("size");
                if (!url.startsWith(DOWNLOAD_PREFIX + tag + "/")
                        || size <= 0 || size > MAX_APK_SIZE) continue;
                String digest = normalizeDigest(asset.optString("digest", ""));
                if (!isDigest(digest)) digest = null;
                String checksumUrl = null;
                for (int k = 0; k < assets.length(); k++) {
                    JSONObject checksum = assets.optJSONObject(k);
                    if (checksum != null && (name + ".sha256").equals(checksum.optString("name"))
                            && "uploaded".equals(checksum.optString("state"))) {
                        String candidate = checksum.optString("browser_download_url", "");
                        if (candidate.startsWith(DOWNLOAD_PREFIX + tag + "/"))
                            checksumUrl = candidate;
                    }
                }
                if (digest == null && checksumUrl == null) continue;
                newest = new Release(version, url, digest, checksumUrl, size);
                break;
            }
        }
        return newest;
    }

    private static boolean isDigest(String digest) {
        return digest != null && digest.matches("[0-9a-f]{64}");
    }

    private static int compareVersions(String left, String right) {
        String[] a = cleanVersion(left).split("\\.");
        String[] b = cleanVersion(right).split("\\.");
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int av = i < a.length ? number(a[i]) : 0;
            int bv = i < b.length ? number(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int number(String value) {
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        try { return end == 0 ? 0 : Integer.parseInt(value.substring(0, end)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String cleanVersion(String value) {
        if (value == null) return "";
        value = value.trim();
        return value.startsWith("v") || value.startsWith("V") ? value.substring(1) : value;
    }

    private static String normalizeDigest(String value) {
        if (value == null) return null;
        value = value.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("sha256:") ? value.substring(7) : value;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static String readText(String address, int limit) throws Exception {
        HttpURLConnection connection = open(address);
        try {
            return BoundedHttpReader.readText(connection.getInputStream(), limit);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String address) throws Exception {
        HttpURLConnection connection = NetworkProxy.openConnection(address);
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/vnd.github+json,application/octet-stream,text/plain,*/*");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("GitHub: HTTP " + code);
            return connection;
        } catch (Exception error) {
            connection.disconnect();
            throw error;
        }
    }

    private UpdateManager() { }
}
