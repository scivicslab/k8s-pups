package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;

/**
 * E2E: verifies that the jupyter-lab tool starts and displays without errors in k8s-pups.
 * Run via K8sPupsE2ERunner.
 *
 * Stops all sessions and waits for pods to be gone before launching — handles both
 * nfs-k8s (RWX) and Longhorn (RWO) storage configurations correctly.
 */
class K8sPupsJupyterLabE2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsJupyterLabE2E().run(); }

    void run() throws Exception {
        System.out.println("--- K8sPupsJupyterLabE2E ---");
        setup();
        try {
            login();
            stopAllSessions();
            waitForUserPodsGone();
            launchToolSession("jupyter-lab");
            String sessionPath = waitForOpenToolButton("jupyter-lab");

            navigateToSession(sessionOrigin() + sessionPath);

            if (page.locator("text=Failed to load").isVisible())
                throw new AssertionError("jupyter-lab: 'Failed to load' error is visible");

            // jp-DirListing is rendered once the file browser is fully initialized.
            // SESSION_TIMEOUT_MS used here because OIDC redirect adds latency before JupyterLab initializes.
            page.waitForSelector(".jp-DirListing",
                    new Page.WaitForSelectorOptions().setTimeout(SESSION_TIMEOUT_MS));

            stopExistingSessionIfAny("jupyter-lab");
            waitForUserPodsGone();
        } finally {
            teardown();
        }
        System.out.println("K8sPupsJupyterLabE2E: PASSED");
    }
}
