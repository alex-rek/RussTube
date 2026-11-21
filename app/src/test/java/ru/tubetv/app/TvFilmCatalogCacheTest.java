package ru.tubetv.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import org.junit.Test;

public final class TvFilmCatalogCacheTest {
    @Test public void filmCatalogIsFreshForThreeDays() {
        long saved = 1_000_000L;
        TvFilmCatalogCache.Snapshot cache = new TvFilmCatalogCache.Snapshot(
                Collections.emptyList(), saved);
        assertTrue(cache.isFresh(saved));
        assertTrue(cache.isFresh(saved + 3L * 24 * 60 * 60 * 1000 - 1));
        assertFalse(cache.isFresh(saved + 3L * 24 * 60 * 60 * 1000));
        assertFalse(cache.isFresh(saved - 1));
    }
}
