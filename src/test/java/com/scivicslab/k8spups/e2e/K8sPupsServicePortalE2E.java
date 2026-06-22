package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;

/**
 * E2E: verifies that the service-portal tool starts and displays without errors in k8s-pups.
 * Run via K8sPupsE2ERunner.
 *
 * service-portal is singleInstance=true, so any existing session is stopped first.
 * The NFS workspace (~/works) is mounted only when the user has a POSIX account in LDAP.
 */
class K8sPupsServicePortalE2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsServicePortalE2E().run(); }

    void run() throws Exception {
        System.out.println("--- K8sPupsServicePortalE2E ---");
        setup();
        try {
            login();
            stopExistingSessionIfAny("service-portal");
            launchToolSession("service-portal");
            String sessionPath = waitForOpenToolButton("service-portal");

            navigateToSession(sessionOrigin() + sessionPath);

            // .brand-name appears once service-portal dashboard is fully rendered.
            page.waitForSelector(".brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(SESSION_TIMEOUT_MS));

            if (page.locator("text=Failed to load").isVisible())
                throw new AssertionError("service-portal: 'Failed to load' error is visible");

            stopExistingSessionIfAny("service-portal");
        } finally {
            teardown();
        }
        System.out.println("K8sPupsServicePortalE2E: PASSED");
    }
}
