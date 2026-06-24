package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * E2E test for login and logout flows.
 * Verifies that login reaches the dashboard and logout clears the session,
 * requiring re-authentication to access the dashboard again.
 */
class K8sPupsLoginLogoutE2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception {
        new K8sPupsLoginLogoutE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- K8sPupsLoginLogoutE2E ---");
        setup();
        try {
            verifyLogin();
            verifyLogout();
        } finally {
            teardown();
        }
        System.out.println("K8sPupsLoginLogoutE2E: PASSED");
    }

    private void verifyLogin() {
        LOG.info("=== Login: navigate to /dashboard -> Keycloak -> fill credentials -> dashboard ===");
        login();
        String urlAfterLogin = page.url();
        if (!urlAfterLogin.contains("/dashboard")) {
            throw new AssertionError("Expected /dashboard after login, got: " + urlAfterLogin);
        }
        LOG.info("Login PASSED: " + urlAfterLogin);
    }

    private void verifyLogout() {
        LOG.info("=== Logout: click .btn-logout -> landing page -> /dashboard requires re-auth ===");

        // Log cookies before logout for diagnosis.
        context.cookies().forEach(c ->
            LOG.info("Cookie before logout: name=" + c.name + " domain=" + c.domain + " path=" + c.path));

        page.locator("a.btn-logout").first().click();

        // Keycloak end-session -> redirects to post_logout_redirect_uri (landing page).
        page.waitForURL(
                url -> !url.contains("/protocol/openid-connect/"),
                new Page.WaitForURLOptions().setTimeout(PAGE_TIMEOUT_MS));

        // Verify landing page is shown (not dashboard).
        page.waitForSelector(".landing",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        LOG.info("After logout, on landing page: " + page.url());

        // Log cookies after logout for diagnosis.
        context.cookies().forEach(c ->
            LOG.info("Cookie after logout: name=" + c.name + " domain=" + c.domain + " path=" + c.path));

        // Accessing /dashboard after logout must redirect to Keycloak login form.
        // page.navigate() follows all server-side redirects and settles at the final URL:
        //   - Keycloak SSO cleared: final URL is Keycloak auth page (login form visible)
        //   - Keycloak SSO still alive: final URL is /dashboard (auto-relogged in)
        page.navigate(BASE_URL + "/dashboard");
        String urlAfterNav = page.url();
        LOG.info("After re-navigating to /dashboard post-logout: " + urlAfterNav);

        // If the dashboard loaded, SSO session was not cleared by the logout.
        Locator brandName = page.locator("span.brand-name");
        if (brandName.count() > 0 && brandName.first().isVisible()) {
            throw new AssertionError(
                "Logout did not clear Keycloak SSO session: /dashboard accessible without re-auth at " + urlAfterNav);
        }

        // Keycloak login form must be visible.
        page.waitForSelector("input[name='username']",
                new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));
        LOG.info("Logout PASSED: /dashboard requires re-auth, login form visible at " + urlAfterNav);
    }
}
