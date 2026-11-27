package ru.tubetv.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.URL;

public final class RutubeAccountSessionTest {
    @Test public void credentialsStayOnRutubeHttpsApi() throws Exception {
        assertTrue(RutubeAccountSession.isFirstPartyApi(
                new URL("https://rutube.ru/api/profile/user")));
        assertFalse(RutubeAccountSession.isFirstPartyApi(
                new URL("http://rutube.ru/api/profile/user")));
        assertFalse(RutubeAccountSession.isFirstPartyApi(
                new URL("https://rutube.ru.evil.test/api/profile/user")));
        assertFalse(RutubeAccountSession.isFirstPartyApi(
                new URL("https://cdn.rutube.ru/api/profile/user")));
        assertFalse(RutubeAccountSession.isFirstPartyApi(
                new URL("https://rutube.ru/video/abc")));
    }
}
