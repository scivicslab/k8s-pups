package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

class K8sPupsGuacamoleInspect extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsGuacamoleInspect().run(); }

    void run() throws Exception {
        setup();
        try {
            login();
            String sessionPath = waitForOpenToolButton("guacamole");

            page.navigate(sessionOrigin() + sessionPath);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.waitForTimeout(5_000);

            String ids = (String) page.evaluate(
                "() => Array.from(document.querySelectorAll('[id]')).map(e => '#' + e.id).join(', ')");
            String classes = (String) page.evaluate(
                "() => [...new Set(Array.from(document.querySelectorAll('[class]')).flatMap(e => [...e.classList]))].join(', ')");
            String html = (String) page.evaluate("() => document.body.innerHTML");

            System.out.println("=== IDs ===");
            System.out.println(ids);
            System.out.println("=== Classes ===");
            System.out.println(classes);
            System.out.println("=== Body HTML (first 3000) ===");
            System.out.println(html.substring(0, Math.min(3000, html.length())));
        } finally {
            teardown();
        }
    }
}
