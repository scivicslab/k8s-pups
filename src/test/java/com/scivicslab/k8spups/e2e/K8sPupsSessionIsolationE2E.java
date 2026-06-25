package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;

/**
 * E2E test: session URLs are only accessible to the session owner.
 *
 * Flow:
 *   1. testadmin logs in and starts a file-browser session.
 *   2. Verify testadmin can access the session URL (sanity check).
 *   3. testuser logs in (separate browser context).
 *   4. testuser attempts to access testadmin's session URL.
 *   5. Assert testuser receives HTTP 403 "You do not own this session".
 */
class K8sPupsSessionIsolationE2E extends K8sPupsE2EBase {

    private static final String TESTUSER          = "testuser";
    private static final String TESTUSER_PASSWORD = "testuser";

    private BrowserContext testUserContext;
    private Page           testUserPage;

    public static void main(String[] args) throws Exception {
        new K8sPupsSessionIsolationE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- K8sPupsSessionIsolationE2E ---");
        setup();
        try {
            verifySessionIsolation();
        } finally {
            if (testUserContext != null) testUserContext.close();
            teardown();
        }
        System.out.println("K8sPupsSessionIsolationE2E: PASSED");
    }

    private void verifySessionIsolation() {
        LOG.info("=== Session isolation: testadmin's session is inaccessible to testuser ===");

        // --- Step 1: testadmin launches a file-browser session ---
        login();
        stopExistingSessionIfAny("file-browser");
        launchToolSession("file-browser");
        String sessionHref = waitForOpenToolButton("file-browser");
        String sessionUrl  = sessionOrigin() + sessionHref;
        LOG.info("testadmin session URL: " + sessionUrl);

        // --- Step 2: verify testadmin CAN access the session (sanity check) ---
        // Use raw navigate() so we can capture the HTTP status before OIDC redirects.
        Response adminResp = page.navigate(sessionUrl);
        int adminStatus = adminResp != null ? adminResp.status() : -1;
        try {
            page.waitForURL(url -> !url.contains("/protocol/openid-connect/"),
                    new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
        } catch (com.microsoft.playwright.TimeoutError ignored) {}
        LOG.info("testadmin → session URL: HTTP " + adminStatus + " final=" + page.url());

        if (adminStatus == 404) {
            throw new AssertionError(
                    "Session routing is broken: testadmin gets HTTP 404 at " + sessionUrl
                    + " — cannot test isolation against a non-existent route");
        }
        if (adminStatus == 403) {
            throw new AssertionError(
                    "Session routing is broken: testadmin gets HTTP 403 for their own session at " + sessionUrl);
        }
        LOG.info("Sanity check PASSED: testadmin can reach session (HTTP " + adminStatus + ")");

        // --- Step 3: testuser logs in in a separate browser context ---
        testUserContext = browser.newContext(
                new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
        testUserPage = testUserContext.newPage();

        testUserPage.navigate(BASE_URL + "/dashboard");
        testUserPage.waitForSelector("input[name='username']",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        testUserPage.fill("input[name='username']", TESTUSER);
        testUserPage.fill("input[name='password']", TESTUSER_PASSWORD);
        testUserPage.click("input[type='submit'], button:has-text('Sign In')");
        testUserPage.waitForURL("**/dashboard",
                new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
        LOG.info("testuser logged in: " + TESTUSER);

        // --- Step 4: testuser attempts to access testadmin's session URL ---
        LOG.info("testuser attempting to access testadmin's session: " + sessionUrl);
        Response userResp = testUserPage.navigate(sessionUrl);
        int userStatus = userResp != null ? userResp.status() : -1;
        // Follow through any OIDC redirects that might occur
        try {
            testUserPage.waitForURL(url -> !url.contains("/protocol/openid-connect/"),
                    new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
        } catch (com.microsoft.playwright.TimeoutError ignored) {}

        String finalUrl  = testUserPage.url();
        String bodyText  = testUserPage.locator("body").textContent();
        LOG.info("testuser response: HTTP " + userStatus + " final URL=" + finalUrl);
        LOG.info("testuser page body (first 300 chars): "
                + bodyText.substring(0, Math.min(300, bodyText.length())));

        // --- Step 5: assert testuser is denied ---
        boolean denied = userStatus == 403
                || bodyText.contains("You do not own this session")
                || bodyText.contains("Forbidden");
        if (!denied) {
            throw new AssertionError(
                    "Session isolation FAILED: testuser (HTTP " + userStatus + ") at " + finalUrl
                    + " — body=" + bodyText.substring(0, Math.min(300, bodyText.length())));
        }
        LOG.info("Session isolation PASSED: testuser received HTTP " + userStatus
                + " — cannot access testadmin's session");

        // --- Cleanup: stop testadmin's session ---
        stopExistingSessionIfAny("file-browser");
    }
}
