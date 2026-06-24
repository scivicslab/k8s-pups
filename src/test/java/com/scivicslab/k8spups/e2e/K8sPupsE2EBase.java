package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;

/**
 * Shared browser setup and k8s-pups navigation helpers for E2E tests.
 * Plain Java class — no JUnit dependency.
 */
abstract class K8sPupsE2EBase {

    protected static final Logger LOG = Logger.getLogger(K8sPupsE2EBase.class.getName());

    protected static final String BASE_URL  = System.getProperty("e2e.base.url", "https://133.39.114.45:7443/local-llm");
    protected static final String USERNAME  = System.getProperty("e2e.username", "testadmin");
    protected static final String PASSWORD  = System.getProperty("e2e.password", "");
    protected static final String CHROME    = "/usr/bin/google-chrome";

    protected static final long PAGE_TIMEOUT_MS    = 30_000;
    protected static final long SESSION_TIMEOUT_MS = 300_000;

    protected Playwright    playwright;
    protected Browser       browser;
    protected BrowserContext context;
    protected Page          page;

    protected void setup() {
        if (PASSWORD.isBlank())
            throw new IllegalStateException("-De2e.password system property is required");
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setExecutablePath(Paths.get(CHROME))
                        .setHeadless(true)
                        .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
        context = browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
        page    = context.newPage();
    }

    protected void teardown() {
        if (context   != null) context.close();
        if (browser   != null) browser.close();
        if (playwright != null) playwright.close();
    }

    protected void login() {
        page.navigate(BASE_URL + "/dashboard");
        page.waitForSelector("input[name='username']",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        page.fill("input[name='username']", USERNAME);
        page.fill("input[name='password']", PASSWORD);
        page.click("input[type='submit'], button:has-text('Sign In')");
        page.waitForURL("**/dashboard", new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
        page.waitForSelector("span.brand-name",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        LOG.info("Logged in as: " + USERNAME);
    }

    private void navigateToDashboard() {
        // Navigate via about:blank first to cleanly unload any previous SPA (e.g. Guacamole,
        // JupyterLab). A direct navigate from a heavy SPA can produce ERR_ABORTED when in-flight
        // XHRs or AngularJS routing interfere with Playwright's navigation wait.
        try { page.navigate("about:blank"); } catch (Exception ignored) {}
        page.navigate(BASE_URL + "/dashboard");
        page.waitForSelector("span.brand-name",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
    }

    protected void stopExistingSessionIfAny(String toolName) {
        navigateToDashboard();
        Locator stopBtn = page.locator(".session-card")
                .filter(new Locator.FilterOptions().setHasText(toolName))
                .locator("button.btn-stop");
        if (stopBtn.count() > 0) {
            LOG.info("Stopping existing session: " + toolName);
            stopBtn.first().click();
            page.waitForURL("**/dashboard", new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.waitForSelector("span.brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        }

        // Wait for the session card to fully disappear (controller destroys session object).
        // For singleInstance tools, the controller blocks a new launch until the old session
        // is fully destroyed — not just when the pod is gone.
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        while (true) {
            Locator card = page.locator(".session-card")
                    .filter(new Locator.FilterOptions().setHasText(toolName));
            if (card.count() == 0) break;
            LOG.info("Waiting for session card to disappear: " + toolName);
            if (System.currentTimeMillis() >= deadline) {
                LOG.warning("Session card did not disappear within timeout: " + toolName);
                break;
            }
            page.waitForTimeout(3_000);
            page.navigate(BASE_URL + "/dashboard");
            page.waitForSelector("span.brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        }
    }

    protected void launchToolSession(String toolName) {
        page.navigate(BASE_URL + "/dashboard");
        page.waitForSelector("span.brand-name",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        Locator launchBtn = page.locator("form")
                .filter(new Locator.FilterOptions().setHas(
                        page.locator("input[name='tool'][value='" + toolName + "']")))
                .locator("button[type='submit']");
        launchBtn.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
        launchBtn.click();
        page.waitForURL("**/dashboard", new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
        LOG.info("Launched tool session: " + toolName);
    }

    protected String waitForOpenToolButton(String toolName) {
        // Poll by reloading the dashboard every 5 seconds until a.btn-open appears.
        // A single waitFor is not enough for slow-starting tools (e.g. JupyterLab)
        // because the dashboard does not auto-refresh after the initial page load.
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        while (true) {
            page.navigate(BASE_URL + "/dashboard");
            page.waitForSelector("span.brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
            Locator openBtn = page.locator(".session-card")
                    .filter(new Locator.FilterOptions().setHasText(toolName))
                    .locator("a.btn-open");
            if (openBtn.count() > 0) {
                String href = openBtn.first().getAttribute("href");
                LOG.info("Session READY: " + toolName + " → " + href);
                return href;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Session did not become READY within "
                        + SESSION_TIMEOUT_MS / 1000 + "s: " + toolName);
            }
            LOG.info("Waiting for session READY: " + toolName + " (retrying in 5s)");
            page.waitForTimeout(5_000);
        }
    }

    protected void stopAllSessions() {
        // Click every stop button on the dashboard one by one until none remain,
        // then wait for all session cards to disappear.
        while (true) {
            navigateToDashboard();
            Locator stopBtns = page.locator("button.btn-stop");
            if (stopBtns.count() == 0) break;
            LOG.info("Stopping session (btn-stop count=" + stopBtns.count() + ")");
            stopBtns.first().click();
            page.waitForURL("**/dashboard", new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.waitForSelector("span.brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        }
        // Wait for all session cards to disappear (controller destroys session objects).
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        while (true) {
            page.navigate(BASE_URL + "/dashboard");
            page.waitForSelector("span.brand-name",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
            if (page.locator(".session-card").count() == 0) {
                LOG.info("All session cards gone.");
                break;
            }
            LOG.info("Waiting for all session cards to disappear...");
            if (System.currentTimeMillis() >= deadline) {
                LOG.warning("Not all sessions stopped within timeout");
                break;
            }
            page.waitForTimeout(3_000);
        }
    }

    protected void ensureLonghornPvc() {
        // Stop ALL active sessions (any tool may hold the RWO Longhorn PVC), then wait
        // for the PVC to fully detach before starting storage-settings.
        stopAllSessions();
        waitForUserPodsGone();
        launchToolSession("storage-settings");
        String sessionPath = waitForOpenToolButton("storage-settings");
        navigateToSession(sessionOrigin() + sessionPath);
        Locator createBtn = page.locator("button.btn-create")
                .filter(new Locator.FilterOptions().setHasText("Create Longhorn PVC"));
        if (createBtn.count() > 0) {
            LOG.info("Creating Longhorn PVC...");
            createBtn.first().click();
            createBtn.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout(PAGE_TIMEOUT_MS));
            LOG.info("Longhorn PVC created");
        } else {
            LOG.info("Longhorn PVC already exists");
        }
        stopExistingSessionIfAny("storage-settings");
        waitForUserPodsGone();
    }

    /**
     * Wait until all user pods in user-pods-local-llm are gone AND the user's Longhorn PVC
     * has detached. Both conditions must hold before the next session can safely mount the PVC.
     */
    protected void waitForUserPodsGone() {
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        // Phase 1: wait for all user pods to be gone.
        while (true) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "kubectl", "get", "pods", "-n", "user-pods-local-llm", "--no-headers");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String out = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor();
                if (out.isEmpty() || out.contains("No resources found")) {
                    LOG.info("All user pods gone.");
                    break;
                }
                LOG.info("Waiting for user pods to terminate: " + out.lines().count() + " pod(s) remaining");
            } catch (Exception e) {
                LOG.warning("Could not check user pods: " + e.getMessage());
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                LOG.warning("User pods did not terminate within timeout");
                return;
            }
            try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // Phase 2: wait for the Longhorn PVC to detach.
        // The kubelet must remove the VolumeAttachment before Longhorn marks the volume as
        // "detached". Until then, a new pod mounting the PVC gets "already mounted" rejected.
        String pvcName = "pups-data-" + USERNAME + "-longhorn";
        while (true) {
            try {
                ProcessBuilder pvPb = new ProcessBuilder(
                        "kubectl", "get", "pvc", pvcName, "-n", "user-pods-local-llm",
                        "-o", "jsonpath={.spec.volumeName}");
                pvPb.redirectErrorStream(false);
                Process pvProc = pvPb.start();
                String pvName = new String(pvProc.getInputStream().readAllBytes()).trim();
                pvProc.waitFor();
                if (pvName.isEmpty()) {
                    LOG.info("Longhorn PVC not found (no PVC to wait for): " + pvcName);
                    return;
                }
                ProcessBuilder volPb = new ProcessBuilder(
                        "kubectl", "get", "volume", pvName, "-n", "longhorn-system",
                        "-o", "jsonpath={.status.state}");
                volPb.redirectErrorStream(false);
                Process volProc = volPb.start();
                String state = new String(volProc.getInputStream().readAllBytes()).trim();
                volProc.waitFor();
                if ("detached".equalsIgnoreCase(state)) {
                    LOG.info("Longhorn PVC detached; ready for next mount: " + pvcName);
                    return;
                }
                LOG.info("Waiting for Longhorn PVC to detach (state=" + state + "): " + pvcName);
            } catch (Exception e) {
                LOG.warning("Could not check Longhorn PVC state: " + e.getMessage());
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                LOG.warning("Longhorn PVC did not detach within timeout: " + pvcName);
                return;
            }
            try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    protected String sessionOrigin() {
        return BASE_URL.replaceFirst("(https?://[^/]+).*", "$1");
    }

    /**
     * Navigate to a session URL and handle Keycloak OIDC redirect transparently.
     * Envoy SecurityPolicy OIDC redirects to Keycloak on first access per session.
     * If Keycloak's SSO session is active, it auto-redirects back without a login form.
     * If re-authentication is required (login form appears), credentials are filled in.
     *
     * After OIDC callback, Envoy restores the original request URL from the state parameter.
     * Because the Envoy Gateway listener uses protocol HTTP (TLS terminated upstream), the
     * stored URL is http://, not https://. The OIDC cookie is set with Secure flag for https://,
     * so the browser omits it on http:// requests, triggering another OIDC redirect loop.
     * Workaround: detect http:// after OIDC and re-navigate to the original https:// URL.
     * The second navigation sends the Secure cookie and bypasses OIDC.
     */
    protected void navigateToSession(String sessionUrl) {
        page.navigate(sessionUrl);

        // Wait for OIDC flow to settle (Keycloak auth → callback → session URL).
        try {
            page.waitForURL(url -> !url.contains("/protocol/openid-connect/") && !url.contains("/oauth2/callback"),
                    new Page.WaitForURLOptions().setTimeout(45_000));
        } catch (com.microsoft.playwright.TimeoutError e) {
            // OIDC did not complete within 45s — check if login form is shown.
        }

        // If still on Keycloak auth, fill in credentials immediately before the auth session expires.
        if (page.url().contains("/protocol/openid-connect/auth")) {
            LOG.info("Keycloak re-authentication required for: " + sessionUrl);
            page.waitForSelector("input[name='username']",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.fill("input[name='username']", USERNAME);
            page.fill("input[name='password']", PASSWORD);
            page.click("input[type='submit'], button:has-text('Sign In')");
            page.waitForURL(url -> !url.contains("/protocol/openid-connect/") && !url.contains("/oauth2/callback"),
                    new Page.WaitForURLOptions().setTimeout(SESSION_TIMEOUT_MS));
        }

        // If OIDC state contained http:// (Gateway listener is HTTP), re-navigate to https://.
        // The OIDC cookie (Secure flag) was set during the https:// callback and will now be sent.
        if (page.url().startsWith("http://")) {
            LOG.info("Detected http:// after OIDC callback; re-navigating to: " + sessionUrl);
            page.navigate(sessionUrl);
            try {
                page.waitForURL(url -> !url.contains("/protocol/openid-connect/") && !url.contains("/oauth2/callback"),
                        new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));
            } catch (com.microsoft.playwright.TimeoutError e) {
                // ignore
            }
        }
        // Callers wait for their own content selector — no generic load wait needed here.
    }
}
