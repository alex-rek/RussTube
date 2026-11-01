package ru.tubetv.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalLibraryCodecTest {
    @Test public void stripsQueryTokensFromPublicPage() {
        assertEquals("https://vkvideo.ru/video-1_2",
                LocalLibraryCodec.safePage("https://vkvideo.ru/video-1_2?access_key=secret"));
        assertEquals("https://vkvideo.ru/video-1_2",
                LocalLibraryCodec.safeVideoPage("VK VIDEO",
                        "https://vkvideo.ru/video-1_2_8982e7a6214d3c3a"));
    }

    @Test public void rejectsSignedMediaAndUserInfo() {
        assertEquals("", LocalLibraryCodec.safePage("https://cdn.example/path/stream.m3u8?token=secret"));
        assertEquals("", LocalLibraryCodec.safePage("https://user:pass@vkvideo.ru/video-1_2"));
        assertEquals("", LocalLibraryCodec.safePage("http://vkvideo.ru/video-1_2"));
    }

    @Test public void acceptsOnlyResolverPagesForTheNamedPlatform() {
        assertEquals("https://rutube.ru/video/abc/", LocalLibraryCodec.safeVideoPage(
                "RUTUBE", "https://rutube.ru/video/abc/?p=secret"));
        assertEquals("", LocalLibraryCodec.safeVideoPage(
                "RUTUBE", "https://other.example/video/abc"));
        assertEquals("https://tube.example/api/v1/videos/abc",
                LocalLibraryCodec.safeVideoPage("PEERTUBE",
                        "https://tube.example/api/v1/videos/abc?token=secret"));
    }

    @Test public void onlyLocalNumericHostsForPairing() {
        assertTrue(LocalPairingCode.localHost("192.168.1.2"));
        assertFalse(LocalPairingCode.localHost("example.com"));
        assertFalse(LocalPairingCode.localHost("8.8.8.8"));
    }
}
