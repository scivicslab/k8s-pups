package com.scivicslab.k8spups.actor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Whether a TCP connection to a Service of a session Pod is accepted. A Pod that has become Ready is not yet reachable
 * through its Service for a moment (the rules that send the Service address to the Pod are written a little later), and
 * a request in that moment is refused. The session is declared ready only after a connection has been accepted.
 */
final class ServiceProbe {

    private ServiceProbe() {}

    /** True when a connection to {@code host:port} is accepted within the timeout. */
    static boolean reachable(String host, int port, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The cluster-internal DNS name of a Service, as the controller's proxy addresses it. */
    static String host(String serviceName, String namespace) {
        return serviceName + "." + namespace + ".svc.cluster.local";
    }
}
