package com.scivicslab.k8spups.k8s;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The session path takes the deployment's prefix, so that sessions of a deployment that sits behind a
 * path-routing proxy are routed to its own gateway. A trailing slash on the prefix is ignored.
 * The property is set before the configuration is first read, because the configuration is cached.
 */
class SessionPathTest {

    static {
        System.setProperty("k8spups.session-path-prefix", "/pups-with-audit/");
    }

    @Test
    void sessionPathStartsWithThePrefix() {
        assertEquals("/pups-with-audit/session/abc", SessionPath.of("abc"));
    }

    @Test
    void subToolIdIsKeptAsGiven() {
        assertEquals("/pups-with-audit/session/abc-tool-8000", SessionPath.of("abc-tool-8000"));
    }

    @Test
    void readinessProbePathTakesTheSessionPath() {
        assertEquals("/pups-with-audit/session/abc/api/version", SessionPath.expand("{sessionPath}/api/version", "abc"));
        assertEquals("/config", SessionPath.expand("/config", "abc"));
    }

    @Test
    void galaxyIsNotReadyBeforeItsApiAnswersUnderTheSessionPath() {
        assertEquals("{sessionPath}/api/version", new com.scivicslab.k8spups.plugin.GalaxyPlugin().readinessProbePath());
    }
}
