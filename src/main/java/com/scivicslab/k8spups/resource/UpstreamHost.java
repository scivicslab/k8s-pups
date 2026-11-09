package com.scivicslab.k8spups.resource;

import java.net.http.HttpRequest;
import java.util.logging.Logger;

/**
 * The {@code Host} header of a request the controller relays to a tool Pod: the host the browser used, as the WebSocket
 * relay already sends it ({@code SessionWebSocketProxy}).
 *
 * <p>A tool that checks where a request comes from compares the {@code Origin} header with {@code Host}. Jupyter Server
 * does it for every request that changes something (creating a notebook or a kernel session, saving a file), and refuses
 * with 403 when the {@code Host} it receives is the name of the Pod's Service instead of the browser's. The check is left
 * on; the request is made to look like what it is: the browser's request, relayed.
 *
 * <p>The JDK's {@link java.net.http.HttpClient} refuses to set {@code Host} unless the JVM is started with
 * {@code -Djdk.httpclient.allowRestrictedHeaders=host} (the Dockerfile of the controller does). Without it the header is
 * not set, the tool sees the Service name as before, and a warning says why, once.
 */
final class UpstreamHost {

    private static final Logger LOG = Logger.getLogger(UpstreamHost.class.getName());

    /** The JVM property that lets the HTTP client send a {@code Host} header. */
    static final String ALLOW_PROPERTY = "jdk.httpclient.allowRestrictedHeaders";

    private static volatile boolean warned;

    private UpstreamHost() {
    }

    /** Sets {@code Host} on {@code request} to {@code browserHost}; false when there is none or the client does not allow it. */
    static boolean apply(HttpRequest.Builder request, String browserHost) {
        if (browserHost == null || browserHost.isBlank()) {
            return false;
        }
        try {
            request.header("Host", browserHost);
            return true;
        } catch (IllegalArgumentException e) {
            if (!warned) {
                warned = true;
                LOG.warning("The Host header of a relayed request could not be set (" + e.getMessage() + "); start the JVM with -D"
                    + ALLOW_PROPERTY + "=host. Tools that check the Origin against the Host will refuse their POST requests.");
            }
            return false;
        }
    }
}
