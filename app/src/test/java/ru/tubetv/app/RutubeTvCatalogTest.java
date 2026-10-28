package ru.tubetv.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public final class RutubeTvCatalogTest {
    @Test public void extractsDistinctLiveIdsInPageOrder() {
        String first = "546602986e6a424d74d594876ddb3f04";
        String second = "afef67d151b5a607dee1ef0aa299a52c";
        String page = "<a href=\"/live/video/" + first + "/\">"
                + "<a href=\"/live/video/" + first + "/\">"
                + "<a href=\"/live\\/video\\/" + second + "/\">";
        assertEquals(Arrays.asList(first, second), RutubeTvCatalog.videoIds(page));
    }

    @Test public void extractsMovieLinksWithoutMixingLiveLinks() {
        String film = "afef67d151b5a607dee1ef0aa299a52c";
        String live = "546602986e6a424d74d594876ddb3f04";
        assertEquals(Arrays.asList(film), RutubeTvCatalog.filmIds(
                "<a href=\"/video/" + film + "/\">"
                        + "<a href=\"/live/video/" + live + "/\">"));
    }
}
