package com.scivicslab.k8spups.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * E2E: verifies that the file-browser tool starts, accepts file uploads,
 * and allows file deletion.
 *
 * FileBrowser Quantum runs with noauth mode; Envoy SecurityPolicy handles authentication.
 * Storage is nfs-k8s (RWX), auto-created on first session start.
 */
class K8sPupsFileBrowserE2E extends K8sPupsE2EBase {

    public static void main(String[] args) throws Exception { new K8sPupsFileBrowserE2E().run(); }

    private static final String TEST_FILENAME = "e2e-test-upload.txt";
    private static final String TEST_CONTENT  = "E2E test file uploaded by K8sPupsFileBrowserE2E";

    void run() throws Exception {
        System.out.println("--- K8sPupsFileBrowserE2E ---");
        setup();
        Path uploadFile = Paths.get("/tmp/" + TEST_FILENAME);
        try {
            login();
            stopAllSessions();
            waitForUserPodsGone();
            launchToolSession("file-browser");
            String sessionPath = waitForOpenToolButton("file-browser");

            navigateToSession(sessionOrigin() + sessionPath);

            // Wait for Vue app to render the file listing
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(SESSION_TIMEOUT_MS));
            page.waitForSelector("#app",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(PAGE_TIMEOUT_MS));

            if (page.locator("text=Failed to load").isVisible())
                throw new AssertionError("file-browser: 'Failed to load' error is visible");
            if (page.locator("text=upstream connect error").isVisible())
                throw new AssertionError("file-browser: upstream connect error is visible");

            LOG.info("FileBrowser UI loaded at: " + page.url());

            // Create the test file on the local filesystem so Playwright can upload it
            Files.writeString(uploadFile, TEST_CONTENT);

            testUpload(uploadFile);
            testDelete();

            stopExistingSessionIfAny("file-browser");
        } finally {
            Files.deleteIfExists(uploadFile);
            teardown();
        }
        System.out.println("K8sPupsFileBrowserE2E: PASSED");
    }

    private void testUpload(Path uploadFile) {
        LOG.info("Testing file upload: " + TEST_FILENAME);

        // FileBrowser Quantum v1.4.0 has a hidden <input type="file" id="upload-input">.
        // setInputFiles() directly on it triggers the Vue onChange handler without
        // needing to open a native file chooser dialog.
        page.locator("#upload-input").setInputFiles(uploadFile);

        // Wait for the uploaded filename to appear in the file listing
        page.waitForSelector("text=" + TEST_FILENAME,
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(PAGE_TIMEOUT_MS));
        LOG.info("Upload confirmed: " + TEST_FILENAME + " is visible in listing");
    }

    private void testDelete() {
        LOG.info("Testing file deletion: " + TEST_FILENAME);

        // Click on the uploaded file to select it; the action toolbar appears on selection.
        page.locator("text=" + TEST_FILENAME).first().click();

        // The toolbar shows a "Delete" button when one or more files are selected.
        // This is the first "Delete" button to appear (in the action bar, not in a dialog).
        Locator deleteBtn = page.locator("button:has-text('Delete')");
        deleteBtn.first().waitFor(
                new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(PAGE_TIMEOUT_MS));
        deleteBtn.first().click();

        // FileBrowser shows a confirmation dialog (deleteWithoutConfirming=false by default).
        // The dialog also contains a "Delete" button — wait for it to appear after the toolbar click.
        page.waitForTimeout(500);
        Locator confirmBtn = page.locator("button:has-text('Delete')").last();
        confirmBtn.waitFor(
                new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(PAGE_TIMEOUT_MS));
        confirmBtn.click();

        // Verify the file is no longer visible in the listing
        page.waitForSelector("text=" + TEST_FILENAME,
                new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.HIDDEN)
                        .setTimeout(PAGE_TIMEOUT_MS));
        LOG.info("Delete confirmed: " + TEST_FILENAME + " is no longer in listing");
    }
}
