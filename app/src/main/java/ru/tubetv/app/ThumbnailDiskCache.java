package ru.tubetv.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** Bounded raw-response cache. Full URL is hashed, including expiring auth parameters. */
final class ThumbnailDiskCache {
    private static final long LIMIT = 64L * 1024 * 1024;
    private static final long DEFAULT_AGE = 6L * 60 * 60 * 1000;
    private final File directory;

    ThumbnailDiskCache(File appCacheDir) {
        directory = new File(appCacheDir, "thumbnails");
        if (!directory.exists()) directory.mkdirs();
    }

    synchronized byte[] read(String url) {
        File file = file(url);
        if (!file.isFile()) return null;
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            if (input.readLong() <= System.currentTimeMillis()) {
                file.delete(); return null;
            }
            long length = file.length() - 8;
            if (length <= 0 || length > 3 * 1024 * 1024) {
                file.delete(); return null;
            }
            byte[] bytes = new byte[(int) length];
            input.readFully(bytes);
            file.setLastModified(System.currentTimeMillis());
            return bytes;
        } catch (Exception error) {
            file.delete();
            return null;
        }
    }

    synchronized void write(String url, byte[] bytes, HttpURLConnection response) {
        if (!cacheAllowed(response)) return;
        String control = response.getHeaderField("Cache-Control");
        long age = DEFAULT_AGE;
        if (control != null) for (String directive : control.split(",")) {
            String value = directive.trim().toLowerCase(java.util.Locale.ROOT);
            if (value.startsWith("max-age=")) try {
                age = Math.max(0, Math.min(DEFAULT_AGE, Long.parseLong(value.substring(8)) * 1000));
            } catch (NumberFormatException ignored) { }
        }
        long expiry = response.getHeaderFieldDate("Expires", 0);
        if (expiry > 0) age = Math.min(age, Math.max(0, expiry - System.currentTimeMillis()));
        if (age <= 0 || bytes.length == 0) return;
        File destination = file(url);
        File temporary = new File(directory, destination.getName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(temporary))) {
            output.writeLong(System.currentTimeMillis() + age);
            output.write(bytes);
        } catch (IOException error) {
            temporary.delete(); return;
        }
        if (!temporary.renameTo(destination)) {
            destination.delete();
            if (!temporary.renameTo(destination)) temporary.delete();
        }
        trim();
    }

    static boolean cacheAllowed(HttpURLConnection response) {
        String control = response.getHeaderField("Cache-Control");
        if (control == null) return true;
        String lower = control.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("no-store") && !lower.contains("no-cache")
                && !lower.contains("max-age=0");
    }

    private void trim() {
        File[] files = directory.listFiles((dir, name) -> !name.endsWith(".tmp"));
        if (files == null) return;
        long size = 0;
        for (File file : files) size += file.length();
        if (size <= LIMIT) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File file : files) {
            if (size <= LIMIT) break;
            long length = file.length();
            if (file.delete()) size -= length;
        }
    }

    private File file(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return new File(directory, name + ".bin");
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
