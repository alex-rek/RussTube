package ru.tubetv.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VideoVisibilityTest {
    private static VideoItem video(String source, String channel) {
        return new VideoItem(source, "Фильм", "", "", "", "").withChannel("1", channel, "", false);
    }

    @Test public void excludesOnlyRutubePremierChannel() {
        assertFalse(VideoVisibility.showInDiscovery(video("RUTUBE", "PREMIER")));
        assertFalse(VideoVisibility.showInDiscovery(video("RUTUBE", "RUTUBE × PREMIER")));
        assertTrue(VideoVisibility.showInDiscovery(video("RUTUBE", "Премьера кино")));
        assertTrue(VideoVisibility.showInDiscovery(video("VK VIDEO", "PREMIER")));
    }
}
