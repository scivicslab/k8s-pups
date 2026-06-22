package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

/**
 * E2E: verifies that the landing page at BASE_URL is publicly accessible without authentication.
 * Run via K8sPupsE2ERunner.
 */
class K8sPupsLandingPageE2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsLandingPageE2E().run(); }

    void run() throws Exception {
        System.out.println("--- K8sPupsLandingPageE2E ---");
        setup();
        try {
            // Navigate without logging in — landing page must be publicly accessible.
            page.navigate(BASE_URL + "/");
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(PAGE_TIMEOUT_MS));

            String currentUrl = page.url();
            if (currentUrl.contains("auth") || currentUrl.contains("login")) {
                throw new AssertionError(
                    "Landing page redirected to authentication: " + currentUrl);
            }

            page.waitForSelector(".landing",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        } finally {
            teardown();
        }
        System.out.println("K8sPupsLandingPageE2E: PASSED");
    }
}
