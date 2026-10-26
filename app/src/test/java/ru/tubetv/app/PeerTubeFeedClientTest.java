package ru.tubetv.app;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PeerTubeFeedClientTest {
    private static final String UUID = "3703fb66-41d0-485a-9008-1fe8674c5c4d";
    private static final String ORIGIN = "https://video.example";
    private static final String CHANNEL = ORIGIN + "/video-channels/science";

    private static String response() throws Exception {
        return new JSONObject().put("data", new org.json.JSONArray()
                .put(new JSONObject()
                        .put("uuid", UUID)
                        .put("url", ORIGIN + "/videos/watch/" + UUID)
                        .put("name", "Короткое видео")
                        .put("duration", 75)
                        .put("views", 142)
                        .put("publishedAt", "2026-10-20T11:00:00Z")
                        .put("previewUrl", ORIGIN + "/preview.jpg")
                        .put("channel", new JSONObject()
                                .put("name", "science")
                                .put("displayName", "Наука")
                                .put("url", CHANNEL)))
                .put(new JSONObject()
                        .put("uuid", "e8a1af4e-414a-4d58-bfe6-2146eed06d10")
                        .put("url", ORIGIN + "/videos/watch/e8a1af4e-414a-4d58-bfe6-2146eed06d10")
                        .put("name", "Скрытое видео")
                        .put("nsfw", true))).toString();
    }

    @Test public void searchIncludesShortVideoAndPlayableOriginUrl() throws Exception {
        List<VideoItem> videos = PeerTubeFeedClient.search("  кот и пёс  ", url -> {
            assertTrue(url.contains("%D0%BA%D0%BE%D1%82"));
            if (url.startsWith("https://sepiasearch.org/")) {
                assertTrue(url.contains("&nsfw=false&isLive=false"));
                return response();
            }
            assertTrue(url.startsWith("https://peerseek.video/v1/search?q="));
            assertTrue(url.contains("include_nsfw=false"));
            return new JSONObject().put("videos", new org.json.JSONArray()
                    .put(new JSONObject().put("videoUrl", ORIGIN + "/w/" + UUID)
                            .put("title", "Короткое видео").put("durationSeconds", 75)))
                    .toString();
        });
        assertEquals(1, videos.size());
        VideoItem video = videos.get(0);
        assertEquals(PeerTubeClient.SOURCE, video.source);
        assertEquals("Короткое видео", video.title);
        assertEquals(75_000L, video.durationMs);
        assertEquals(ORIGIN + "/api/v1/videos/" + UUID, video.playUrl);
        assertEquals(ORIGIN + "/preview.jpg", video.thumbnail);
        assertEquals(CHANNEL, video.channelId);
        assertEquals("Наука", video.channelName);
        assertEquals(142L, video.views);
        assertTrue(video.publishedAtMs > 0);
        assertFalse(video.isLive);
    }

    @Test public void peerSeekAddsDistinctVideosAndRejectsUnsafeLinks() throws Exception {
        String secondId = "e8a1af4e-414a-4d58-bfe6-2146eed06d10";
        JSONObject result = new JSONObject().put("videos", new org.json.JSONArray()
                .put(new JSONObject().put("videoUrl", ORIGIN + "/w/" + secondId)
                        .put("title", "Наука").put("durationSeconds", 120)
                        .put("thumbnailUrl", "https://images.example/thumb.jpg")
                        .put("channelHandle", "science").put("channel", "Наука")
                        .put("views", 900).put("publishedAt", "2026-10-20T11:00:00Z"))
                .put(new JSONObject().put("videoUrl", "http://insecure.example/w/" + UUID)
                        .put("title", "Недопустимый адрес")));
        List<VideoItem> videos = PeerTubeFeedClient.parsePeerSeek(result);
        assertEquals(1, videos.size());
        assertEquals(ORIGIN + "/api/v1/videos/" + secondId, videos.get(0).playUrl);
        assertEquals(CHANNEL, videos.get(0).channelId);
        assertEquals(900L, videos.get(0).views);
    }

    @Test public void peerSeekStillProvidesResultsWhenSepiaFails() throws Exception {
        List<VideoItem> videos = PeerTubeFeedClient.search("наука", url -> {
            if (url.startsWith("https://sepiasearch.org/")) throw new Exception("index unavailable");
            return new JSONObject().put("videos", new org.json.JSONArray()
                    .put(new JSONObject().put("videoUrl", ORIGIN + "/w/" + UUID)
                            .put("title", "Наука").put("durationSeconds", 120)))
                    .toString();
        });
        assertEquals(1, videos.size());
        assertEquals(ORIGIN + "/api/v1/videos/" + UUID, videos.get(0).playUrl);
    }

    @Test public void channelUsesOriginApiAndKeepsChannelIdentity() throws Exception {
        VideoItem subscribed = PeerTubeFeedClient.parse(new JSONObject(response())).get(0);
        List<VideoItem> videos = PeerTubeFeedClient.channelVideos(subscribed, url -> {
            assertEquals(ORIGIN + "/api/v1/video-channels/science/videos?count=20&start=0", url);
            return response();
        });
        assertEquals(1, videos.size());
        assertEquals(subscribed.channelKey(), videos.get(0).channelKey());
    }

    @Test public void liveFeedIncludesOnlyCurrentStreams() throws Exception {
        JSONObject live = new JSONObject()
                .put("uuid", UUID).put("url", ORIGIN + "/videos/watch/" + UUID)
                .put("name", "Прямой эфир").put("isLive", true).put("duration", 0)
                .put("channel", new JSONObject().put("url", CHANNEL).put("displayName", "Наука"));
        JSONObject recording = new JSONObject()
                .put("uuid", "e8a1af4e-414a-4d58-bfe6-2146eed06d10")
                .put("url", ORIGIN + "/videos/watch/e8a1af4e-414a-4d58-bfe6-2146eed06d10")
                .put("name", "Запись").put("isLive", false);
        List<VideoItem> videos = PeerTubeFeedClient.live("", url -> {
            assertTrue(url.contains("isLive=true&includeScheduledLive=false"));
            assertFalse(url.contains("search="));
            return new JSONObject().put("data", new org.json.JSONArray()
                    .put(live).put(recording)).toString();
        });
        assertEquals(1, videos.size());
        assertTrue(videos.get(0).isLive);
        assertEquals(CHANNEL, videos.get(0).channelId);
        assertEquals(0, videos.get(0).durationMs);
    }
}
