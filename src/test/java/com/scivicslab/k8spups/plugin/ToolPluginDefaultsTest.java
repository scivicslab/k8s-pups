package com.scivicslab.k8spups.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolPlugin interface default methods.
 * Uses an anonymous minimal implementation to test shared defaults.
 */
class ToolPluginDefaultsTest {

    /** Minimal concrete implementation that only overrides required methods. */
    private static ToolPlugin minimalPlugin() {
        return new ToolPlugin() {
            @Override public String name() { return "test-tool"; }
            @Override public String displayName() { return "Test Tool"; }
            @Override public String containerImage() { return "test:latest"; }
            @Override public int containerPort() { return 9090; }
            @Override public ConnectionType connectionType() { return ConnectionType.HTTP; }
        };
    }

    @Test
    @DisplayName("Default icon path follows naming convention")
    void defaultIconFollowsConvention() {
        ToolPlugin plugin = minimalPlugin();
        assertEquals("icons/test-tool.png", plugin.icon());
    }

    @Test
    @DisplayName("Default description is empty string")
    void defaultDescriptionIsEmpty() {
        assertEquals("", minimalPlugin().description());
    }

    @Test
    @DisplayName("Default environmentVariables is empty map")
    void defaultEnvironmentVariablesIsEmpty() {
        assertTrue(minimalPlugin().environmentVariables().isEmpty());
    }

    @Test
    @DisplayName("Default resourceRequests contains cpu and memory")
    void defaultResourceRequestsContainsCpuAndMemory() {
        Map<String, String> requests = minimalPlugin().resourceRequests();
        assertTrue(requests.containsKey("cpu"), "requests must have cpu");
        assertTrue(requests.containsKey("memory"), "requests must have memory");
    }

    @Test
    @DisplayName("Default resourceLimits contains cpu and memory")
    void defaultResourceLimitsContainsCpuAndMemory() {
        Map<String, String> limits = minimalPlugin().resourceLimits();
        assertTrue(limits.containsKey("cpu"), "limits must have cpu");
        assertTrue(limits.containsKey("memory"), "limits must have memory");
    }

    @Test
    @DisplayName("Default userDataMountPath is null")
    void defaultUserDataMountPathIsNull() {
        assertNull(minimalPlugin().userDataMountPath());
    }

    @Test
    @DisplayName("Default readOnlyRootFilesystem is true (secure default)")
    void defaultReadOnlyRootFilesystemIsTrue() {
        assertTrue(minimalPlugin().readOnlyRootFilesystem());
    }

    @Test
    @DisplayName("Default runAsUser is 1000")
    void defaultRunAsUserIs1000() {
        assertEquals(1000L, minimalPlugin().runAsUser());
    }

    @Test
    @DisplayName("Default runAsNonRoot is true (secure default)")
    void defaultRunAsNonRootIsTrue() {
        assertTrue(minimalPlugin().runAsNonRoot());
    }

    @Test
    @DisplayName("Default seccompProfileType is RuntimeDefault")
    void defaultSeccompProfileTypeIsRuntimeDefault() {
        assertEquals("RuntimeDefault", minimalPlugin().seccompProfileType());
    }

    @Test
    @DisplayName("Default passthroughPath is false")
    void defaultPassthroughPathIsFalse() {
        assertFalse(minimalPlugin().passthroughPath());
    }

    @Test
    @DisplayName("Default workspaceEnabled is false")
    void defaultWorkspaceEnabledIsFalse() {
        assertFalse(minimalPlugin().workspaceEnabled());
    }

    @Test
    @DisplayName("Default workspaceSidecar is null")
    void defaultWorkspaceSidecarIsNull() {
        assertNull(minimalPlugin().workspaceSidecar());
    }

    @Test
    @DisplayName("Default singleInstance is false")
    void defaultSingleInstanceIsFalse() {
        assertFalse(minimalPlugin().singleInstance());
    }

    @Test
    @DisplayName("Default batchMode is false")
    void defaultBatchModeIsFalse() {
        assertFalse(minimalPlugin().batchMode());
    }

    @Test
    @DisplayName("Default resourceProfiles contains a single default profile")
    void defaultResourceProfilesContainsOneEntry() {
        var profiles = minimalPlugin().resourceProfiles();
        assertEquals(1, profiles.size());
        assertEquals("default", profiles.get(0).name());
    }

    @Test
    @DisplayName("Default userParameters is empty list")
    void defaultUserParametersIsEmpty() {
        assertTrue(minimalPlugin().userParameters().isEmpty());
    }

    @Test
    @DisplayName("Default nfsVolumes is empty list")
    void defaultNfsVolumesIsEmpty() {
        assertTrue(minimalPlugin().nfsVolumes().isEmpty());
    }

    @Test
    @DisplayName("Default writablePaths is empty list")
    void defaultWritablePathsIsEmpty() {
        assertTrue(minimalPlugin().writablePaths().isEmpty());
    }
}
