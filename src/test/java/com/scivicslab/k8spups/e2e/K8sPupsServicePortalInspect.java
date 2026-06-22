package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

class K8sPupsServicePortalInspect extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsServicePortalInspect().run(); }

    void run() throws Exception {
        setup();
        try {
            login();
            String sessionPath = waitForOpenToolButton("service-portal");

            page.navigate(sessionOrigin() + sessionPath);
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(SESSION_TIMEOUT_MS));

            String ids = (String) page.evaluate(
                "() => Array.from(document.querySelectorAll('[id]')).map(e => '#' + e.id).join(', ')");
            String classes = (String) page.evaluate(
                "() => [...new Set(Array.from(document.querySelectorAll('[class]')).flatMap(e => [...e.classList]))].join(', ')");
            String html = (String) page.evaluate("() => document.body.innerHTML");

            System.out.println("URL: " + page.url());
            System.out.println("=== IDs ===");
            System.out.println(ids);
            System.out.println("=== Classes ===");
            System.out.println(classes);
            System.out.println("=== Body HTML (first 2000) ===");
            System.out.println(html.substring(0, Math.min(2000, html.length())));
        } finally {
            teardown();
        }
    }
}
