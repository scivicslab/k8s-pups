package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;

/**
 * E2E: verifies that the ai-workspace tool starts and displays without errors in k8s-pups.
 * Run via K8sPupsE2ERunner.
 *
 * ai-workspace is singleInstance=true, so any existing session is stopped first.
 * ~/works is the user's allocated storage.
 */
class K8sPupsAiWorkspaceE2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsAiWorkspaceE2E().run(); }

    void run() throws Exception {
        System.out.println("--- K8sPupsAiWorkspaceE2E ---");
        setup();
        try {
            login();
            stopExistingSessionIfAny("ai-workspace");
            launchToolSession("ai-workspace");
            String sessionPath = waitForOpenToolButton("ai-workspace");

            navigateToSession(sessionOrigin() + sessionPath);

            // .brand-name appears once ai-workspace dashboard is fully rendered.
            page.waitForSelector(".brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(SESSION_TIMEOUT_MS));

            if (page.locator("text=Failed to load").isVisible())
                throw new AssertionError("ai-workspace: 'Failed to load' error is visible");

            stopExistingSessionIfAny("ai-workspace");
        } finally {
            teardown();
        }
        System.out.println("K8sPupsAiWorkspaceE2E: PASSED");
    }
}
