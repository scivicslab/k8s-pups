package com.scivicslab.k8spups.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What English Toolkit declares to k8s-pups. Needs no cluster and no external service. */
class EnglishToolkitPluginTest {

    private final EnglishToolkitPlugin plugin = new EnglishToolkitPlugin();

    @Test
    void name() {
        assertEquals("quarkus-english-toolkit", plugin.name());
        assertEquals("English Toolkit", plugin.displayName());
    }

    @Test
    void containerPort() {
        assertEquals(28200, plugin.containerPort());
    }

    @Test
    void containerImageIsPinnedAndFromTheConfiguredRegistry() {
        String image = plugin.containerImage();
        assertTrue(image.contains("quarkus-english-toolkit"), image);
        assertTrue(image.startsWith("${REGISTRY}/"), image);
        assertFalse(image.endsWith(":latest"), image);
    }

    @Test
    void connectionType() {
        assertEquals(ConnectionType.HTTP, plugin.connectionType());
    }

    @Test
    void sessionPrefixIsStrippedBeforeItArrives() {
        // The application serves at the container root and builds page URLs from PUPS_SESSION_PATH.
        assertFalse(plugin.passthroughPath());
    }

    @Test
    void writesGoToTheUsersOwnWorkspace() {
        assertTrue(plugin.workspaceEnabled());
        assertEquals("/home/ubuntu", plugin.userDataMountPath());
        assertFalse(plugin.readOnlyRootFilesystem());
        assertEquals(1000L, plugin.runAsUser());
        assertTrue(plugin.runAsNonRoot());
    }

    @Test
    void dictionaryPathsPointAtTheSharedMount() {
        var env = plugin.environmentVariables();
        assertEquals("/dictionaries/cobuild.json", env.get("ENGLISH_COBUILD_PATH"));
        assertEquals("/dictionaries/cobuild-ocr", env.get("ENGLISH_COBUILD_OCR_DIR"));
        assertEquals("/dictionaries/activator-ocr", env.get("ENGLISH_ACTIVATOR_OCR_DIR"));
    }

    @Test
    void withoutAConfiguredShareNoVolumeIsDeclared() {
        // The dictionary share is deployment config; unset, the tool still starts.
        assertTrue(plugin.nfsVolumes().isEmpty());
    }

    @Test
    void readinessAndResources() {
        assertEquals("/", plugin.readinessProbePath());
        assertTrue(plugin.resourceLimits().containsKey("cpu"));
        assertTrue(plugin.resourceLimits().containsKey("memory"));
    }
}
