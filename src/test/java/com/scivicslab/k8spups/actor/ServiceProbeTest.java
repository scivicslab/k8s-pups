package com.scivicslab.k8spups.actor;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

class ServiceProbeTest {

    @Test
    void aListeningPortIsReachable() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(ServiceProbe.reachable("127.0.0.1", server.getLocalPort(), 1000));
        }
    }

    @Test
    void aPortWithNoListenerIsRefused() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = server.getLocalPort();
        }
        assertFalse(ServiceProbe.reachable("127.0.0.1", port, 1000));
    }

    @Test
    void aNameThatDoesNotResolveIsNotReachable() {
        assertFalse(ServiceProbe.reachable("no-such-service.invalid", 8080, 500));
    }

    @Test
    void theHostNameIsTheClusterInternalNameOfTheService() {
        assertEquals("pups-svc-abc.user-pods-with-audit.svc.cluster.local",
                ServiceProbe.host("pups-svc-abc", "user-pods-with-audit"));
    }
}
