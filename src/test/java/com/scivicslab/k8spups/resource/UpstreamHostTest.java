package com.scivicslab.k8spups.resource;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The Host header of a relayed request reaches the tool as the browser's. The test server answers with the Host it
 * received, which is what Jupyter Server compares with the Origin.
 */
class UpstreamHostTest {

    private static String hostSeenBy(String browserHost) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = String.valueOf(ex.getRequestHeaders().getFirst("Host")).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/sessions"))
                .POST(HttpRequest.BodyPublishers.noBody());
            UpstreamHost.apply(rb, browserHost);
            return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(rb.build(), HttpResponse.BodyHandlers.ofString()).body();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theToolSeesTheHostOfTheBrowser() throws Exception {
        assertEquals("192.168.5.25", hostSeenBy("192.168.5.25"));
        assertEquals("pups.example.org:8443", hostSeenBy("pups.example.org:8443"));
    }

    @Test
    void withoutABrowserHostTheClientsOwnHostIsKept() throws Exception {
        assertTrue(hostSeenBy(null).startsWith("127.0.0.1:"));
        assertTrue(hostSeenBy("  ").startsWith("127.0.0.1:"));
    }

    @Test
    void theJvmOfTheTestsAllowsTheHeader() {
        // The same switch the controller's Dockerfile sets; without it the test above would see the client's own Host.
        assertEquals("host", System.getProperty(UpstreamHost.ALLOW_PROPERTY));
    }
}
