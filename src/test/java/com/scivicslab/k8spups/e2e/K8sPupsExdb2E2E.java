package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * E2E: verifies that quarkus-exdb2 starts and displays without errors in k8s-pups.
 * Run via K8sPupsE2ERunner.
 *
 * quarkus-exdb2 requires a workspace PVC mounted at /home/devteam (for the H2 database).
 * Storage must be configured before launch: ensureLonghornPvc() stops all sessions,
 * confirms the Longhorn PVC exists, then launches the tool.
 */
class K8sPupsExdb2E2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsExdb2E2E().run(); }

    void run() throws Exception {
        System.out.println("--- K8sPupsExdb2E2E ---");
        setup();
        try {
            login();
            // Storage must be confirmed before launching: the workspace PVC (/home/devteam)
            // is required for the H2 database. ensureLonghornPvc() also stops all sessions
            // so no other pod holds the RWO PVC when exdb2 starts.
            ensureLonghornPvc();
            launchToolSession("quarkus-exdb2");
            String sessionPath = waitForOpenToolButton("quarkus-exdb2");

            navigateToSession(sessionOrigin() + sessionPath);
            page.waitForSelector(".brand-name",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(SESSION_TIMEOUT_MS));

            if (page.locator("text=Failed to load").isVisible())
                throw new AssertionError("quarkus-exdb2: 'Failed to load' visible");
            if (page.locator("text=upstream connect error").isVisible())
                throw new AssertionError("quarkus-exdb2: 'upstream connect error' visible");
            if (page.locator("text=Internal Server Error").isVisible())
                throw new AssertionError("quarkus-exdb2: 'Internal Server Error' visible");

            stopExistingSessionIfAny("quarkus-exdb2");
        } finally {
            teardown();
        }
        System.out.println("K8sPupsExdb2E2E: PASSED");
    }
}
