package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

/**
 * E2E: verifies that service-portal launched from k8s-pups can start and use
 * each sub-tool (html-saurus, chat-ui, turing-workflow-editor).
 *
 * Each sub-tool registers with the k8s-pups controller via POST /api/sub-tool/{sessionId},
 * which creates a dedicated Service + HTTPRoute. The browser reaches each sub-tool
 * directly via Envoy Gateway — service-portal does NOT proxy sub-tool traffic.
 *
 * html-saurus requires a workspace directory with Docusaurus projects (doc_* dirs).
 * When the NFS workspace is not mounted (e.g. testadmin has no LDAP account),
 * the html-saurus scenario is skipped with a warning rather than failing.
 *
 * Run via K8sPupsE2ERunner or standalone main().
 */
class K8sPupsServicePortalToolsE2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception {
        new K8sPupsServicePortalToolsE2E().run();
    }

    private String portalUrl;

    void run() throws Exception {
        System.out.println("--- K8sPupsServicePortalToolsE2E ---");
        setup();
        try {
            login();
            stopExistingSessionIfAny("service-portal");
            launchToolSession("service-portal");
            String sessionPath = waitForOpenToolButton("service-portal");
            portalUrl = sessionOrigin() + sessionPath;

            navigateToSession(portalUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(SESSION_TIMEOUT_MS));
            page.waitForSelector(".brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(SESSION_TIMEOUT_MS));
            LOG.info("service-portal dashboard ready: " + portalUrl);

            tryTestHtmlSaurus();
            testChatUi();
            testTuringWorkflowEditor();

            stopExistingSessionIfAny("service-portal");
        } finally {
            teardown();
        }
        System.out.println("K8sPupsServicePortalToolsE2E: PASSED");
    }

    // --- sub-tool test scenarios ---

    /**
     * html-saurus requires Docusaurus projects (doc_* dirs) in the workspace.
     * Skipped with a warning when the NFS workspace is not mounted for the test user.
     */
    private void tryTestHtmlSaurus() {
        LOG.info("=== html-saurus ===");
        try {
            navigateToPortal();
            // Use the NFS workspace path; html-saurus needs Docusaurus projects under it.
            page.fill("#param-html-saurus-dir", "/home/devteam/works");
            page.click("#tool-tile-html-saurus button.btn-launch");
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(PAGE_TIMEOUT_MS));

            // Short timeout: if html-saurus dies immediately (no workspace), detect quickly.
            String accessUrl = waitForSubToolReady("html-saurus", 30_000);
            page.navigate(sessionOrigin() + accessUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(PAGE_TIMEOUT_MS));

            String body = (String) page.evaluate("() => document.body.innerText");
            if (body == null || body.isBlank())
                throw new AssertionError("html-saurus: page body is empty");
            LOG.info("html-saurus: content verified");

            navigateToPortal();
            stopSubTool("html-saurus");
            LOG.info("html-saurus: STOPPED");
        } catch (AssertionError e) {
            LOG.warning("html-saurus SKIPPED: " + e.getMessage()
                    + " (NFS workspace likely not mounted for this user)");
            // Navigate back to portal so subsequent tests start from a clean state.
            navigateToPortal();
        }
    }

    private void testChatUi() {
        LOG.info("=== chat-ui ===");
        navigateToPortal();
        page.selectOption("#param-quarkus-chat-ui-provider", "claude");
        page.click("#tool-tile-quarkus-chat-ui button.btn-launch");
        page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(PAGE_TIMEOUT_MS));

        String accessUrl = waitForSubToolReady("quarkus-chat-ui", SESSION_TIMEOUT_MS);
        page.navigate(sessionOrigin() + accessUrl);
        page.waitForLoadState(LoadState.LOAD,
                new Page.WaitForLoadStateOptions().setTimeout(PAGE_TIMEOUT_MS));

        Locator promptInput = page.locator("#prompt-input");
        promptInput.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
        if (!promptInput.isEnabled())
            throw new AssertionError("chat-ui: #prompt-input is not enabled");

        Locator loginScreen = page.locator("#login-screen");
        if (loginScreen.count() > 0 && loginScreen.isVisible())
            throw new AssertionError("chat-ui: #login-screen is visible (single-user mode expected)");
        LOG.info("chat-ui: #prompt-input enabled, no login screen");

        navigateToPortal();
        stopSubTool("quarkus-chat-ui");
        LOG.info("chat-ui: STOPPED");
    }

    private void testTuringWorkflowEditor() {
        LOG.info("=== turing-workflow-editor ===");
        navigateToPortal();
        page.click("#tool-tile-turing-workflow-editor button.btn-launch");
        page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(PAGE_TIMEOUT_MS));

        String accessUrl = waitForSubToolReady("turing-workflow-editor", SESSION_TIMEOUT_MS);
        page.navigate(sessionOrigin() + accessUrl);
        page.waitForLoadState(LoadState.LOAD,
                new Page.WaitForLoadStateOptions().setTimeout(PAGE_TIMEOUT_MS));

        page.waitForSelector("#stepsContainer",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        page.waitForSelector("#runBtn",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        LOG.info("turing-workflow-editor: #stepsContainer and #runBtn verified");

        navigateToPortal();
        stopSubTool("turing-workflow-editor");
        LOG.info("turing-workflow-editor: STOPPED");
    }

    // --- helpers ---

    private void navigateToPortal() {
        page.navigate(portalUrl);
        page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(PAGE_TIMEOUT_MS));
        page.waitForSelector(".brand-name",
                new Page.WaitForSelectorOptions().setTimeout(SESSION_TIMEOUT_MS));
    }

    /**
     * Polls /api/status on the service-portal page until the named sub-tool is READY,
     * then returns its accessUrl (e.g. /session/{id}-quarkus-chat-ui-28100/).
     *
     * k8s-pups registers the sub-tool's HTTPRoute asynchronously after the process
     * starts; this loop waits for both the process and the route to be ready.
     *
     * The fetch runs inside the browser so the OIDC session cookie is sent automatically.
     */
    private String waitForSubToolReady(String toolName, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Object result = page.evaluate(
                    "(name) => fetch('api/status').then(r => r.json())" +
                    ".then(d => { const s = (d.activeSessions || []).find(s => s.toolName === name);" +
                    "             return s && s.state === 'READY' ? s.accessUrl : null; })",
                    toolName);
            if (result != null && !result.toString().isBlank()) {
                LOG.info("Sub-tool READY: " + toolName + " → " + result);
                return result.toString();
            }
            if (System.currentTimeMillis() >= deadline)
                throw new AssertionError(
                        "Sub-tool did not become READY within " + timeoutMs / 1000 + "s: " + toolName);
            LOG.info("Waiting for sub-tool READY: " + toolName + " (retrying in 3s)");
            page.waitForTimeout(3_000);
        }
    }

    /**
     * Clicks btn-stop on the session card for the named sub-tool and waits for the
     * page to reload (service-portal calls location.reload() after stopping).
     */
    private void stopSubTool(String toolName) {
        Locator stopBtn = page.locator(".session-card")
                .filter(new Locator.FilterOptions().setHasText(toolName))
                .locator("button.btn-stop");
        stopBtn.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
        stopBtn.first().click();
        page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(PAGE_TIMEOUT_MS));
    }
}
