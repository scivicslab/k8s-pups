package com.scivicslab.k8spups.k8s;

import com.scivicslab.k8spups.plugin.ConnectionType;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SessionInfo record.
 * Verifies podName(), serviceName(), and constructor behavior.
 */
class SessionInfoTest {

    /** Minimal stub ToolPlugin for testing. */
    private static ToolPlugin stubPlugin(String name) {
        return new ToolPlugin() {
            @Override public String name() { return name; }
            @Override public String displayName() { return name; }
            @Override public String containerImage() { return "test-image:latest"; }
            @Override public int containerPort() { return 8080; }
            @Override public ConnectionType connectionType() { return ConnectionType.HTTP; }
        };
    }

    @Test
    @DisplayName("podName is derived from tool name and session ID")
    void podNameFormat() {
        ToolPlugin plugin = stubPlugin("jupyter-lab");
        SessionInfo info = new SessionInfo(
            "abc123", "alice", plugin, Collections.emptyList(),
            null, null, Collections.emptyMap(), null, null, null
        );

        assertEquals("pups-jupyter-lab-abc123", info.podName());
    }

    @Test
    @DisplayName("serviceName is derived from session ID")
    void serviceNameFormat() {
        ToolPlugin plugin = stubPlugin("chat-ui");
        SessionInfo info = new SessionInfo(
            "xyz789", "bob", plugin, Collections.emptyList(),
            null, null, Collections.emptyMap(), null, null, null
        );

        assertEquals("pups-svc-xyz789", info.serviceName());
    }

    @Test
    @DisplayName("Backward-compatible constructor without storage type sets userStorageType to null")
    void backwardCompatConstructorSetsStorageTypeNull() {
        ToolPlugin plugin = stubPlugin("guacamole");
        SessionInfo info = new SessionInfo(
            "s1", "user1", plugin, Collections.emptyList(),
            null, null, Collections.emptyMap(), "20Gi", null
        );

        assertNull(info.userStorageType());
    }

    @Test
    @DisplayName("additionalMounts defaults to empty list in backward-compat constructor")
    void additionalMountsDefaultsToEmptyList() {
        ToolPlugin plugin = stubPlugin("ai-toolkit");
        SessionInfo info = new SessionInfo(
            "s2", "user2", plugin, Collections.emptyList(),
            null, null, Collections.emptyMap(), null, null, null
        );

        assertNotNull(info.additionalMounts());
        assertTrue(info.additionalMounts().isEmpty());
    }

    @Test
    @DisplayName("Full constructor stores additional mounts")
    void fullConstructorStoresAdditionalMounts() {
        ToolPlugin plugin = stubPlugin("jupyter-lab");
        List<MountSpec> mounts = List.of(new MountSpec("nfs-k8s", "/data", null));
        SessionInfo info = new SessionInfo(
            "s3", "user3", plugin, Collections.emptyList(),
            null, "standard", Collections.emptyMap(), "100Gi", "longhorn", null, mounts
        );

        assertEquals(1, info.additionalMounts().size());
        assertEquals("/data", info.additionalMounts().get(0).mountPath());
    }

    @Test
    @DisplayName("userParams defaults to empty map in minimal constructor")
    void userParamsDefaultsToEmptyMap() {
        ToolPlugin plugin = stubPlugin("docusaurus");
        SessionInfo info = new SessionInfo(
            "s4", "user4", plugin, Collections.emptyList(),
            null, null
        );

        assertNotNull(info.userParams());
        assertTrue(info.userParams().isEmpty());
    }

    @Test
    @DisplayName("toolConfigEnv stored correctly in full constructor")
    void toolConfigEnvStoredCorrectly() {
        ToolPlugin plugin = stubPlugin("ocr-file-manager");
        Map<String, String> toolEnv = Map.of(
            "OCR_SERVER_URL", "http://192.168.5.17:8013",
            "OPENWEBUI_URL",  "https://example.com");
        SessionInfo info = new SessionInfo(
            "s5", "user5", plugin, Collections.emptyList(),
            null, "default", Collections.emptyMap(),
            null, null, null, Collections.emptyList(), toolEnv
        );

        assertEquals("http://192.168.5.17:8013", info.toolConfigEnv().get("OCR_SERVER_URL"));
        assertEquals("https://example.com",       info.toolConfigEnv().get("OPENWEBUI_URL"));
    }

    @Test
    @DisplayName("toolConfigEnv defaults to empty map in backward-compat constructor")
    void toolConfigEnvDefaultsToEmptyMap() {
        ToolPlugin plugin = stubPlugin("guacamole");
        SessionInfo info = new SessionInfo(
            "s6", "user6", plugin, Collections.emptyList(),
            null, null, Collections.emptyMap(), null, null, null,
            Collections.emptyList()
        );

        assertNotNull(info.toolConfigEnv());
        assertTrue(info.toolConfigEnv().isEmpty());
    }
}
