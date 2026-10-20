package com.scivicslab.k8spups.resource;

import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decisions of the WebSocket proxy that need no running server. */
class SessionWebSocketProxyTest {

    @Test
    void aWebSocketUpgradeNeedsBothHeaders() {
        assertTrue(SessionWebSocketProxy.isWebSocketUpgrade("websocket", "Upgrade"));
        assertTrue(SessionWebSocketProxy.isWebSocketUpgrade("WebSocket", "keep-alive, Upgrade"));
        assertFalse(SessionWebSocketProxy.isWebSocketUpgrade("websocket", "keep-alive"));
        assertFalse(SessionWebSocketProxy.isWebSocketUpgrade("h2c", "Upgrade"));
        assertFalse(SessionWebSocketProxy.isWebSocketUpgrade(null, "Upgrade"));
        assertFalse(SessionWebSocketProxy.isWebSocketUpgrade("websocket", null));
    }

    @Test
    void noOriginMeansNotABrowser() {
        assertTrue(SessionWebSocketProxy.OriginCheck.allowed(null, "192.168.5.25", null));
        assertTrue(SessionWebSocketProxy.OriginCheck.allowed("  ", "192.168.5.25", null));
    }

    @Test
    void anOriginOfTheSameHostIsAllowedWithOrWithoutTheDefaultPort() {
        assertTrue(SessionWebSocketProxy.OriginCheck.allowed("https://192.168.5.25", "192.168.5.25", null));
        assertTrue(SessionWebSocketProxy.OriginCheck.allowed("https://192.168.5.25:443", "192.168.5.25", null));
        assertTrue(SessionWebSocketProxy.OriginCheck.allowed("https://192.168.5.25", "192.168.5.25:443", null));
        assertTrue(SessionWebSocketProxy.OriginCheck.allowed("https://HOST.example", "host.example", null));
        assertTrue(SessionWebSocketProxy.OriginCheck.allowed("https://192.168.5.25", "pups-controller:8080", "192.168.5.25"));
        assertTrue(SessionWebSocketProxy.OriginCheck.allowed("https://192.168.5.25", "x", "192.168.5.25, proxy.internal"));
    }

    @Test
    void anOriginOfAnotherSiteIsRefused() {
        assertFalse(SessionWebSocketProxy.OriginCheck.allowed("https://evil.example", "192.168.5.25", null));
        assertFalse(SessionWebSocketProxy.OriginCheck.allowed("https://192.168.5.25.evil.example", "192.168.5.25", null));
        assertFalse(SessionWebSocketProxy.OriginCheck.allowed("https://192.168.5.25:8443", "192.168.5.25", null));
        assertFalse(SessionWebSocketProxy.OriginCheck.allowed("null", "192.168.5.25", null));
        assertFalse(SessionWebSocketProxy.OriginCheck.allowed("not a url", "192.168.5.25", null));
    }

    @Test
    void theToolGetsTheBrowsersHeadersWithoutTheOnesOfTheBrowsersConnection() {
        MultiMap out = SessionWebSocketProxy.UpstreamHeaders.of(Map.of(
            "Cookie", List.of("a=1"),
            "Origin", List.of("https://192.168.5.25"),
            "Host", List.of("192.168.5.25"),
            "Upgrade", List.of("websocket"),
            "Connection", List.of("Upgrade"),
            "Sec-WebSocket-Key", List.of("k"),
            "Sec-WebSocket-Version", List.of("13"),
            "Content-Length", List.of("0")), "192.168.5.25");
        assertEquals("a=1", out.get("cookie"));
        assertEquals("https://192.168.5.25", out.get("origin"));
        assertEquals("192.168.5.25", out.get("x-forwarded-host"));
        assertNull(out.get("host"));
        assertNull(out.get("upgrade"));
        assertNull(out.get("connection"));
        assertNull(out.get("sec-websocket-key"));
        assertNull(out.get("sec-websocket-version"));
        assertNull(out.get("content-length"));
    }

    @Test
    void anXForwardedHostThatAlreadyCameInIsKept() {
        MultiMap out = SessionWebSocketProxy.UpstreamHeaders.of(
            Map.of("X-Forwarded-Host", List.of("front.example")), "192.168.5.25");
        assertEquals(List.of("front.example"), out.getAll("x-forwarded-host"));
    }

    @Test
    void aCloseStatusThatOnlyReportsWhatHappenedLocallyIsNeverSent() {
        assertEquals(1000, WebSocketRelay.closeStatus(null));
        assertEquals(1000, WebSocketRelay.closeStatus((short) 1005));
        assertEquals(1011, WebSocketRelay.closeStatus((short) 1006));
        assertEquals(1011, WebSocketRelay.closeStatus((short) 1015));
        assertEquals(1001, WebSocketRelay.closeStatus((short) 1001));
        assertEquals(4000, WebSocketRelay.closeStatus((short) 4000));
    }
}
