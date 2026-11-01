package ru.tubetv.app;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LocalVideoIdentityTest {
    @Test public void vkRoundTripDoesNotCarrySignedUrl() throws Exception {
        VideoItem item = new VideoItem("VK VIDEO", "Пример", "", "",
                "https://vkvideo.ru/video-1_2?access_key=private",
                "https://vkvideo.ru/video-1_2?access_key=private");
        JSONObject identity = LocalVideoIdentity.from(item);
        assertEquals("-1_2", identity.getString("contentId"));
        assertEquals("https://vkvideo.ru/video-1_2", LocalVideoIdentity.toVideo(identity).playUrl);
        org.junit.Assert.assertFalse(identity.toString().contains("private"));
    }

    @Test public void peerTubePreservesInstanceInId() throws Exception {
        VideoItem item = new VideoItem("PEERTUBE", "Видео", "", "",
                "https://tube.example/api/v1/videos/abc",
                "https://tube.example/videos/watch/abc");
        JSONObject identity = LocalVideoIdentity.from(item);
        assertEquals("tube.example/abc", identity.getString("contentId"));
        assertEquals(item.playUrl, LocalVideoIdentity.toVideo(identity).playUrl);
    }

    @Test public void vkPathSuffixIsNotSentToTv() throws Exception {
        VideoItem item = new VideoItem("VK VIDEO", "Видео", "", "",
                "https://vkvideo.ru/video-228049108_456248157_8982e7a6214d3c3a",
                "https://vkvideo.ru/video-228049108_456248157_8982e7a6214d3c3a");
        JSONObject identity = LocalVideoIdentity.from(item);
        assertEquals("-228049108_456248157", identity.getString("contentId"));
        assertEquals("https://vkvideo.ru/video-228049108_456248157",
                LocalVideoIdentity.toVideo(identity).playUrl);
        org.junit.Assert.assertFalse(identity.toString().contains("8982e7a6214d3c3a"));
    }
}
