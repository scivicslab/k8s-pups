package com.scivicslab.k8spups.resource;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A browser, a relay and a tool on loopback sockets: messages go through both ways, and a close on either side closes
 * the other. No cluster, no external service.
 */
class WebSocketRelayTest {

    private Vertx vertx;
    private HttpServer toolServer;
    private HttpServer relayServer;
    private WebSocketClient client;
    private final LongAdder counter = new LongAdder();
    private final LongAdder fromBrowser = new LongAdder();
    private final CountDownLatch toolSawClose = new CountDownLatch(1);
    private final AtomicReference<Short> toolSawCloseStatus = new AtomicReference<>();
    private volatile io.vertx.core.http.ServerWebSocket toolSide;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        client = vertx.createWebSocketClient();

        toolServer = vertx.createHttpServer().webSocketHandler(ws -> {
            toolSide = ws;
            ws.textMessageHandler(m -> ws.writeTextMessage("echo:" + m));
            ws.binaryMessageHandler(b -> ws.writeBinaryMessage(b));
            ws.closeHandler(v -> {
                toolSawCloseStatus.set(ws.closeStatusCode());
                toolSawClose.countDown();
            });
        });
        toolServer.listen(0).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        relayServer = vertx.createHttpServer().webSocketHandler(browserSide -> {
            browserSide.pause();
            client.connect(toolServer.actualPort(), "localhost", "/").onSuccess(tool -> {
                WebSocketRelay.connect(browserSide, tool, counter, fromBrowser);
                browserSide.resume();
            });
        });
        relayServer.listen(0).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private WebSocket browser() throws Exception {
        return client.connect(relayServer.actualPort(), "localhost", "/")
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void aTextMessageGoesToTheToolAndTheAnswerComesBack() throws Exception {
        WebSocket browser = browser();
        AtomicReference<String> got = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        browser.textMessageHandler(m -> {
            got.set(m);
            latch.countDown();
        });
        browser.writeTextMessage("hello");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("echo:hello", got.get());
        assertTrue(counter.sum() >= "hello".length() + "echo:hello".length());
    }

    /** What the user does counts as use of the session; what the tool shows on its own does not. */
    @Test
    void onlyTheMessagesOfTheBrowserAreCountedAsUse() throws Exception {
        WebSocket browser = browser();
        CountDownLatch connected = new CountDownLatch(1);
        browser.textMessageHandler(m -> connected.countDown());
        browser.writeTextMessage("first key");          // the relay is connected end to end once this is answered
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        assertEquals(1, fromBrowser.sum());

        CountDownLatch pushed = new CountDownLatch(3);
        browser.textMessageHandler(m -> pushed.countDown());
        toolSide.writeTextMessage("tool output 1");
        toolSide.writeTextMessage("tool output 2");
        toolSide.writeTextMessage("tool output 3");
        assertTrue(pushed.await(5, TimeUnit.SECONDS));
        assertEquals(1, fromBrowser.sum(), "what the tool shows on its own is not use");

        CountDownLatch answered = new CountDownLatch(1);
        browser.textMessageHandler(m -> answered.countDown());
        browser.writeTextMessage("a key the user typed");
        assertTrue(answered.await(5, TimeUnit.SECONDS));
        assertEquals(2, fromBrowser.sum());
    }

    @Test
    void aBinaryMessageIsRelayedUnchanged() throws Exception {
        WebSocket browser = browser();
        AtomicReference<byte[]> got = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        browser.binaryMessageHandler(b -> {
            got.set(b.getBytes());
            latch.countDown();
        });
        byte[] payload = new byte[70_000];            // larger than one default frame, so it is split into frames
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        browser.writeBinaryMessage(Buffer.buffer(payload));
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertArrayEquals(payload, got.get());
    }

    @Test
    void whenTheBrowserClosesTheToolSeesTheSameClose() throws Exception {
        WebSocket browser = browser();
        browser.writeTextMessage("ping the path");     // make sure the relay is connected end to end
        CountDownLatch answered = new CountDownLatch(1);
        browser.textMessageHandler(m -> answered.countDown());
        assertTrue(answered.await(5, TimeUnit.SECONDS));

        browser.close((short) 1001, "going away");
        assertTrue(toolSawClose.await(5, TimeUnit.SECONDS));
        assertEquals((short) 1001, toolSawCloseStatus.get());
    }

    @Test
    void whenTheToolClosesTheBrowserSeesTheSameClose() throws Exception {
        WebSocket browser = browser();
        browser.writeTextMessage("x");
        CountDownLatch answered = new CountDownLatch(1);
        browser.textMessageHandler(m -> answered.countDown());
        assertTrue(answered.await(5, TimeUnit.SECONDS));

        CountDownLatch browserSawClose = new CountDownLatch(1);
        AtomicReference<Short> status = new AtomicReference<>();
        browser.closeHandler(v -> {
            status.set(browser.closeStatusCode());
            browserSawClose.countDown();
        });
        toolSide.close((short) 4001, "tool finished");
        assertTrue(browserSawClose.await(5, TimeUnit.SECONDS));
        assertEquals((short) 4001, status.get());
    }

    @Test
    void messagesThatArriveAfterTheOtherSideClosedAreDroppedWithoutAnUncaughtException() throws Exception {
        var uncaught = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        vertx.exceptionHandler(uncaught::add);
        for (int i = 0; i < 30; i++) {
            WebSocket browser = browser();
            browser.writeTextMessage("x");
            browser.close();              // the tool's answer is still on its way when the browser side is gone
        }
        Thread.sleep(1500);
        assertTrue(uncaught.isEmpty(), "uncaught " + uncaught.size() + ", the first: " + stackOf(uncaught));
    }

    /**
     * A second relay in front of the same tool, with the egress limit of the session: what the tool sends to the browser
     * is added to {@code sentToBrowser}, and the relay closes both sides once it is over {@code limit} bytes.
     */
    private int relayWithLimit(LongAdder sentToBrowser, long limit) throws Exception {
        HttpServer limited = vertx.createHttpServer().webSocketHandler(browserSide -> {
            browserSide.pause();
            client.connect(toolServer.actualPort(), "localhost", "/").onSuccess(tool -> {
                WebSocketRelay.connect(browserSide, tool, new LongAdder(), new LongAdder(), frame -> true,
                    sentToBrowser, () -> sentToBrowser.sum() > limit);
                browserSide.resume();
            });
        });
        return limited.listen(0).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).actualPort();
    }

    /** SessionEgress: a session that has sent its limit to the browser is cut with 1008, and the message over it is not delivered. */
    @Test
    void whatTheToolSendsOverTheLimitClosesBothSidesWith1008() throws Exception {
        LongAdder sentToBrowser = new LongAdder();
        int port = relayWithLimit(sentToBrowser, 100);
        WebSocket browser = client.connect(port, "localhost", "/")
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        CountDownLatch answered = new CountDownLatch(1);
        CountDownLatch browserSawClose = new CountDownLatch(1);
        AtomicReference<Short> browserStatus = new AtomicReference<>();
        browser.textMessageHandler(m -> {
            received.add(m);
            answered.countDown();
        });
        browser.closeHandler(v -> {
            browserStatus.set(browser.closeStatusCode());
            browserSawClose.countDown();
        });

        browser.writeTextMessage("hi");                 // the relay is connected end to end once this is answered
        assertTrue(answered.await(5, TimeUnit.SECONDS));
        assertEquals("echo:hi".length(), sentToBrowser.sum(), "only what goes to the browser is counted");

        String fifty = "a".repeat(50);
        toolSide.writeTextMessage(fifty);               // 57 bytes in all: under the limit, delivered
        toolSide.writeTextMessage(fifty);               // 107 bytes in all: over the limit, not delivered
        assertTrue(browserSawClose.await(5, TimeUnit.SECONDS));
        assertTrue(toolSawClose.await(5, TimeUnit.SECONDS));

        assertEquals((short) 1008, browserStatus.get());
        assertEquals((short) 1008, toolSawCloseStatus.get());
        assertEquals(java.util.List.of("echo:hi", fifty), received);
    }

    /** Without a limit reached, the relay with the egress check passes everything, as the plain relay does. */
    @Test
    void underTheLimitTheToolsMessagesAreRelayedAndCounted() throws Exception {
        LongAdder sentToBrowser = new LongAdder();
        int port = relayWithLimit(sentToBrowser, 1_000_000);
        WebSocket browser = client.connect(port, "localhost", "/")
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        CountDownLatch answered = new CountDownLatch(3);
        browser.textMessageHandler(m -> answered.countDown());
        browser.writeTextMessage("one");
        browser.writeTextMessage("two");
        browser.writeTextMessage("three");
        assertTrue(answered.await(5, TimeUnit.SECONDS));
        assertEquals("echo:one".length() + "echo:two".length() + "echo:three".length(), sentToBrowser.sum());
        assertEquals(1L, toolSawClose.getCount(), "nothing was closed");
    }

    private static String stackOf(java.util.List<Throwable> uncaught) {
        if (uncaught.isEmpty()) {
            return "";
        }
        var w = new java.io.StringWriter();
        uncaught.get(0).printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }
}
