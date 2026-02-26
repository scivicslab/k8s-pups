import { test, expect } from '@playwright/test';

const BASE = '/pups';
const USERNAME = process.env.TEST_USERNAME ?? 'testadmin';
const PASSWORD = process.env.TEST_PASSWORD;
const MAILHOG_API = 'http://mailhog.sc-account-bg.svc:8025/api/v2';

if (!PASSWORD) {
  throw new Error('TEST_PASSWORD env var is required. Usage: TEST_PASSWORD=xxx npx playwright test');
}

/**
 * Fetch the latest email OTP code from MailHog via kubectl exec.
 */
async function fetchEmailOtp(): Promise<string> {
  const { execSync } = require('child_process');
  // Delete old messages first to avoid stale codes
  execSync(
    `kubectl exec -n k8s-pups deployment/k8s-pups-controller -- curl -s -X DELETE "${MAILHOG_API}/messages"`,
    { encoding: 'utf-8' }
  );
  // Wait for the new email to arrive
  for (let i = 0; i < 15; i++) {
    await new Promise(r => setTimeout(r, 2000));
    const raw = execSync(
      `kubectl exec -n k8s-pups deployment/k8s-pups-controller -- curl -s "${MAILHOG_API}/messages?start=0&limit=1"`,
      { encoding: 'utf-8' }
    );
    const data = JSON.parse(raw);
    if (data.count > 0) {
      const body = data.items[0].Content.Body;
      const match = body.match(/Access code:\s*(\d{6})/);
      if (match) {
        console.log(`Email OTP code: ${match[1]}`);
        return match[1];
      }
    }
  }
  throw new Error('Failed to retrieve email OTP from MailHog within 30 seconds');
}

test.describe('k8s-pups E2E', () => {

  test('full session lifecycle', async ({ page }) => {

    // --- Step 1: Navigate to /pups/ → auto-redirect to Keycloak login ---
    await test.step('Navigate to app and reach Keycloak login', async () => {
      await page.goto(BASE + '/');
      // /pups/ redirects to /pups/dashboard, which requires auth → Keycloak login
      await expect(page.locator('input[name="username"]')).toBeVisible({ timeout: 15_000 });
      await page.screenshot({ path: 'screenshots/01-keycloak-login.png' });
    });

    // --- Step 2: Enter credentials ---
    await test.step('Enter username and password', async () => {
      await page.fill('input[name="username"]', USERNAME);
      await page.fill('input[name="password"]', PASSWORD);
      await page.screenshot({ path: 'screenshots/02-credentials-entered.png' });
      // Clear old emails before triggering the OTP send
      const { execSync } = require('child_process');
      execSync(
        `kubectl exec -n k8s-pups deployment/k8s-pups-controller -- curl -s -X DELETE "${MAILHOG_API}/messages"`,
        { encoding: 'utf-8' }
      );
      await page.click('input[type="submit"], button:has-text("Sign In")');
    });

    // --- Step 3: Handle 2FA (email OTP or TOTP) ---
    await test.step('Handle email OTP 2FA', async () => {
      // Wait for the 2FA page to appear
      await page.waitForLoadState('domcontentloaded');
      await page.waitForTimeout(1000);
      await page.screenshot({ path: 'screenshots/03a-2fa-page.png' });

      // Check if we're on the email OTP page
      const emailOtpText = page.locator('text=six digit code');
      const isEmailOtp = await emailOtpText.isVisible({ timeout: 5_000 }).catch(() => false);

      if (isEmailOtp) {
        console.log('Email OTP page detected, fetching code from MailHog...');
        const code = await fetchEmailOtp();

        // Find the editable OTP input field (exclude readonly username display)
        const otpInput = page.locator('input:not([type="hidden"]):not([readonly])').first();
        await otpInput.fill(code);
        await page.screenshot({ path: 'screenshots/03b-otp-entered.png' });
        await page.click('input[type="submit"], button:has-text("Sign In")');
      } else {
        console.log('No email OTP page detected');
      }
    });

    // --- Step 4: Dashboard loads ---
    await test.step('Dashboard loads with user info', async () => {
      await expect(page.locator('text=k8s-pups Dashboard')).toBeVisible({ timeout: 30_000 });
      await expect(page.locator(`text=${USERNAME}`)).toBeVisible();
      await page.screenshot({ path: 'screenshots/04-dashboard.png' });
    });

    // --- Step 5: Check for existing session or start new one ---
    const hasActiveSession = await page.locator('text=Active Session').isVisible().catch(() => false);

    if (hasActiveSession) {
      await test.step('Stop existing session', async () => {
        console.log('Found existing session, stopping it first...');
        await page.click('button:has-text("Stop Session")');
        await expect(page.locator('text=Start a Session')).toBeVisible({ timeout: 15_000 });
        await page.screenshot({ path: 'screenshots/05-session-stopped.png' });
      });
    }

    // --- Step 6: Start new session (with network tracing) ---
    await test.step('Click LLM Coding Agent button', async () => {
      await expect(page.locator('text=Start a Session')).toBeVisible();

      // Capture all network requests during button click
      const requests: string[] = [];
      page.on('request', req => requests.push(`>> ${req.method()} ${req.url()}`));
      page.on('response', resp => requests.push(`<< ${resp.status()} ${resp.url()}`));

      console.log(`Before click - URL: ${page.url()}`);
      await page.click('button:has-text("LLM Coding Agent")');
      await page.waitForLoadState('domcontentloaded');
      await page.waitForTimeout(2000);

      console.log(`After click - URL: ${page.url()}`);
      console.log('Network trace:');
      requests.forEach(r => console.log('  ' + r));

      const pageContent = await page.content();
      console.log(`Page content (first 500 chars): ${pageContent.slice(0, 500)}`);
      await page.screenshot({ path: 'screenshots/06-session-starting.png' });

      // Check if we ended up on "Resource not found"
      const isResourceNotFound = await page.locator('text=Resource not found').isVisible().catch(() => false);
      if (isResourceNotFound) {
        console.error(`RESOURCE NOT FOUND at URL: ${page.url()}`);
        // Try navigating directly to dashboard
        console.log('Attempting manual navigation to /pups/dashboard...');
        await page.goto(BASE + '/dashboard');
        await page.waitForLoadState('domcontentloaded');
        console.log(`After manual nav - URL: ${page.url()}`);
        await page.screenshot({ path: 'screenshots/06b-manual-dashboard.png' });
      }
    });

    // --- Step 7: Wait for session to become READY ---
    await test.step('Session becomes READY', async () => {
      // If page auto-refreshes, wait for READY. Otherwise poll manually.
      for (let i = 0; i < 20; i++) {
        const isReady = await page.locator('text=READY').isVisible().catch(() => false);
        if (isReady) break;

        const isDashboard = await page.locator('text=k8s-pups Dashboard').isVisible().catch(() => false);
        if (isDashboard && !isReady) {
          // Dashboard visible but not READY yet — wait for auto-refresh
          await page.waitForTimeout(3000);
          continue;
        }

        // Not on dashboard — try navigating there
        await page.goto(BASE + '/dashboard');
        await page.waitForLoadState('domcontentloaded');
        await page.waitForTimeout(2000);
      }
      await expect(page.locator('text=READY')).toBeVisible({ timeout: 10_000 });
      await page.screenshot({ path: 'screenshots/07-session-ready.png' });
    });

    // --- Step 8: Check "Open Tool" link ---
    await test.step('Open Tool link appears and works', async () => {
      const openToolLink = page.locator('a:has-text("Open Tool")');
      await expect(openToolLink).toBeVisible();

      const href = await openToolLink.getAttribute('href');
      console.log(`Open Tool link href: ${href}`);
      expect(href).toMatch(/\/session\/[a-f0-9-]+\//);

      // Navigate to the tool URL
      await page.goto(href!);
      await page.waitForLoadState('domcontentloaded');

      // Check the coder-agent page loaded
      const pageContent = await page.content();
      console.log(`Coder agent page title: ${await page.title()}`);
      console.log(`Page content length: ${pageContent.length}`);
      console.log(`Page URL: ${page.url()}`);
      await page.screenshot({ path: 'screenshots/08-coder-agent.png' });

      // Verify it's the coder-agent page (or at least not an error)
      const hasError = await page.locator('text=Resource not found').isVisible().catch(() => false);
      if (hasError) {
        console.error('ERROR: "Resource not found" on coder-agent page!');
      }
      expect(hasError).toBe(false);

      // Verify SSE connection becomes 'ready' (not stuck on 'reconnecting')
      const connStatus = page.locator('#connection-status');
      await expect(connStatus).toBeVisible();
      const statusText = await connStatus.textContent();
      console.log(`Connection status: ${statusText}`);
      // Wait up to 10s for SSE to connect
      await expect(connStatus).toHaveText('ready', { timeout: 10_000 });
      console.log('SSE connection established successfully');

      // Verify vLLM models appear in the model dropdown
      const localModels = page.locator('#model-select option[data-type="local"]');
      const localCount = await localModels.count();
      console.log(`Local (vLLM) models found: ${localCount}`);
      for (let i = 0; i < localCount; i++) {
        const name = await localModels.nth(i).textContent();
        console.log(`  Local model: ${name}`);
      }
      expect(localCount).toBeGreaterThan(0);
      await page.screenshot({ path: 'screenshots/08a-vllm-models.png' });
    });

    // --- Step 9: Send chat to vLLM and verify response ---
    await test.step('Send chat to vLLM and verify response', async () => {
      // Select the first local model
      const localOption = page.locator('#model-select option[data-type="local"]').first();
      const modelValue = await localOption.getAttribute('value');
      console.log(`Selecting local model: ${modelValue}`);
      await page.selectOption('#model-select', modelValue!);

      // Type a simple prompt and send
      await page.fill('#prompt-input', 'Say "hello" and nothing else.');
      await page.click('#send-btn');

      // Wait for assistant response to complete (streaming class removed on result event)
      const assistantMsg = page.locator('.message.assistant:not(.streaming)').last();
      await expect(assistantMsg).toBeVisible({ timeout: 120_000 });

      // Verify response has a .message-footer (added on result event with cost/duration)
      const footer = assistantMsg.locator('.message-footer');
      await expect(footer).toBeVisible({ timeout: 5_000 });

      // Log the response
      const responseText = await assistantMsg.textContent();
      console.log(`vLLM response (first 200 chars): ${responseText?.slice(0, 200)}`);

      // Verify no error message appeared
      const errorMsgs = page.locator('.message.error');
      const errorCount = await errorMsgs.count();
      if (errorCount > 0) {
        const lastError = await errorMsgs.last().textContent();
        console.error(`Error message found: ${lastError}`);
      }
      expect(errorCount).toBe(0);

      await page.screenshot({ path: 'screenshots/09-vllm-chat-response.png' });
    });

    // --- Step 10: Go back to dashboard and stop session ---
    await test.step('Stop session and cleanup', async () => {
      await page.goto(BASE + '/dashboard');
      await expect(page.locator('text=Active Session')).toBeVisible({ timeout: 15_000 });
      await page.click('button:has-text("Stop Session")');
      await expect(page.locator('text=Start a Session')).toBeVisible({ timeout: 30_000 });
      await page.screenshot({ path: 'screenshots/10-cleanup-done.png' });
    });
  });
});
