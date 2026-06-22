package com.scivicslab.k8spups.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServicePortalPlugin configuration values.
 */
class ServicePortalPluginTest {

    private ServicePortalPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new ServicePortalPlugin();
    }

    @Test
    @DisplayName("name is service-portal")
    void name() {
        assertEquals("service-portal", plugin.name());
    }

    @Test
    @DisplayName("containerPort is 28000")
    void containerPort() {
        assertEquals(28000, plugin.containerPort());
    }

    @Test
    @DisplayName("containerImage references quarkus-service-portal")
    void containerImage() {
        assertTrue(plugin.containerImage().contains("quarkus-service-portal"),
            "image must reference quarkus-service-portal");
        assertFalse(plugin.containerImage().endsWith(":latest"),
            "image must use a fixed version tag, not :latest");
    }

    @Test
    @DisplayName("connectionType is HTTP")
    void connectionType() {
        assertEquals(ConnectionType.HTTP, plugin.connectionType());
    }

    @Test
    @DisplayName("readOnlyRootFilesystem is false (sub-process management requires writes)")
    void readOnlyRootFilesystem() {
        assertFalse(plugin.readOnlyRootFilesystem());
    }

    @Test
    @DisplayName("workspaceEnabled is true")
    void workspaceEnabled() {
        assertTrue(plugin.workspaceEnabled());
    }

    @Test
    @DisplayName("workspaceMountPath is /home/devteam/works")
    void workspaceMountPath() {
        assertEquals("/home/devteam/works", plugin.workspaceMountPath());
    }

    @Test
    @DisplayName("workspaceSubPath is works (mount only ~/works, not entire home)")
    void workspaceSubPath() {
        assertEquals("works", plugin.workspaceSubPath());
    }

    @Test
    @DisplayName("readinessProbePath is /api/status")
    void readinessProbePath() {
        assertEquals("/api/status", plugin.readinessProbePath());
    }

    @Test
    @DisplayName("singleInstance is true (one workspace per user)")
    void singleInstance() {
        assertTrue(plugin.singleInstance());
    }

    @Test
    @DisplayName("resourceLimits has cpu and memory")
    void resourceLimits() {
        var limits = plugin.resourceLimits();
        assertTrue(limits.containsKey("cpu"));
        assertTrue(limits.containsKey("memory"));
    }

    @Test
    @DisplayName("resourceProfiles is non-empty")
    void resourceProfiles() {
        assertFalse(plugin.resourceProfiles().isEmpty());
    }

    @Test
    @DisplayName("workspaceSidecar is null (single-container mode)")
    void workspaceSidecarIsNull() {
        assertNull(plugin.workspaceSidecar());
    }

    @Test
    @DisplayName("passthroughPath is false (HTTPRoute rewrites path prefix)")
    void passthroughPath() {
        assertFalse(plugin.passthroughPath());
    }
}
