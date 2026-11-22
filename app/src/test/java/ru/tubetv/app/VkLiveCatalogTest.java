package ru.tubetv.app;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public final class VkLiveCatalogTest {
    @Test public void listsOnlyAccessibleOnlineStreams() throws Exception {
        JSONObject online = stream("Игра", true);
        JSONObject offline = stream("Запись", false);
        JSONObject root = new JSONObject().put("data", new JSONObject().put("streamBlogs",
                new JSONArray().put(entry("player_one", online))
                        .put(entry("player_two", offline))));
        List<VideoItem> videos = VkLiveCatalog.parse(root);
        assertEquals(1, videos.size());
        assertEquals("https://live.vkvideo.ru/player_one", videos.get(0).pageUrl);
        assertTrue(videos.get(0).isLive);
        assertEquals("VK VIDEO", videos.get(0).source);
    }

    @Test public void selectsHlsFromFreshResponseWithoutStoringIt() throws Exception {
        JSONObject playback = stream("Игра", true).put("data", new JSONArray().put(
                new JSONObject().put("sharedPlayerUrls", new JSONArray()
                        .put(new JSONObject().put("type", "live_cmaf")
                                .put("url", "https://cdn.example/live.mpd"))
                        .put(new JSONObject().put("type", "live_hls")
                                .put("url", "https://cdn.example/live.m3u8")))));
        assertEquals("https://cdn.example/live.m3u8",
                VkLiveCatalog.selectPlayback(playback).streamUrl);
        assertFalse(VkLiveCatalog.isLivePage("https://evil.example/player_one"));
    }

    @Test public void usesLiveUserAgentOnlyForVkLivePages() {
        assertEquals(VkLiveCatalog.USER_AGENT, PlaybackEngineFactory.vkUserAgent(
                "https://live.vkvideo.ru/totsamiybabichev"));
        assertEquals(VkWebClient.USER_AGENT, PlaybackEngineFactory.vkUserAgent(
                "https://vkvideo.ru/video-1_12345"));
    }

    private static JSONObject entry(String blogUrl, JSONObject stream) throws Exception {
        return new JSONObject().put("stream", stream).put("blog", new JSONObject()
                .put("blogUrl", blogUrl).put("owner", new JSONObject()
                        .put("id", 42).put("displayName", "Стример")));
    }

    private static JSONObject stream(String title, boolean online) throws Exception {
        return new JSONObject().put("title", title).put("isOnline", online)
                .put("hasAccess", true).put("isEnded", false);
    }
}
