package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * E2E: verifies that the guacamole tool starts and displays without errors in k8s-pups.
 * Run via K8sPupsE2ERunner.
 *
 * Access control is handled by the Envoy Gateway SecurityPolicy (OIDC), so Guacamole's
 * own login screen is not shown. The browser lands directly on the Guacamole home screen.
 */
class K8sPupsGuacamoleE2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsGuacamoleE2E().run(); }

    void run() throws Exception {
        System.out.println("--- K8sPupsGuacamoleE2E ---");
        setup();
        try {
            login();
            stopExistingSessionIfAny("guacamole");
            launchToolSession("guacamole");
            String sessionPath = waitForOpenToolButton("guacamole");

            navigateToSession(sessionOrigin() + sessionPath);

            if (page.locator("text=Failed to load").isVisible())
                throw new AssertionError("guacamole: 'Failed to load' error is visible");

            // Guacamole renders #content (AngularJS root) once authenticated.
            // Use ATTACHED (not VISIBLE) — the element exists in DOM but may have zero size
            // during AngularJS initialization.
            page.waitForSelector("#content",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(SESSION_TIMEOUT_MS));

            stopExistingSessionIfAny("guacamole");
        } finally {
            teardown();
        }
        System.out.println("K8sPupsGuacamoleE2E: PASSED");
    }
}
