package com.scivicslab.k8spups.e2e;

/**
 * Entry point for k8s-pups E2E tests.
 *
 * <p>Runs all E2E scenarios in sequence against an already-deployed k8s-pups environment.
 * Any failure throws an exception and exits with a non-zero code.
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>k8s-pups deployed at -De2e.base.url (default: https://133.39.114.45/local-llm)</li>
 *   <li>testadmin user exists (-De2e.username, -De2e.password)</li>
 *   <li>Google Chrome installed at /usr/bin/google-chrome</li>
 *   <li>Playwright Chromium installed</li>
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.k8spups.e2e.K8sPupsE2ERunner \
 *     -Dexec.classpathScope=test \
 *     -De2e.base.url=https://133.39.114.45/local-llm \
 *     -De2e.username=testadmin \
 *     -De2e.password=&lt;password&gt;
 * </pre>
 */
public class K8sPupsE2ERunner {

    public static void main(String[] args) throws Exception {
        System.out.println("=== k8s-pups E2E Tests ===");
        new K8sPupsLandingPageE2E().run();
        new K8sPupsStorageE2E().run();
        new K8sPupsFileBrowserE2E().run();
        new K8sPupsJupyterLabE2E().run();
        new K8sPupsGuacamoleE2E().run();
        new K8sPupsServicePortalE2E().run();
        new K8sPupsServicePortalToolsE2E().run();
        System.out.println("=== All E2E tests PASSED ===");
    }
}
