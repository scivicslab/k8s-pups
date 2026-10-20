package com.scivicslab.k8spups.resource;

import io.vertx.core.http.WebSocketBase;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.http.WebSocketFrameType;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Connects two WebSockets, the browser's and the tool's, so that every message of one reaches the other and a close of
 * one closes the other. It knows nothing about sessions or users; the caller has decided that the connection is allowed.
 */
final class WebSocketRelay {

    private WebSocketRelay() {
    }

    /**
     * Starts relaying. Both sockets must be open and must not have a frame handler yet.
     *
     * @param counter the bytes of every message relayed in either direction are added here
     */
    static void connect(WebSocketBase browser, WebSocketBase tool, LongAdder counter) {
        AtomicBoolean closing = new AtomicBoolean();
        try {
            pipe(browser, tool, counter);
            pipe(tool, browser, counter);
            browser.closeHandler(v -> closeOther(browser, tool, closing));
            tool.closeHandler(v -> closeOther(tool, browser, closing));
            browser.exceptionHandler(e -> closeOther(browser, tool, closing));
            tool.exceptionHandler(e -> closeOther(tool, browser, closing));
        } catch (IllegalStateException closedBeforeConnecting) {
            // One side closed while the other was being connected (a browser that gave up, for instance): no handler
            // can be set on a closed socket, and the close handler that would have closed the other never runs.
            closing.set(true);
            closeQuietly(browser);
            closeQuietly(tool);
            return;
        }
        // A side that closed just before the handlers were set has missed its close event.
        if (browser.isClosed()) {
            closeOther(browser, tool, closing);
        } else if (tool.isClosed()) {
            closeOther(tool, browser, closing);
        }
    }

    private static void closeQuietly(WebSocketBase socket) {
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IllegalStateException alreadyClosed) {
            // nothing to close
        }
    }

    private static void pipe(WebSocketBase from, WebSocketBase to, LongAdder counter) {
        from.frameHandler(frame -> {
            // Each side answers the other's ping by itself; relaying them would answer twice.
            if (frame.isPing() || frame.type() == WebSocketFrameType.PONG) {
                return;
            }
            // The other side may have closed while this message was on its way; the close handler is closing this one.
            try {
                if (to.isClosed()) {
                    return;
                }
                counter.add(frame.binaryData().length());
                to.writeFrame(copyOf(frame));
                if (!to.isClosed() && to.writeQueueFull()) {
                    from.pause();
                    to.drainHandler(v -> from.resume());
                }
            } catch (IllegalStateException closedMeanwhile) {
                // The socket closed between the check and the write: nothing is left to deliver to.
            }
        });
    }

    private static WebSocketFrame copyOf(WebSocketFrame frame) {
        if (frame.isClose()) {
            return frame;
        }
        if (frame.isContinuation()) {
            return WebSocketFrame.continuationFrame(frame.binaryData(), frame.isFinal());
        }
        if (frame.isText()) {
            return WebSocketFrame.textFrame(frame.textData(), frame.isFinal());
        }
        return WebSocketFrame.binaryFrame(frame.binaryData(), frame.isFinal());
    }

    private static void closeOther(WebSocketBase closed, WebSocketBase other, AtomicBoolean closing) {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        if (!other.isClosed()) {
            other.close(closeStatus(closed.closeStatusCode()), closed.closeReason());
        }
    }

    /**
     * The status to send on: a received close carries its own status, but 1005 (none given), 1006 (the connection
     * dropped) and 1015 (TLS failure) are reports of what happened locally and must never go on the wire.
     */
    static short closeStatus(Short received) {
        if (received == null || received == 1005) {
            return 1000;
        }
        if (received == 1006 || received == 1015) {
            return 1011;
        }
        return received;
    }
}
