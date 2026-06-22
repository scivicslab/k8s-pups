package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Page;

class K8sPupsJupyterLabInspect extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsJupyterLabInspect().run(); }

    void run() throws Exception {
        setup();

        // Capture console errors, failed requests, and 4xx/5xx responses.
        java.util.List<String> consoleErrors = new java.util.ArrayList<>();
        java.util.List<String> failedRequests = new java.util.ArrayList<>();
        java.util.List<String> badResponses = new java.util.ArrayList<>();
        java.util.List<String> allRequests = new java.util.ArrayList<>();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) consoleErrors.add(msg.text());
        });
        page.onRequest(req -> allRequests.add(req.method() + " " + req.url().replaceAll("https?://[^/]+", "")));
        page.onRequestFailed(req -> failedRequests.add(req.failure() + " — " + req.url()));
        page.onResponse(resp -> {
            if (resp.status() >= 400) {
                String body = "";
                try { body = " | body:" + resp.text().substring(0, Math.min(200, resp.text().length())); } catch (Exception e) { }
                String server = resp.headers().getOrDefault("server", "?");
                String contentType = resp.headers().getOrDefault("content-type", "?");
                badResponses.add(resp.status() + " [server:" + server + " ct:" + contentType + "] — " + resp.url() + body);
            }
        });

        try {
            login();
            stopExistingSessionIfAny("jupyter-lab");
            launchToolSession("jupyter-lab");
            String sessionPath = waitForOpenToolButton("jupyter-lab");

            System.out.println("Session path: " + sessionPath);
            System.out.println("Session URL: " + sessionOrigin() + sessionPath);

            navigateToSession(sessionOrigin() + sessionPath);
            System.out.println("URL after navigateToSession: " + page.url());

            page.waitForTimeout(20_000);
            System.out.println("URL after 20s wait: " + page.url());

            String ids = (String) page.evaluate(
                "() => Array.from(document.querySelectorAll('[id]')).map(e => '#' + e.id).join(', ')");
            String classes = (String) page.evaluate(
                "() => [...new Set(Array.from(document.querySelectorAll('[class]')).flatMap(e => [...e.classList]))].slice(0, 80).join(', ')");
            String html = (String) page.evaluate("() => document.body.innerHTML");

            System.out.println("=== IDs ===");
            System.out.println(ids);
            System.out.println("=== Classes (first 80) ===");
            System.out.println(classes);
            System.out.println("=== Body HTML (first 2000) ===");
            System.out.println(html.substring(0, Math.min(2000, html.length())));
            System.out.println("=== Console Errors ===");
            consoleErrors.forEach(System.out::println);
            System.out.println("=== Failed Requests (network-level) ===");
            failedRequests.forEach(System.out::println);
            System.out.println("=== Bad Responses (4xx/5xx) ===");
            badResponses.forEach(System.out::println);
            System.out.println("=== All Requests (path only) ===");
            allRequests.forEach(System.out::println);
        } finally {
            teardown();
        }
    }
}
