package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;

/**
 * Shared browser setup and k8s-pups navigation helpers for E2E tests.
 * Plain Java class — no JUnit dependency.
 */
abstract class K8sPupsE2EBase {

    protected static final Logger LOG = Logger.getLogger(K8sPupsE2EBase.class.getName());

    protected static final String BASE_URL  = System.getProperty("e2e.base.url", "https://133.39.114.45:7443/local-llm");
    protected static final String USERNAME  = System.getProperty("e2e.username", "testadmin");
    protected static final String PASSWORD  = System.getProperty("e2e.password", "");
    protected static final String CHROME    = "/usr/bin/google-chrome";

    protected static final long PAGE_TIMEOUT_MS    = 30_000;
    protected static final long SESSION_TIMEOUT_MS = 300_000;

    protected Playwright    playwright;
    protected Browser       browser;
    protected BrowserContext context;
    protected Page          page;

    protected void setup() {
        if (PASSWORD.isBlank())
            throw new IllegalStateException("-De2e.password system property is required");
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setExecutablePath(Paths.get(CHROME))
                        .setHeadless(true)
                        .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
        context = browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
        page    = context.newPage();
    }

    protected void teardown() {
        if (context   != null) context.close();
        if (browser   != null) browser.close();
        if (playwright != null) playwright.close();
    }

    protected void login() {
        page.navigate(BASE_URL + "/dashboard");
        page.waitForSelector("input[name='username']",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        page.fill("input[name='username']", USERNAME);
        page.fill("input[name='password']", PASSWORD);
        page.click("input[type='submit'], button:has-text('Sign In')");
        page.waitForURL("**/dashboard", new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
        page.waitForSelector("span.brand-name",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        LOG.info("Logged in as: " + USERNAME);
    }

    private void navigateToDashboard() {
        // Navigate via about:blank first to cleanly unload any previous SPA (e.g. Guacamole,
        // JupyterLab). A direct navigate from a heavy SPA can produce ERR_ABORTED when in-flight
        // XHRs or AngularJS routing interfere with Playwright's navigation wait.
        try { page.navigate("about:blank"); } catch (Exception ignored) {}
        page.navigate(BASE_URL + "/dashboard");
        page.waitForSelector("span.brand-name",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
    }

    protected void stopExistingSessionIfAny(String toolName) {
        navigateToDashboard();
        Locator stopBtn = page.locator(".session-card")
                .filter(new Locator.FilterOptions().setHasText(toolName))
                .locator("button.btn-stop");
        if (stopBtn.count() > 0) {
            LOG.info("Stopping existing session: " + toolName);
            stopBtn.first().click();
            page.waitForURL("**/dashboard", new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.waitForSelector("span.brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        }

        // Wait for the session card to fully disappear (controller destroys session object).
        // For singleInstance tools, the controller blocks a new launch until the old session
        // is fully destroyed — not just when the pod is gone.
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        while (true) {
            Locator card = page.locator(".session-card")
                    .filter(new Locator.FilterOptions().setHasText(toolName));
            if (card.count() == 0) break;
            LOG.info("Waiting for session card to disappear: " + toolName);
            if (System.currentTimeMillis() >= deadline) {
                LOG.warning("Session card did not disappear within timeout: " + toolName);
                break;
            }
            page.waitForTimeout(3_000);
            page.navigate(BASE_URL + "/dashboard");
            page.waitForSelector("span.brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        }
    }

    protected void launchToolSession(String toolName) {
        page.navigate(BASE_URL + "/dashboard");
        page.waitForSelector("span.brand-name",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        Locator launchBtn = page.locator("form")
                .filter(new Locator.FilterOptions().setHas(
                        page.locator("input[name='tool'][value='" + toolName + "']")))
                .locator("button[type='submit']");
        launchBtn.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
        launchBtn.click();
        page.waitForURL("**/dashboard", new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
        LOG.info("Launched tool session: " + toolName);
    }

    protected String waitForOpenToolButton(String toolName) {
        // Poll by reloading the dashboard every 5 seconds until a.btn-open appears.
        // A single waitFor is not enough for slow-starting tools (e.g. JupyterLab)
        // because the dashboard does not auto-refresh after the initial page load.
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        while (true) {
            page.navigate(BASE_URL + "/dashboard");
            page.waitForSelector("span.brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
            Locator card = page.locator(".session-card")
                    .filter(new Locator.FilterOptions().setHasText(toolName));
            Locator openBtn = card.locator("a.btn-open");
            if (openBtn.count() > 0) {
                String href = openBtn.first().getAttribute("href");
                LOG.info("Session READY: " + toolName + " → " + href);
                return href;
            }
            // Fail fast if the session card shows FAILED state
            // (e.g. Longhorn RWO conflict: another pod still holds the PVC).
            if (card.count() > 0) {
                String cardText = card.first().textContent();
                if (cardText != null && cardText.contains("FAILED")) {
                    throw new AssertionError("Session entered FAILED state: " + toolName
                            + " — card text: " + cardText.substring(0, Math.min(200, cardText.length())));
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Session did not become READY within "
                        + SESSION_TIMEOUT_MS / 1000 + "s: " + toolName);
            }
            LOG.info("Waiting for session READY: " + toolName + " (retrying in 5s)");
            page.waitForTimeout(5_000);
        }
    }

    protected void stopAllSessions() {
        // Click every stop button on the dashboard one by one until none remain,
        // then wait for all session cards to disappear.
        while (true) {
            navigateToDashboard();
            Locator stopBtns = page.locator("button.btn-stop");
            if (stopBtns.count() == 0) break;
            LOG.info("Stopping session (btn-stop count=" + stopBtns.count() + ")");
            stopBtns.first().click();
            page.waitForURL("**/dashboard", new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.waitForSelector("span.brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        }
        // Wait for all session cards to disappear (controller destroys session objects).
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        while (true) {
            page.navigate(BASE_URL + "/dashboard");
            page.waitForSelector("span.brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
            if (page.locator(".session-card").count() == 0) {
                LOG.info("All session cards gone.");
                break;
            }
            LOG.info("Waiting for all session cards to disappear...");
            if (System.currentTimeMillis() >= deadline) {
                LOG.warning("Not all sessions stopped within timeout");
                break;
            }
            page.waitForTimeout(3_000);
        }
    }

    protected void ensureLonghornPvc() {
        // Stop ALL active sessions (any tool may hold the RWO Longhorn PVC), then wait
        // for the PVC to fully detach before starting storage-settings.
        stopAllSessions();
        waitForUserPodsGone();
        launchToolSession("storage-settings");
        String sessionPath = waitForOpenToolButton("storage-settings");
        navigateToSession(sessionOrigin() + sessionPath);
        Locator createBtn = page.locator("button.btn-create")
                .filter(new Locator.FilterOptions().setHasText("Create Longhorn PVC"));
        if (createBtn.count() > 0) {
            LOG.info("Creating Longhorn PVC...");
            createBtn.first().click();
            createBtn.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout(PAGE_TIMEOUT_MS));
            LOG.info("Longhorn PVC created");
        } else {
            LOG.info("Longhorn PVC already exists");
        }
        stopExistingSessionIfAny("storage-settings");
        waitForUserPodsGone();
    }

    /**
     * Wait until the test user's pods in user-pods-local-llm are gone AND the user's Longhorn PVC
     * has detached. Both conditions must hold before the next session can safely mount the PVC.
     * Only waits for pods owned by USERNAME (label selector user=USERNAME), so other users'
     * pods running in parallel do not block the test.
     */
    protected void waitForUserPodsGone() {
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        // Phase 1: wait for the test user's pods to be gone.
        while (true) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "kubectl", "get", "pods", "-n", "user-pods-local-llm",
                        "-l", "user=" + USERNAME, "--no-headers");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String out = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor();
                if (out.isEmpty() || out.contains("No resources found")) {
                    LOG.info("All user pods gone for user=" + USERNAME + ".");
                    break;
                }
                LOG.info("Waiting for user pods to terminate (user=" + USERNAME + "): " + out.lines().count() + " pod(s) remaining");
            } catch (Exception e) {
                LOG.warning("Could not check user pods: " + e.getMessage());
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                LOG.warning("User pods did not terminate within timeout");
                return;
            }
            try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // Phase 2: wait for the Longhorn PVC to detach.
        // The kubelet must remove the VolumeAttachment before Longhorn marks the volume as
        // "detached". Until then, a new pod mounting the PVC gets "already mounted" rejected.
        String pvcName = "pups-data-" + USERNAME + "-longhorn";
        while (true) {
            try {
                ProcessBuilder pvPb = new ProcessBuilder(
                        "kubectl", "get", "pvc", pvcName, "-n", "user-pods-local-llm",
                        "-o", "jsonpath={.spec.volumeName}");
                pvPb.redirectErrorStream(false);
                Process pvProc = pvPb.start();
                String pvName = new String(pvProc.getInputStream().readAllBytes()).trim();
                pvProc.waitFor();
                if (pvName.isEmpty()) {
                    LOG.info("Longhorn PVC not found (no PVC to wait for): " + pvcName);
                    return;
                }
                ProcessBuilder volPb = new ProcessBuilder(
                        "kubectl", "get", "volume", pvName, "-n", "longhorn-system",
                        "-o", "jsonpath={.status.state}");
                volPb.redirectErrorStream(false);
                Process volProc = volPb.start();
                String state = new String(volProc.getInputStream().readAllBytes()).trim();
                volProc.waitFor();
                if ("detached".equalsIgnoreCase(state)) {
                    LOG.info("Longhorn PVC detached; ready for next mount: " + pvcName);
                    return;
                }
                LOG.info("Waiting for Longhorn PVC to detach (state=" + state + "): " + pvcName);
            } catch (Exception e) {
                LOG.warning("Could not check Longhorn PVC state: " + e.getMessage());
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                LOG.warning("Longhorn PVC did not detach within timeout: " + pvcName);
                return;
            }
            try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ── Desktop stability check ────────────────────────────────────────────────

    private static final String GUAC_ERROR_SELECTOR =
        ".connection-error, [ng-switch-when='CLIENT.STATUS.TUNNEL_ERROR'], " +
        ".notification.error, .guac-notification.error";

    /**
     * Waits {@code durationMs} milliseconds while polling every 5s for a Guacamole
     * disconnect error.  After the wait, verifies the VNC canvas is still live and
     * that the final screenshot is not all-black.
     *
     * Reproduces the "Guacamole internal error after ~1 minute" regression:
     * the disconnect appears as an error notification OR the canvas drops to 0x0.
     *
     * @param durationMs  how long to hold the connection open (e.g. 80_000)
     * @param screenshotPath  path where the final screenshot is written
     */
    protected void assertStableNoDisconnect(long durationMs, Path screenshotPath) throws IOException {
        LOG.info(String.format("Stability check: holding connection for %.0fs...", durationMs / 1000.0));
        long start = System.currentTimeMillis();
        long end   = start + durationMs;

        while (System.currentTimeMillis() < end) {
            long remaining = end - System.currentTimeMillis();
            page.waitForTimeout(Math.min(5_000, remaining));

            Locator errEl = page.locator(GUAC_ERROR_SELECTOR);
            if (errEl.count() > 0) {
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath).setFullPage(false));
                long elapsed = (System.currentTimeMillis() - start) / 1000;
                throw new AssertionError(String.format(
                    "Guacamole disconnected with error after %ds: %s — screenshot: %s",
                    elapsed, errEl.first().textContent(), screenshotPath.toAbsolutePath()));
            }

            // Log canvas size each poll to track any intermittent issues
            String dims = (String) page.evaluate(
                "() => { const c = document.querySelector('#display canvas, .display canvas');"
                + " return c ? c.width + 'x' + c.height : 'gone'; }");
            long elapsed = (System.currentTimeMillis() - start) / 1000;
            LOG.info(String.format("  +%ds: canvas=%s", elapsed, dims));

            if ("gone".equals(dims) || dims == null) {
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath).setFullPage(false));
                throw new AssertionError(String.format(
                    "Guacamole canvas disappeared after %ds — screenshot: %s",
                    elapsed, screenshotPath.toAbsolutePath()));
            }
            if (dims.startsWith("0x") || dims.endsWith("x0")) {
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotPath).setFullPage(false));
                throw new AssertionError(String.format(
                    "Guacamole canvas became %s after %ds (VNC framebuffer lost) — screenshot: %s",
                    dims, elapsed, screenshotPath.toAbsolutePath()));
            }
        }

        // Final verification: canvas still live, screenshot not black
        page.screenshot(new Page.ScreenshotOptions()
            .setPath(screenshotPath).setFullPage(false));
        assertScreenshotNotBlack(screenshotPath);

        LOG.info(String.format("Stability check PASSED: connection held for %.0fs",
            durationMs / 1000.0));
    }

    /**
     * Fails if the screenshot is essentially all-black OR a uniform solid color.
     *
     * Check 1 (brightness): at least 10% of sampled pixels must have total R+G+B > 30.
     *   Catches a true black screen.
     *
     * Check 2 (color variance): at least 15% of sampled pixels must deviate from the
     *   average brightness by more than 20.
     *   Catches a uniform solid-color background (e.g. xsetroot alone with no MATE panel
     *   or icons), which passes the brightness check but has near-zero pixel variance.
     *   A real desktop with panel + wallpaper + icons always has significant color diversity.
     */
    protected static void assertScreenshotNotBlack(Path path) throws IOException {
        BufferedImage img = ImageIO.read(path.toFile());
        int w = img.getWidth(), h = img.getHeight();
        int samples = 2000;
        int[] brightness = new int[samples];
        long sum = 0;
        int nonBlack = 0;

        for (int i = 0; i < samples; i++) {
            int x = (w * i) / samples;
            int y = h / 4 + (h / 2) * (i % 2);
            int rgb = img.getRGB(x, Math.min(y, h - 1));
            int b = ((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff);
            brightness[i] = b;
            sum += b;
            if (b > 30) nonBlack++;
        }

        double brightPct = 100.0 * nonBlack / samples;
        LOG.info(String.format("Brightness check: %.1f%% non-black pixels (threshold 10%%)", brightPct));
        if (brightPct < 10.0) {
            throw new AssertionError(String.format(
                "Desktop is all-black (%.1f%% non-black pixels). Screenshot: %s",
                brightPct, path.toAbsolutePath()));
        }

        double avg = (double) sum / samples;
        int deviated = 0;
        for (int b : brightness) {
            if (Math.abs(b - avg) > 20) deviated++;
        }
        double variancePct = 100.0 * deviated / samples;
        LOG.info(String.format("Color variance: avg=%.0f, %.1f%% of pixels deviate >20 from avg (threshold 15%%)",
            avg, variancePct));
        if (variancePct < 15.0) {
            throw new AssertionError(String.format(
                "Desktop appears uniform (%.1f%% color variance, avg brightness=%.0f). "
                + "MATE components (marco, mate-panel) are likely not rendering — "
                + "only the solid xsetroot background is visible. Screenshot: %s",
                variancePct, avg, path.toAbsolutePath()));
        }
    }

    protected String sessionOrigin() {
        return BASE_URL.replaceFirst("(https?://[^/]+).*", "$1");
    }

    /**
     * Navigate to a session URL and handle Keycloak OIDC redirect transparently.
     * Envoy SecurityPolicy OIDC redirects to Keycloak on first access per session.
     * If Keycloak's SSO session is active, it auto-redirects back without a login form.
     * If re-authentication is required (login form appears), credentials are filled in.
     *
     * After OIDC callback, Envoy restores the original request URL from the state parameter.
     * Because the Envoy Gateway listener uses protocol HTTP (TLS terminated upstream), the
     * stored URL is http://, not https://. The OIDC cookie is set with Secure flag for https://,
     * so the browser omits it on http:// requests, triggering another OIDC redirect loop.
     * Workaround: detect http:// after OIDC and re-navigate to the original https:// URL.
     * The second navigation sends the Secure cookie and bypasses OIDC.
     */
    protected void navigateToSession(String sessionUrl) {
        page.navigate(sessionUrl);

        // Wait for OIDC flow to settle (Keycloak auth → callback → session URL).
        try {
            page.waitForURL(url -> !url.contains("/protocol/openid-connect/") && !url.contains("/oauth2/callback"),
                    new Page.WaitForURLOptions().setTimeout(45_000));
        } catch (com.microsoft.playwright.TimeoutError e) {
            // OIDC did not complete within 45s — check if login form is shown.
        }

        // If still on Keycloak auth, fill in credentials immediately before the auth session expires.
        if (page.url().contains("/protocol/openid-connect/auth")) {
            LOG.info("Keycloak re-authentication required for: " + sessionUrl);
            page.waitForSelector("input[name='username']",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.fill("input[name='username']", USERNAME);
            page.fill("input[name='password']", PASSWORD);
            page.click("input[type='submit'], button:has-text('Sign In')");
            page.waitForURL(url -> !url.contains("/protocol/openid-connect/") && !url.contains("/oauth2/callback"),
                    new Page.WaitForURLOptions().setTimeout(SESSION_TIMEOUT_MS));
        }

        // If OIDC state contained http:// (Gateway listener is HTTP), re-navigate to https://.
        // The OIDC cookie (Secure flag) was set during the https:// callback and will now be sent.
        if (page.url().startsWith("http://")) {
            LOG.info("Detected http:// after OIDC callback; re-navigating to: " + sessionUrl);
            page.navigate(sessionUrl);
            try {
                page.waitForURL(url -> !url.contains("/protocol/openid-connect/") && !url.contains("/oauth2/callback"),
                        new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
            } catch (com.microsoft.playwright.TimeoutError e) {
                // ignore
            }
        }
        // Callers wait for their own content selector — no generic load wait needed here.
    }
}
