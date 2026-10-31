package com.scivicslab.k8spups.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configuration that File Browser is started with turns off the seasonal themes: on Halloween FileBrowser opens a
 * notice that cannot be closed and that blocks the upload window behind it.
 */
class FileBrowserConfigTest {

    @Test
    void seasonalThemesAreTurnedOff() {
        String script = String.join(" ", new FileBrowserPlugin().containerCommand());
        assertTrue(script.contains("frontend:\n  styling:\n    disableEventThemes: true\n"), script);
    }
}
