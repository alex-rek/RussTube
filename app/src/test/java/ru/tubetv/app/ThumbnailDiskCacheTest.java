package ru.tubetv.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;

public final class ThumbnailDiskCacheTest {
    private static HttpURLConnection response(String cacheControl) throws Exception {
        return new HttpURLConnection(new URL("https://example.com/thumb")) {
            @Override public String getHeaderField(String name) {
                return "Cache-Control".equalsIgnoreCase(name) ? cacheControl : null;
            }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
    }

    @Test public void exactUrlIncludingAuthParametersIsTheCacheKey() throws Exception {
        File directory = Files.createTempDirectory("thumbnail-cache").toFile();
        ThumbnailDiskCache cache = new ThumbnailDiskCache(directory);
        byte[] image = {1, 2, 3};
        cache.write("https://example.com/thumb?token=one", image, response("max-age=60"));
        assertArrayEquals(image, cache.read("https://example.com/thumb?token=one"));
        assertNull(cache.read("https://example.com/thumb?token=two"));
    }

    @Test public void noStoreAndExpiredResponsesAreNotReadBack() throws Exception {
        File directory = Files.createTempDirectory("thumbnail-cache").toFile();
        ThumbnailDiskCache cache = new ThumbnailDiskCache(directory);
        cache.write("https://example.com/private", new byte[]{1}, response("no-store"));
        cache.write("https://example.com/stale", new byte[]{2}, response("max-age=0"));
        assertNull(cache.read("https://example.com/private"));
        assertNull(cache.read("https://example.com/stale"));
    }

    @Test public void tvScaleHasTwentyOneExactValues() {
        assertEquals("0.50x", TvScalePreferences.label(0));
        assertEquals("1.00x", TvScalePreferences.label(10));
        assertEquals("1.50x", TvScalePreferences.label(20));
    }
}
