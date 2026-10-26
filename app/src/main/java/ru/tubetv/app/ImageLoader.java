package ru.tubetv.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ImageLoader {
    private final LruCache<String, Bitmap> cache;
    private final ThumbnailDiskCache disk;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>(96);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(3, 3,
            0, TimeUnit.SECONDS, queue);
    // Only accessed on the UI thread. Queued work never holds an Activity or View strongly.
    private final Map<String, Request> pending = new HashMap<>();
    private final Set<String> scheduledPrefetch = new HashSet<>();
    private long lastPruneAt;

    ImageLoader(Context context) {
        disk = new ThumbnailDiskCache(context.getCacheDir());
        int sizeKb = (int) Math.min(16 * 1024L, Runtime.getRuntime().maxMemory() / 1024L / 16L);
        cache = new LruCache<String, Bitmap>(sizeKb) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    void load(String url, ImageView target) {
        load(null, url, target);
    }

    void load(String platform, String url, ImageView target) {
        int width = target.getWidth();
        if (width <= 0) width = Math.min(target.getResources().getDisplayMetrics().widthPixels,
                Math.round(360 * target.getResources().getDisplayMetrics().density));
        load(platform, url, target, width, Math.max(1, width * 9 / 16));
    }

    void load(String url, ImageView target, int width, int height) {
        load(null, url, target, width, height);
    }

    void load(String platform, String url, ImageView target, int width, int height) {
        long startedBinding = SystemClock.elapsedRealtimeNanos();
        String key = platform + ":" + key(url, width, height);
        boolean same = key.equals(target.getTag());
        target.setTag(key);
        if (!same) target.setImageDrawable(new ColorDrawable(0xff10243b));
        long now = SystemClock.uptimeMillis();
        if (pending.size() > 48 || (pending.size() > 12 && now - lastPruneAt >= 120)) {
            pruneRequests();
            lastPruneAt = now;
        }
        if (url == null || url.isEmpty()) return;
        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            long bindUs = (SystemClock.elapsedRealtimeNanos() - startedBinding) / 1000;
            if (BuildConfig.DEBUG && bindUs > 5_000)
                Log.i("RussTubeImagePerf", "source=memory host=" + host(url)
                        + " bindUs=" + bindUs);
            return;
        }
        Request request = pending.get(key);
        if (request != null) {
            for (WeakReference<ImageView> ref : request.targets) if (ref.get() == target) return;
            request.targets.add(new WeakReference<>(target));
            if (request.prefetch && queue.remove(request)) {
                request.prefetch = false;
                queue.offerFirst(request);
            }
            return;
        }
        request = new Request(platform, url, key, width, height, false);
        request.targets.add(new WeakReference<>(target));
        makeRoom();
        pending.put(key, request);
        executor.execute(request);
        if (queue.remove(request)) queue.offerFirst(request);
    }

    void prefetch(String url, int width, int height) {
        prefetch(null, url, width, height);
    }

    void prefetch(String platform, String url, int width, int height) {
        if (url == null || url.isEmpty() || scheduledPrefetch.size() >= 16) return;
        String key = platform + ":" + key(url, width, height);
        if (cache.get(key) != null || pending.containsKey(key) || !scheduledPrefetch.add(key)) return;
        ui.postDelayed(() -> runPrefetch(platform, url, key, width, height, 0), 350);
    }

    private void runPrefetch(String platform, String url, String key,
                             int width, int height, int attempt) {
        if (cache.get(key) != null || pending.containsKey(key)) {
            scheduledPrefetch.remove(key); return;
        }
        // Let visible requests consume all workers before starting the next neighbor.
        if (executor.getActiveCount() > 0 || !queue.isEmpty()) {
            if (attempt < 30) ui.postDelayed(() -> runPrefetch(platform, url, key, width, height, attempt + 1), 200);
            else scheduledPrefetch.remove(key);
            return;
        }
        scheduledPrefetch.remove(key);
        Request request = new Request(platform, url, key, width, height, true);
        pending.put(key, request);
        executor.execute(request);
    }

    private void makeRoom() {
        if (queue.remainingCapacity() == 0) {
            Request dropped = (Request) queue.pollLast();
            if (dropped != null) pending.remove(dropped.key);
        }
    }

    void trimMemory() { cache.evictAll(); }

    private void pruneRequests() {
        Iterator<Request> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Request request = iterator.next();
            Iterator<WeakReference<ImageView>> targets = request.targets.iterator();
            while (targets.hasNext()) {
                ImageView target = targets.next().get();
                if (target == null || !request.key.equals(target.getTag())) targets.remove();
            }
            if (request.targets.isEmpty() && !request.prefetch) {
                request.cancelled = true;
                if (executor.remove(request)) iterator.remove();
                else { request.disconnect(); iterator.remove(); }
            }
        }
    }

    private final class Request implements Runnable {
        final String platform, url, key;
        final int width, height;
        boolean prefetch;
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        final List<WeakReference<ImageView>> targets = new ArrayList<>();
        final long queuedAt = SystemClock.elapsedRealtime();
        Request(String platform, String url, String key, int width, int height, boolean prefetch) {
            this.platform = platform; this.url = url; this.key = key;
            this.width = width; this.height = height;
            this.prefetch = prefetch;
        }

        void disconnect() {
            HttpURLConnection active = connection;
            if (active != null) active.disconnect();
        }

        @Override public void run() {
            Bitmap result = null;
            long started = SystemClock.elapsedRealtime();
            long networkEnded = started;
            long decodeEnded = started;
            String source = "disk";
            int bytesCount = 0;
            byte[] downloaded = null;
            boolean storeInMemory = true;
            try {
                if (cancelled) return;
                result = cache.get(key);
                if (result == null) {
                    byte[] bytes = disk.read(url);
                    if (bytes == null) {
                        source = "network";
                        connection = NetworkProxy.openConnection(platform, url);
                        connection.setConnectTimeout(5000);
                        connection.setReadTimeout(5000);
                        int code = connection.getResponseCode();
                        if (code < 200 || code >= 300) {
                            Log.w("RussTubeImages", "HTTP " + code + " for " + host(url));
                            return;
                        }
                        bytes = BoundedHttpReader.read(connection.getInputStream(), 3 * 1024 * 1024);
                        networkEnded = SystemClock.elapsedRealtime();
                        storeInMemory = ThumbnailDiskCache.cacheAllowed(connection);
                        if (storeInMemory) downloaded = bytes;
                    } else networkEnded = SystemClock.elapsedRealtime();
                    if (cancelled) return;
                    bytesCount = bytes.length;
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height);
                    result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    decodeEnded = SystemClock.elapsedRealtime();
                    if (result != null && !cancelled && storeInMemory) cache.put(key, result);
                }
            } catch (Exception error) {
                if (!cancelled) Log.w("RussTubeImages", "Failed to load " + host(url), error);
            } finally {
                Bitmap bitmap = result;
                String origin = source;
                int size = bytesCount;
                long readMs = networkEnded - started;
                long decodeMs = decodeEnded - networkEnded;
                long finishedDecodeAt = decodeEnded;
                ui.post(() -> {
                    if (pending.get(key) != this) return;
                    pending.remove(key);
                    for (WeakReference<ImageView> ref : targets) {
                        ImageView target = ref.get();
                        if (bitmap != null && !cancelled && target != null && key.equals(target.getTag()))
                            target.setImageBitmap(bitmap);
                    }
                    if (BuildConfig.DEBUG) Log.i("RussTubeImagePerf", "source=" + origin
                            + " host=" + host(url) + " queueMs=" + (started - queuedAt)
                            + " readMs=" + readMs
                            + " decodeMs=" + decodeMs
                            + " bindMs=" + (SystemClock.elapsedRealtime() - finishedDecodeAt)
                            + " bytes=" + size);
                });
                if (downloaded != null && !cancelled && bitmap != null)
                    disk.write(url, downloaded, connection);
                disconnect();
            }
        }
    }

    private static String key(String url, int width, int height) {
        int w = Math.max(64, ((Math.max(1, width) + 63) / 64) * 64);
        int h = Math.max(64, ((Math.max(1, height) + 63) / 64) * 64);
        return url + "#" + w + "x" + h;
    }

    private static String host(String url) {
        try { return new URL(url).getHost(); } catch (Exception ignored) { return "image"; }
    }

    static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
        int sample = 1;
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2;
        // Panoramic and portrait images need a ceiling even if their short side fits.
        long maxPixels = (long) targetWidth * targetHeight * 4;
        while ((long) (width / sample) * (height / sample) > maxPixels) sample *= 2;
        return sample;
    }
}
