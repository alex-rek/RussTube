package ru.tubetv.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class BrowseFeaturesTest {
    private static VideoItem video(String source, String id, String title) {
        return new VideoItem(source, title, "", "", "https://example.test/" + id,
                "https://example.test/" + id, 100000);
    }

    @Test public void recommendationsExcludeWatchedAndDeduplicateAcrossQueries() {
        VideoItem seen = video("OK", "1", "Космос и планеты");
        VideoItem fresh = video("OK", "2", "Космос сегодня");
        List<VideoItem> result = RecommendationEngine.rank(Arrays.asList(seen, fresh, fresh),
                Collections.singletonList(seen));
        assertEquals(1, result.size());
        assertEquals(fresh.stableKey(), result.get(0).stableKey());
    }

    @Test public void recommendationsRankTopicMatchesBeforeUnrelatedVideos() {
        VideoItem seen = video("OK", "1", "Космос планеты");
        VideoItem unrelated = video("OK", "2", "Рецепты пирогов");
        VideoItem matching = video("RUTUBE", "3", "Космос телескопы");
        List<VideoItem> result = RecommendationEngine.rank(Arrays.asList(unrelated, matching),
                Collections.singletonList(seen));
        assertEquals(matching, result.get(0));
    }

    @Test public void recommendationQueriesDiscardNumbersAndStopWords() {
        List<String> queries = RecommendationEngine.queries(Collections.singletonList(
                video("OK", "1", "Как смотреть видео про космос 2026")));
        assertEquals(Collections.singletonList("космос"), queries);
        assertTrue(RecommendationEngine.queries(Collections.emptyList()).isEmpty());
    }

    @Test public void liveRecommendationsAreExcluded() {
        VideoItem live = video("RUTUBE", "1", "Новости").withChannel("123", "Новости", "", true);
        assertTrue(RecommendationEngine.rank(Collections.singletonList(live), Collections.emptyList()).isEmpty());
    }

    @Test public void metadataSurvivesQualityDurationAndJsonRoundTrip() throws Exception {
        VideoItem original = video("VK VIDEO", "1", "Эфир").withChannel("-123", "Наука", "https://vk.com/club123", true)
                .withMetrics(12345, 1_700_000_000_000L);
        VideoItem restored = VideoJson.decode(VideoJson.encode(original.withQuality(1920, 1080).withDuration(5000)));
        assertEquals(original.channelKey(), restored.channelKey());
        assertEquals("Наука", restored.channelName);
        assertEquals(original.channelUrl, restored.channelUrl);
        assertTrue(restored.isLive);
        assertEquals(1920, restored.maxWidth);
        assertEquals(5000, restored.durationMs);
        assertEquals(12345, restored.views);
        assertEquals(1_700_000_000_000L, restored.publishedAtMs);
    }

    @Test public void channelIdentityIsScopedToPlatform() {
        VideoItem rutube = video("RUTUBE", "1", "Ролик").withChannel("123", "Автор", "", false);
        VideoItem ok = video("OK", "1", "Ролик").withChannel("123", "Автор", "", false);
        assertNotEquals(rutube.channelKey(), ok.channelKey());
    }

    @Test public void liveSectionDoesNotInferLiveFromTitleOrZeroDuration() {
        VideoItem recorded = video("RUTUBE", "1", "Прямой эфир").withDuration(0);
        VideoItem live = video("RUTUBE", "2", "Новости").withChannel("1", "Автор", "", true);
        assertEquals(Collections.singletonList(live), FeedRepository.liveOnly(Arrays.asList(recorded, live)));
    }

    @Test public void rutubeRequiresCurrentlyOnAirNotJustLivestreamFlag() throws Exception {
        JSONObject ended = new JSONObject().put("id", "a").put("is_livestream", true).put("is_on_air", false);
        JSONObject active = new JSONObject().put("id", "b").put("is_livestream", true).put("is_on_air", true)
                .put("author", new JSONObject().put("id", 42).put("name", "Автор"));
        List<VideoItem> parsed = SearchClient.parseRutube(new JSONObject().put("results", new JSONArray().put(ended).put(active)));
        assertFalse(parsed.get(0).isLive);
        assertTrue(parsed.get(1).isLive);
        assertEquals("42", parsed.get(1).channelId);
        assertEquals("Автор", parsed.get(1).channelName);
    }

    @Test public void vkUpcomingAndFinishedStreamsDoNotAppearAsLive() throws Exception {
        assertTrue(ChannelMetadata.vkLive(new JSONObject().put("live", 1)));
        assertFalse(ChannelMetadata.vkLive(new JSONObject().put("live", 1).put("upcoming", 1)));
        assertFalse(ChannelMetadata.vkLive(new JSONObject().put("live", 1).put("live_status", "finished")));
        assertFalse(ChannelMetadata.vkLive(new JSONObject().put("live", 3)));
    }

    @Test public void legacySavedVideosLoadWithoutChannelMetadata() throws Exception {
        VideoItem legacy = VideoJson.decode(new JSONObject().put("source", "OK").put("title", "Видео"));
        assertEquals("", legacy.channelId);
        assertFalse(legacy.isLive);
    }

    @Test public void dzenUsesPublisherIdentityFromSourceObject() throws Exception {
        JSONObject entry = new JSONObject().put("title", "Космос")
                .put("link", "https://dzen.ru/video/watch/123abc")
                .put("video", new JSONObject().put("duration", 90))
                .put("source", new JSONObject().put("id", "publisher123").put("title", "Наука")
                        .put("shareLink", "https://dzen.ru/science"));
        VideoItem result = DzenClient.parseJsonItems(new JSONArray().put(entry),
                new java.util.LinkedHashSet<>(), 12).get(0);
        assertEquals("publisher123", result.channelId);
        assertEquals("Наука", result.channelName);
        assertEquals("https://dzen.ru/science", result.channelUrl);
    }

    @Test public void okAlbumSubscriptionPreservesStableChannelId() throws Exception {
        JSONObject entry = new JSONObject().put("name", "Космос")
                .put("movie", new JSONObject().put("id", "123456789").put("provider", "UploadedODKL"))
                .put("owner", new JSONObject().put("album", new JSONObject().put("id", "c123")
                        .put("name", "Новости")));
        JSONObject props = new JSONObject().put("videos", new JSONObject().put("list", new JSONArray().put(entry)));
        String encoded = props.toString().replace("&", "&amp;").replace("\"", "&quot;");
        VideoItem result = OkClient.parseSearchResults("<video-search-result data-props=\""
                + encoded + "\"></video-search-result>").get(0);
        assertEquals("album:c123", result.channelId);
        assertEquals("Новости", result.channelName);
    }

    @Test public void okChannelFeedExcludesUnrelatedAlbumCards() throws Exception {
        VideoItem channel = video("OK", "1", "Космос").withChannel("album:c123", "Новости", "", false);
        String matching = albumCard("c123", "123456789", "Космос");
        String unrelated = albumCard("c999", "987654321", "Реклама");
        List<VideoItem> result = OkClient.parseChannelVideos(matching + unrelated, channel);
        assertEquals(1, result.size());
        assertEquals("Космос", result.get(0).title);
        assertEquals(channel.channelKey(), result.get(0).channelKey());
    }

    private static String albumCard(String album, String id, String title) throws Exception {
        String metadata = new JSONObject().put("data", new JSONObject()
                .put("parentEntityType", "VIDEO_ALBUM").put("parentEntityId", album)
                .put("entityId", id)).toString().replace("\"", "&quot;");
        return "<div data-seen-params=\"" + metadata + "\"><a class=\"video-card_n ellip\">"
                + title + "</a></div>";
    }
}
