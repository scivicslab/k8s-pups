package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.SessionState;
import com.scivicslab.k8spups.actor.SessionStatus;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which address a request for a session goes to, and who may send it. */
class SessionTargetTest {

    private static final String NS = "user-pods-with-audit";

    private static SessionStatus session(String id, String tool) {
        return new SessionStatus(id, "alice", tool, SessionState.values()[0], "pod-" + id, null, null);
    }

    private static ToolPlugin tool(String displayName, boolean passthrough, int port) {
        return (ToolPlugin) Proxy.newProxyInstance(ToolPlugin.class.getClassLoader(), new Class<?>[] { ToolPlugin.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "displayName" -> displayName;
                case "passthroughPath" -> passthrough;
                case "containerPort" -> port;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static Function<String, ToolPlugin> lookup(ToolPlugin... tools) {
        return name -> {
            for (ToolPlugin t : tools) {
                if (t.displayName().equals(name)) {
                    return t;
                }
            }
            return null;
        };
    }

    @Test
    void ownSessionGoesToItsPodServiceWithThePrefixDropped() {
        var r = SessionTarget.resolve("abc-123", "api/x", "a=1", "http",
            List.of(session("abc-123", "AI Chat")), lookup(tool("AI Chat", false, 8080)), NS);
        assertEquals(SessionTarget.Kind.SESSION, r.kind());
        assertEquals("abc-123", r.sessionId());
        assertEquals("http://pups-svc-abc-123.user-pods-with-audit.svc.cluster.local:8080/api/x?a=1", r.url());
    }

    @Test
    void aWebSocketGoesToTheSameAddressWithTheWsScheme() {
        var r = SessionTarget.resolve("abc-123", "stable/ws", null, "ws",
            List.of(session("abc-123", "Editor")), lookup(tool("Editor", false, 8443)), NS);
        assertEquals("ws://pups-svc-abc-123.user-pods-with-audit.svc.cluster.local:8443/stable/ws", r.url());
    }

    @Test
    void aToolThatKeepsTheSessionPrefixGetsItBack() {
        var r = SessionTarget.resolve("abc-123", "lab", null, "http",
            List.of(session("abc-123", "Jupyter Lab")), lookup(tool("Jupyter Lab", true, 8888)), NS);
        assertEquals("http://pups-svc-abc-123.user-pods-with-audit.svc.cluster.local:8888"
            + com.scivicslab.k8spups.k8s.SessionPath.of("abc-123") + "/lab", r.url());
    }

    @Test
    void anUnknownToolFallsBackToThePlainSettings() {
        var r = SessionTarget.resolve("abc-123", "", null, "http",
            List.of(session("abc-123", "Gone")), lookup(), NS);
        assertEquals("http://pups-svc-abc-123.user-pods-with-audit.svc.cluster.local:8080/", r.url());
    }

    @Test
    void aSessionOfAnotherUserIsForbidden() {
        var r = SessionTarget.resolve("zzz-999", "x", null, "http",
            List.of(session("abc-123", "AI Chat")), lookup(tool("AI Chat", false, 8080)), NS);
        assertEquals(SessionTarget.Kind.FORBIDDEN, r.kind());
        assertNull(r.url());
    }

    @Test
    void aSubToolOfOwnSessionGoesToItsService() {
        var r = SessionTarget.resolve("abc-123-editor-9000", "p", null, "ws",
            List.of(session("abc-123", "AI Chat")), lookup(), NS);
        assertEquals(SessionTarget.Kind.SUB_TOOL, r.kind());
        assertEquals("abc-123", r.sessionId());
        assertEquals("ws://pups-subtool-abc-123-editor-9000.user-pods-with-audit.svc.cluster.local:9000/p", r.url());
    }

    @Test
    void aSubToolOfAnotherUsersSessionIsForbidden() {
        var r = SessionTarget.resolve("zzz-999-editor-9000", "p", null, "http",
            List.of(session("abc-123", "AI Chat")), lookup(), NS);
        assertEquals(SessionTarget.Kind.FORBIDDEN, r.kind());
    }
}
