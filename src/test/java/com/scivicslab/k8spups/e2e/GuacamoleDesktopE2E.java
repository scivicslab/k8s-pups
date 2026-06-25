package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * E2E test that verifies the Guacamole remote desktop actually renders.
 *
 * The all-tools E2E only checks that #content appears (OIDC auth + Guacamole
 * login succeeded). This test goes one step further and waits for the VNC
 * connection to be established and the desktop canvas to appear, then saves a
 * screenshot so the result can be inspected visually.
 *
 * Pass -Dguac.screenshot=/path/to/out.png to control output location.
 * Defaults to /tmp/guacamole-desktop.png.
 */
class GuacamoleDesktopE2E extends K8sPupsE2EBase {

    // #display is the Guacamole viewport div; canvas children appear once VNC
    // handshake completes and the first frame is received.
    private static final String DISPLAY_CANVAS = "#display canvas";

    // Fallback: older Guacamole builds use .display instead of #display.
    private static final String DISPLAY_CANVAS_ALT = ".display canvas";

    private static final Path SCREENSHOT_PATH = Paths.get(
            System.getProperty("guac.screenshot", "/tmp/guacamole-desktop.png"));

    public static void main(String[] args) throws Exception {
        new GuacamoleDesktopE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- GuacamoleDesktopE2E ---");
        setup();
        try {
            login();
            stopAllSessions();
            waitForUserPodsGone();

            // Launch guacamole and wait for the controller to mark it READY.
            launchToolSession("guacamole");
            String sessionHref = waitForOpenToolButton("guacamole");
            String sessionUrl  = sessionOrigin() + sessionHref;
            LOG.info("Guacamole session ready: " + sessionUrl);

            // Clear sessionStorage so auto-login.js runs fresh.
            try { page.evaluate("sessionStorage.clear()"); } catch (Exception ignored) {}

            navigateToSession(sessionUrl);

            // Step 1: wait for #content — Guacamole AngularJS is "ready" (auto-login done).
            LOG.info("Waiting for #content (auto-login)...");
            page.waitForSelector("#content",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(SESSION_TIMEOUT_MS));
            LOG.info("#content appeared — auto-login succeeded, VNC connecting...");

            // Step 2: wait for the display canvas with non-zero dimensions.
            // The canvas element appears immediately on connection, but its size is
            // set only after the VNC server completes the RFB handshake and sends the
            // framebuffer dimensions. Poll until the canvas has real width × height.
            LOG.info("Waiting for VNC canvas with non-zero size...");
            String[] result = waitForCanvasWithSize();
            String canvasSelector = result[0];
            String dims           = result[1];
            LOG.info("Canvas ready: " + canvasSelector + " " + dims);

            // Allow MATE desktop to paint at least one non-black frame.
            // The first VNC frame is all-black while the compositor initializes.
            LOG.info("Waiting for MATE desktop to paint...");
            page.waitForTimeout(8_000);

            // Step 3: initial screenshot to verify rendering.
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(SCREENSHOT_PATH)
                    .setFullPage(false));
            LOG.info("Screenshot saved: " + SCREENSHOT_PATH.toAbsolutePath());
            assertScreenshotNotBlack(SCREENSHOT_PATH);

            // Step 4: stability check — hold the connection for 80s and detect the
            // "Guacamole internal error after ~1 minute" disconnect regression.
            // The disconnect appears as an error notification or canvas dropping to 0x0.
            assertStableNoDisconnect(80_000, SCREENSHOT_PATH);

            System.out.println("PASS: guacamole desktop stable (" + dims + ") — screenshot: "
                    + SCREENSHOT_PATH.toAbsolutePath());

        } finally {
            try { stopExistingSessionIfAny("guacamole"); } catch (Exception ignored) {}
            teardown();
        }
    }

    /**
     * Wait until a Guacamole display canvas appears AND has non-zero dimensions.
     * The canvas element is created on connection initiation; its size is set only
     * after the VNC RFB handshake delivers the framebuffer dimensions.
     * Returns [selector, "WxH"].
     */
    private String[] waitForCanvasWithSize() {
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        String matchedSel = null;
        while (true) {
            // Find which selector has a canvas.
            if (matchedSel == null) {
                for (String sel : new String[]{ DISPLAY_CANVAS, DISPLAY_CANVAS_ALT }) {
                    if (page.locator(sel).count() > 0) { matchedSel = sel; break; }
                }
            }

            // Fail fast on explicit Guacamole connection-error state.
            Locator errEl = page.locator(
                    ".connection-error, [ng-switch-when='CLIENT.STATUS.TUNNEL_ERROR'], " +
                    ".notification.error, .guac-notification.error");
            if (errEl.count() > 0) {
                page.screenshot(new Page.ScreenshotOptions().setPath(SCREENSHOT_PATH).setFullPage(false));
                throw new AssertionError("Guacamole connection error: "
                        + errEl.first().textContent()
                        + " — screenshot: " + SCREENSHOT_PATH.toAbsolutePath());
            }

            if (matchedSel != null) {
                String dims = (String) page.evaluate(
                        "() => { const c = document.querySelector('" + matchedSel + "');"
                        + " return c ? c.width + 'x' + c.height : 'null'; }");
                if (dims != null && !dims.equals("null")
                        && !dims.startsWith("0x") && !dims.endsWith("x0")) {
                    return new String[]{ matchedSel, dims };
                }
                LOG.info("Canvas present but size=" + dims + "; waiting for RFB framebuffer dimensions...");
            } else {
                LOG.info("Waiting for Guacamole display canvas...");
            }

            if (System.currentTimeMillis() >= deadline) {
                // Save a diagnostic screenshot before failing.
                try {
                    page.screenshot(new Page.ScreenshotOptions()
                            .setPath(SCREENSHOT_PATH).setFullPage(false));
                } catch (Exception ignored) {}
                String body = page.locator("body").textContent();
                throw new AssertionError("VNC canvas never reached non-zero size within "
                        + SESSION_TIMEOUT_MS / 1000 + "s"
                        + (matchedSel == null ? " (canvas never appeared)" : " (canvas is 0x0)")
                        + ". Body snippet: " + body.substring(0, Math.min(500, body.length()))
                        + " — diagnostic screenshot: " + SCREENSHOT_PATH.toAbsolutePath());
            }
            page.waitForTimeout(3_000);
        }
    }
}
