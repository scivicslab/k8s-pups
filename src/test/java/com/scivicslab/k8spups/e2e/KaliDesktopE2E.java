package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * E2E test that verifies the Kali Linux desktop renders via Guacamole.
 *
 * Same architecture as GuacamoleDesktopE2E (Xvnc + MATE + guacd + Tomcat).
 * Verifies that the VNC canvas appears with non-zero dimensions AND that the
 * screenshot contains non-black content (catches black-screen failures that
 * pass the canvas-size check).
 *
 * Pass -Dkali.screenshot=/path/to/out.png to control output location.
 * Defaults to /tmp/kali-desktop.png.
 */
class KaliDesktopE2E extends K8sPupsE2EBase {

    private static final String DISPLAY_CANVAS     = "#display canvas";
    private static final String DISPLAY_CANVAS_ALT = ".display canvas";

    private static final Path SCREENSHOT_PATH = Paths.get(
            System.getProperty("kali.screenshot", "/tmp/kali-desktop.png"));

    public static void main(String[] args) throws Exception {
        new KaliDesktopE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- KaliDesktopE2E ---");
        setup();
        try {
            login();
            stopAllSessions();
            waitForUserPodsGone();

            launchToolSession("kali");
            String sessionHref = waitForOpenToolButton("kali");
            String sessionUrl  = sessionOrigin() + sessionHref;
            LOG.info("Kali session ready: " + sessionUrl);

            try { page.evaluate("sessionStorage.clear()"); } catch (Exception ignored) {}

            navigateToSession(sessionUrl);

            LOG.info("Waiting for #content (auto-login)...");
            page.waitForSelector("#content",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(SESSION_TIMEOUT_MS));
            LOG.info("#content appeared — auto-login succeeded, VNC connecting...");

            LOG.info("Waiting for VNC canvas with non-zero size...");
            String[] result = waitForCanvasWithSize();
            String canvasSelector = result[0];
            String dims           = result[1];
            LOG.info("Canvas ready: " + canvasSelector + " " + dims);

            // Poll until MATE paints at least one non-black frame (up to 45s).
            // Kali's MATE starts slower than Ubuntu's: VNC frames can take 20-30s to
            // arrive after the canvas reaches non-zero size. A fixed 8s wait is too short.
            waitForCanvasNonBlack(45_000);

            // Stability check — hold the connection for 80s and detect the
            // "Guacamole internal error after ~1 minute" disconnect regression.
            assertStableNoDisconnect(80_000, SCREENSHOT_PATH);

            System.out.println("PASS: kali desktop stable (" + dims + ") — screenshot: "
                    + SCREENSHOT_PATH.toAbsolutePath());

        } finally {
            try { stopExistingSessionIfAny("kali"); } catch (Exception ignored) {}
            teardown();
        }
    }

    private void waitForCanvasNonBlack(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int attempt = 0;
        while (true) {
            attempt++;
            page.waitForTimeout(5_000);
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(SCREENSHOT_PATH)
                    .setFullPage(false));
            try {
                assertScreenshotNotBlack(SCREENSHOT_PATH);
                LOG.info("Canvas non-black confirmed after attempt " + attempt);
                return;
            } catch (AssertionError e) {
                if (System.currentTimeMillis() >= deadline) {
                    throw e;
                }
                LOG.info("Canvas still black at attempt " + attempt + ", retrying...");
            }
        }
    }

    private String[] waitForCanvasWithSize() {
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        String matchedSel = null;
        while (true) {
            if (matchedSel == null) {
                for (String sel : new String[]{ DISPLAY_CANVAS, DISPLAY_CANVAS_ALT }) {
                    if (page.locator(sel).count() > 0) { matchedSel = sel; break; }
                }
            }

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
