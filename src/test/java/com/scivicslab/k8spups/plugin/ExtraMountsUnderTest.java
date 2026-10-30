package com.scivicslab.k8spups.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The tools whose screen shows only the user's home get the group storage and the datasets under that home, where
 * their own file browser can open them; the others keep the absolute default paths.
 */
class ExtraMountsUnderTest {

    @Test
    void toolsThatShowOnlyTheHomeMountUnderIt() {
        assertEquals("/home/jovyan", new JupyterLabPlugin().extraMountsUnder());
        assertEquals("/home/jovyan", new JupyterAiPlugin().extraMountsUnder());
        assertEquals("/home/user", new GuacamolePlugin().extraMountsUnder());
        assertEquals("/srv", new FileBrowserPlugin().extraMountsUnder());
    }

    @Test
    void otherToolsKeepTheAbsoluteDefaultPaths() {
        assertNull(new GalaxyPlugin().extraMountsUnder(), "Galaxy's file sources read /groups and /datasets");
        assertNull(new CodeRaptorPlugin().extraMountsUnder());
    }

    @Test
    void galaxyKeepsItsFilesInADirectoryOfItsOwnInTheStorage() {
        assertEquals("galaxy", new GalaxyPlugin().userDataSubPath());
        assertNull(new JupyterLabPlugin().userDataSubPath(), "the home of the other tools is the root of the storage");
    }
}
