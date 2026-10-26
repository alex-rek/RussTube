package ru.tubetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NetworkProxyTest {
    @Test public void platformSelectionsDefaultToEnabledAndRemainIndependent() {
        MemoryPreferences prefs = new MemoryPreferences();
        for (String source : NetworkProxy.SOURCES)
            assertTrue(NetworkProxy.isSourceEnabled(prefs, source));

        NetworkProxy.setSourceEnabled(prefs, "VK VIDEO", false);
        assertFalse(NetworkProxy.isSourceEnabled(prefs, "VK VIDEO"));
        assertTrue(NetworkProxy.isSourceEnabled(prefs, "RUTUBE"));
        assertTrue(NetworkProxy.isSourceEnabled(prefs, "PEERTUBE"));
        NetworkProxy.setSourceEnabled(prefs, "VK VIDEO", true);
        assertTrue(NetworkProxy.isSourceEnabled(prefs, "VK VIDEO"));
    }

    @Test public void firstPartyHostInferenceUsesDomainBoundaries() {
        assertEquals("RUTUBE", NetworkProxy.sourceForUrl("https://cdn.rutube.ru/thumb.jpg"));
        assertEquals("VK VIDEO", NetworkProxy.sourceForUrl("https://vkvideo.ru/video1"));
        assertEquals("OK", NetworkProxy.sourceForUrl("https://ok.ru/video/1"));
        assertEquals("ДЗЕН", NetworkProxy.sourceForUrl("https://dzen.ru/video/watch/1"));
        assertEquals("PEERTUBE", NetworkProxy.sourceForUrl("https://peerseek.video/search"));
        assertNull(NetworkProxy.sourceForUrl("https://notrutube.ru/video"));
        assertNull(NetworkProxy.sourceForUrl("https://video.example.org/stream.m3u8"));
    }
}
