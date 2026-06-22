package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

/**
 * E2E: verifies that the storage-settings tool loads without errors in k8s-pups.
 * Run via K8sPupsE2ERunner.
 */
class K8sPupsStorageE2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsStorageE2E().run(); }

    void run() throws Exception {
        System.out.println("--- K8sPupsStorageE2E ---");
        setup();
        try {
            login();
            stopExistingSessionIfAny("storage-settings");
            launchToolSession("storage-settings");
            String sessionPath = waitForOpenToolButton("storage-settings");

            navigateToSession(sessionOrigin() + sessionPath);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(PAGE_TIMEOUT_MS));

            if (page.locator("text=Failed to load").isVisible())
                throw new AssertionError("storage-settings: 'Failed to load' error is visible");

            stopExistingSessionIfAny("storage-settings");
        } finally {
            teardown();
        }
        System.out.println("K8sPupsStorageE2E: PASSED");
    }
}
