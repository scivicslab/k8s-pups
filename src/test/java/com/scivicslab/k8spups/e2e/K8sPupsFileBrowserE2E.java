package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * E2E: verifies that the file-browser tool starts and displays its UI in k8s-pups.
 * Run via K8sPupsE2ERunner.
 *
 * FileBrowser Quantum runs with noauth mode; Envoy SecurityPolicy handles authentication.
 * Storage is nfs-k8s (RWX), auto-created on first session start.
 */
class K8sPupsFileBrowserE2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsFileBrowserE2E().run(); }

    void run() throws Exception {
        System.out.println("--- K8sPupsFileBrowserE2E ---");
        setup();
        try {
            login();
            // Stop all sessions before launching to avoid Longhorn RWO conflicts.
            // If the user's active storage type is longhorn, another running session
            // may hold the PVC and block file-browser from mounting.
            stopAllSessions();
            waitForUserPodsGone();
            launchToolSession("file-browser");
            String sessionPath = waitForOpenToolButton("file-browser");

            navigateToSession(sessionOrigin() + sessionPath);

            if (page.locator("text=Failed to load").isVisible())
                throw new AssertionError("file-browser: 'Failed to load' error is visible");

            // Wait for NETWORK_IDLE to ensure Vue.js API calls have completed.
            // FileBrowser Quantum renders #app (Vue root) and populates it via /api/resources.
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(SESSION_TIMEOUT_MS));

            // #app is present in the initial HTML but empty until Vue renders.
            // Check ATTACHED (in DOM) rather than VISIBLE since it starts with zero dimensions.
            page.waitForSelector("#app",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(PAGE_TIMEOUT_MS));

            // Verify no error state is displayed.
            if (page.locator("text=upstream connect error").isVisible())
                throw new AssertionError("file-browser: upstream connect error is visible");
            if (page.locator("text=connection refused").isVisible())
                throw new AssertionError("file-browser: connection refused is visible");

            LOG.info("FileBrowser UI loaded successfully at: " + page.url());

            stopExistingSessionIfAny("file-browser");
        } finally {
            teardown();
        }
        System.out.println("K8sPupsFileBrowserE2E: PASSED");
    }
}
