package ru.tubetv.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class TvChannelStoreTest {
    private static final String RUTUBE_ID = "0123456789abcdef0123456789abcdef";
    private static final String NEXT_ID = "fedcba9876543210fedcba9876543210";

    @Test public void acceptsOnlyPermanentSupportedPages() {
        assertNotNull(TvChannelStore.link("https://rutube.ru/live/video/" + RUTUBE_ID + "/?t=10"));
        assertNotNull(TvChannelStore.link("https://vkvideo.ru/video-12_34?t=55s"));
        assertNotNull(TvChannelStore.link("https://ok.ru/video/1234567"));
        assertNull(TvChannelStore.link("https://rutube.ru/api/play/options/" + RUTUBE_ID));
        assertNull(TvChannelStore.link("https://evil.example/video/1234567"));
        assertNull(TvChannelStore.link("http://ok.ru/video/1234567"));
        assertNull(TvChannelStore.link("https://ok.ru@evil.example/video/1234567"));
        assertEquals("https://vkvideo.ru/video-12_34",
                TvChannelStore.link("https://vkvideo.ru/video-12_34?t=55s").pageUrl);
        TvChannelStore.Link keyed = TvChannelStore.link(
                "https://vkvideo.ru/video-12_34_2cec856843657d26?t=55s");
        assertNotNull(keyed);
        assertEquals("-12_34", keyed.id);
        assertEquals("https://vkvideo.ru/video-12_34_2cec856843657d26", keyed.pageUrl);
    }

    @Test public void rediscoveryKeepsUserSettingsAndDoesNotDuplicate() {
        List<TvChannelStore.Entry> catalog = new ArrayList<>();
        VideoItem first = live(RUTUBE_ID, "Первое имя");
        assertEquals(1, TvChannelStore.mergeEntries(catalog, Collections.singletonList(first)));
        TvChannelStore.Entry saved = catalog.get(0);
        saved.favorite = true;
        saved.hidden = true;
        saved.order = 17;
        saved.customTitle = true;
        saved.title = "Мой канал";
        assertEquals(0, TvChannelStore.mergeEntries(catalog, Collections.singletonList(first)));
        assertEquals(1, catalog.size());
        assertTrue(TvChannelStore.mergeEntries(catalog,
                Collections.singletonList(live(NEXT_ID, "Новое имя"))) > 0);
        assertEquals(1, catalog.size());
        assertEquals(NEXT_ID, saved.id);
        assertEquals("Мой канал", saved.title);
        assertEquals(17, saved.order);
        assertTrue(saved.favorite);
        assertTrue(saved.hidden);
    }

    @Test public void sameTitleOnDifferentPlatformsIsNotIdentity() {
        List<TvChannelStore.Entry> catalog = new ArrayList<>();
        VideoItem rutube = live(RUTUBE_ID, "Общее имя");
        VideoItem ok = new VideoItem("OK", "Общее имя", "", "",
                "https://ok.ru/videoembed/123", "https://ok.ru/video/123")
                .withChannel("another", "Вещатель", "", true);
        TvChannelStore.mergeEntries(catalog, java.util.Arrays.asList(rutube, ok));
        assertEquals(2, catalog.size());
    }

    @Test public void vkAccessKeyChangesKeepOneCatalogIdentity() {
        List<TvChannelStore.Entry> catalog = new ArrayList<>();
        VideoItem first = new VideoItem("VK VIDEO", "Первый", "", "",
                "https://vkvideo.ru/video-12_34_oldkey", "https://vkvideo.ru/video-12_34_oldkey")
                .withChannel("-12", "Телеканал", "", true);
        VideoItem next = new VideoItem("VK VIDEO", "Первый", "", "",
                "https://vkvideo.ru/video-12_34_newkey", "https://vkvideo.ru/video-12_34_newkey")
                .withChannel("-12", "Телеканал", "", true);
        assertEquals(1, TvChannelStore.mergeEntries(catalog, Collections.singletonList(first)));
        catalog.get(0).favorite = true;
        TvChannelStore.mergeEntries(catalog, Collections.singletonList(next));
        assertEquals(1, catalog.size());
        assertTrue(catalog.get(0).favorite);
        assertEquals(next.pageUrl, catalog.get(0).pageUrl);
    }

    @Test public void http403AloneDoesNotClaimRegionalBlock() {
        assertEquals(TvChannelStore.Status.UNKNOWN, TvChannelStore.failureStatus("HTTP 403"));
        assertEquals(TvChannelStore.Status.PAUSED,
                TvChannelStore.failureStatus("Эфир приостановлен"));
    }

    private static VideoItem live(String id, String title) {
        return new VideoItem("RUTUBE", title, "", "https://example.org/logo.png",
                "https://rutube.ru/play/embed/" + id,
                "https://rutube.ru/live/video/" + id + "/")
                .withChannel("channel-1", "Вещатель", "https://rutube.ru/channel/1", true);
    }
}
