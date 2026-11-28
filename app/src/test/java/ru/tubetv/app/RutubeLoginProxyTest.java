package ru.tubetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.Proxy;

public final class RutubeLoginProxyTest {
    @Test public void rulesUseSelectedProxyType() {
        assertEquals("http://proxy.example:8080", RutubeLoginProxy.proxyRule(
                new NetworkProxy.Config(Proxy.Type.HTTP, "proxy.example", 8080, "", "")));
        assertEquals("socks://[2001:db8::1]:1080", RutubeLoginProxy.proxyRule(
                new NetworkProxy.Config(Proxy.Type.SOCKS, "2001:db8::1", 1080, "", "")));
    }

    @Test public void proxyCredentialsOnlyGoToConfiguredHttpProxy() {
        NetworkProxy.Config proxy = new NetworkProxy.Config(
                Proxy.Type.HTTP, "proxy.example", 8080, "user", "secret");
        assertTrue(RutubeLoginProxy.matchesHttpProxyAuth(proxy, "PROXY.EXAMPLE"));
        assertFalse(RutubeLoginProxy.matchesHttpProxyAuth(proxy, "rutube.ru"));
        assertFalse(RutubeLoginProxy.matchesHttpProxyAuth(proxy, "proxy.example.evil.test"));
        assertFalse(RutubeLoginProxy.matchesHttpProxyAuth(new NetworkProxy.Config(
                Proxy.Type.SOCKS, "proxy.example", 1080, "user", "secret"), "proxy.example"));
    }
}
