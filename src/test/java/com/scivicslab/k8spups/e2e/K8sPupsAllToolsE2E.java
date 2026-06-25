package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive E2E: tests every tool in k8s-pups.
 *
 * For each HTTP tool (storage-settings, file-browser, jupyter-lab, guacamole,
 * kali, service-portal):
 *   1. Launch the tool.
 *   2. Wait for the Open Tool button (pod is READY).
 *   3. Navigate to the session URL and verify a known UI element is present.
 *   4. Session isolation check: testuser must receive HTTP 403 when accessing
 *      the same session URL.
 *   5. Stop the session.
 *
 * For pdf-ocr (batch/VNC — no HTTP UI):
 *   1. Launch the job.
 *   2. Verify the session card appears (job started).
 *   3. Stop manually (the job auto-stops on completion but may take several minutes).
 *
 * Prerequisites:
 *   - testadmin account: -De2e.username / -De2e.password
 *   - testuser account: username=testuser, password=testuser (must exist in Keycloak)
 */
class K8sPupsAllToolsE2E extends K8sPupsE2EBase {

    private static final String TESTUSER          = "testuser";
    private static final String TESTUSER_PASSWORD = "testuser";

    /**
     * Per-tool configuration for the E2E launch/verify/stop cycle.
     *
     * @param toolName        Internal plugin name used in the launch form (e.g. "file-browser").
     *                        Also used for session-card text matching via the pod name
     *                        (pups-{toolName}-{sessionId} appears in the card).
     * @param readySelector   CSS / text selector that must be ATTACHED in the DOM after the
     *                        session URL loads, confirming the tool's UI is up.
     * @param uiTimeoutMs     How long to wait for readySelector before failing.
     * @param needsPodsClear  True if all user pods must be gone before launching this tool
     *                        (Longhorn RWO: only one pod can mount the PVC at a time).
     */
    private record ToolSpec(
        String  toolName,
        String  readySelector,
        long    uiTimeoutMs,
        boolean needsPodsClear
    ) {}

    private static final List<ToolSpec> HTTP_TOOLS = List.of(
        new ToolSpec("storage-settings", "span.brand-tag", PAGE_TIMEOUT_MS,    false),
        new ToolSpec("file-browser",     "#app",           PAGE_TIMEOUT_MS,    false),
        new ToolSpec("jupyter-lab",      ".jp-DirListing", SESSION_TIMEOUT_MS, true),
        new ToolSpec("guacamole",        "#content",       SESSION_TIMEOUT_MS, true),
        new ToolSpec("kali",             "#content",       SESSION_TIMEOUT_MS, true),
        new ToolSpec("service-portal",   ".brand-name",    SESSION_TIMEOUT_MS, true)
    );

    public static void main(String[] args) throws Exception {
        new K8sPupsAllToolsE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- K8sPupsAllToolsE2E ---");
        setup();
        BrowserContext testUserContext = null;
        Page           testUserPage   = null;

        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        try {
            login();
            stopAllSessions();
            waitForUserPodsGone();

            testUserContext = browser.newContext(
                new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
            testUserPage = testUserContext.newPage();
            loginAs(testUserPage, TESTUSER, TESTUSER_PASSWORD);

            for (ToolSpec tool : HTTP_TOOLS) {
                try {
                    runHttpTool(tool, testUserPage);
                    passed.add(tool.toolName());
                } catch (AssertionError | Exception e) {
                    System.err.println("FAILED [" + tool.toolName() + "]: " + e.getMessage());
                    failed.add(tool.toolName() + ": " + e.getMessage());
                    // Clean up so the next tool can start fresh
                    try { stopExistingSessionIfAny(tool.toolName()); } catch (Exception ignored) {}
                    try { waitForUserPodsGone(); } catch (Exception ignored) {}
                }
            }

            // pdf-ocr: batch job, no HTTP UI
            try {
                runBatchTool("pdf-ocr", "PDF → Markdown OCR");
                passed.add("pdf-ocr");
            } catch (AssertionError | Exception e) {
                System.err.println("FAILED [pdf-ocr]: " + e.getMessage());
                failed.add("pdf-ocr: " + e.getMessage());
            }

        } finally {
            if (testUserContext != null) testUserContext.close();
            teardown();
        }

        System.out.println("\n=== K8sPupsAllToolsE2E Results ===");
        passed.forEach(t -> System.out.println("  PASSED: " + t));
        failed.forEach(t -> System.out.println("  FAILED: " + t));

        if (!failed.isEmpty()) {
            throw new AssertionError("Some tools failed: " + failed);
        }
        System.out.println("K8sPupsAllToolsE2E: ALL PASSED");
    }

    // ── per-tool flows ────────────────────────────────────────────────────────

    private void runHttpTool(ToolSpec tool, Page testUserPage) {
        LOG.info("=== Tool: " + tool.toolName() + " ===");

        stopExistingSessionIfAny(tool.toolName());
        if (tool.needsPodsClear()) {
            stopAllSessions();
            waitForUserPodsGone();
        }

        launchToolSession(tool.toolName());
        String sessionHref = waitForOpenToolButton(tool.toolName());
        String sessionUrl  = sessionOrigin() + sessionHref;
        LOG.info(tool.toolName() + " session ready: " + sessionUrl);

        // Clear sessionStorage to prevent cross-tool contamination (e.g. guac-auto-done
        // set by a previous Guacamole-based tool prevents auto-login in the next one).
        try { page.evaluate("sessionStorage.clear()"); } catch (Exception ignored) {}

        // Verify UI
        navigateToSession(sessionUrl);
        try {
            page.waitForSelector(tool.readySelector(),
                new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(tool.uiTimeoutMs()));
        } catch (com.microsoft.playwright.TimeoutError e) {
            throw new AssertionError(tool.toolName()
                + ": UI did not load — selector '" + tool.readySelector()
                + "' not found within " + tool.uiTimeoutMs() + "ms"
                + " — current URL: " + page.url()
                + " — body: " + page.locator("body").textContent()
                    .substring(0, Math.min(300,
                        page.locator("body").textContent().length())));
        }
        if (page.locator("text=Failed to load").isVisible())
            throw new AssertionError(tool.toolName() + ": 'Failed to load' visible");
        if (page.locator("text=upstream connect error").isVisible())
            throw new AssertionError(tool.toolName() + ": 'upstream connect error' visible");
        if (page.locator("text=Proxy error").isVisible())
            throw new AssertionError(tool.toolName() + ": 'Proxy error' visible in body");
        if (page.locator("text=Internal Server Error").isVisible())
            throw new AssertionError(tool.toolName() + ": 'Internal Server Error' visible in body");
        LOG.info(tool.toolName() + ": UI OK at " + page.url());

        // Session isolation
        verifyIsolation(tool.toolName(), sessionUrl, testUserPage);

        // Stop
        stopExistingSessionIfAny(tool.toolName());
        System.out.println("  PASS: " + tool.toolName());
    }

    private void runBatchTool(String toolName, String displayName) {
        LOG.info("=== Tool: " + toolName + " (batch) ===");

        // Stop any leftover run
        stopExistingSessionIfAny(toolName);

        launchToolSession(toolName);

        // Wait for session card to appear (job pod started)
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        boolean appeared = false;
        while (System.currentTimeMillis() < deadline) {
            page.navigate(BASE_URL + "/dashboard");
            page.waitForSelector("span.brand-name",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
            Locator card = page.locator(".session-card")
                .filter(new Locator.FilterOptions().setHasText(toolName));
            if (card.count() > 0) {
                appeared = true;
                LOG.info(toolName + ": session card appeared — job started");
                break;
            }
            page.waitForTimeout(3_000);
        }
        if (!appeared) {
            throw new AssertionError(toolName + ": session card never appeared");
        }

        // Stop manually — batch jobs may take minutes to finish
        stopExistingSessionIfAny(toolName);
        System.out.println("  PASS: " + toolName + " (batch)");
    }

    // ── isolation check ───────────────────────────────────────────────────────

    private void verifyIsolation(String toolName, String sessionUrl, Page testUserPage) {
        LOG.info("Isolation check [" + toolName + "]: testuser → " + sessionUrl);

        Response resp   = testUserPage.navigate(sessionUrl);
        int      status = resp != null ? resp.status() : -1;
        try {
            testUserPage.waitForURL(
                url -> !url.contains("/protocol/openid-connect/"),
                new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
        } catch (com.microsoft.playwright.TimeoutError ignored) {}

        String body   = testUserPage.locator("body").textContent();
        boolean denied = status == 403
            || body.contains("You do not own this session")
            || body.contains("Forbidden");

        if (!denied) {
            throw new AssertionError(
                "Session isolation FAILED [" + toolName + "]: testuser got HTTP " + status
                + " at " + testUserPage.url()
                + " — body=" + body.substring(0, Math.min(300, body.length())));
        }
        LOG.info("Isolation OK [" + toolName + "]: testuser HTTP " + status);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void loginAs(Page targetPage, String username, String password) {
        targetPage.navigate(BASE_URL + "/dashboard");
        targetPage.waitForSelector("input[name='username']",
            new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        targetPage.fill("input[name='username']", username);
        targetPage.fill("input[name='password']", password);
        targetPage.click("input[type='submit'], button:has-text('Sign In')");
        targetPage.waitForURL("**/dashboard",
            new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
        LOG.info("Logged in as " + username);
    }
}
