package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * E2E: verifies that compliance-tracer starts and displays without errors in k8s-pups.
 * Run via K8sPupsE2ERunner.
 *
 * compliance-tracer requires a workspace PVC mounted at /home/devteam (for the H2 database).
 * Storage must be configured before launch: ensureLonghornPvc() stops all sessions,
 * confirms the Longhorn PVC exists, then launches the tool.
 */
class K8sPupsComplianceTracerE2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsComplianceTracerE2E().run(); }

    void run() throws Exception {
        System.out.println("--- K8sPupsComplianceTracerE2E ---");
        setup();
        try {
            login();
            // Storage must be confirmed before launching: the workspace PVC (/home/devteam)
            // is required for the H2 database. ensureLonghornPvc() also stops all sessions
            // so no other pod holds the RWO PVC when compliance-tracer starts.
            ensureLonghornPvc();
            launchToolSession("compliance-tracer");
            String sessionPath = waitForOpenToolButton("compliance-tracer");

            navigateToSession(sessionOrigin() + sessionPath);
            page.waitForSelector(".brand-name",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(SESSION_TIMEOUT_MS));

            if (page.locator("text=Failed to load").isVisible())
                throw new AssertionError("compliance-tracer: 'Failed to load' visible");
            if (page.locator("text=upstream connect error").isVisible())
                throw new AssertionError("compliance-tracer: 'upstream connect error' visible");
            if (page.locator("text=Internal Server Error").isVisible())
                throw new AssertionError("compliance-tracer: 'Internal Server Error' visible");

            stopExistingSessionIfAny("compliance-tracer");
        } finally {
            teardown();
        }
        System.out.println("K8sPupsComplianceTracerE2E: PASSED");
    }
}
