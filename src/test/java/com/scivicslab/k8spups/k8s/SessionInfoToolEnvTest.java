package com.scivicslab.k8spups.k8s;

import com.scivicslab.k8spups.plugin.ConnectionType;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TR.002.2 — SessionInfo へのツール設定格納
 * createSession() が toolConfigEnv を SessionInfo に詰めることを、
 * SessionInfo レコード自体の動作で検証する。
 */
@Tag("TR.002.2")
@DisplayName("TR.002.2 — SessionInfo Tool Env")
class SessionInfoToolEnvTest {

    private static final ToolPlugin STUB_PLUGIN = new ToolPlugin() {
        @Override public String name() { return "ocr-file-manager"; }
        @Override public String displayName() { return "OCR File Manager"; }
        @Override public String containerImage() { return "registry/ocr:latest"; }
        @Override public int containerPort() { return 8080; }
        @Override public ConnectionType connectionType() { return ConnectionType.HTTP; }
    };

    @Test
    @DisplayName("toolConfigEnv が正しく格納される")
    void sessionInfo_withToolConfigEnv_storesCorrectly() {
        Map<String, String> toolConfigEnv = Map.of(
            "OCR_SERVER_URL", "http://192.168.5.17:8013",
            "OPENWEBUI_URL", "https://example.com");

        SessionInfo info = new SessionInfo(
            "sess-001", "testuser", STUB_PLUGIN,
            List.of(), null, "default",
            Collections.emptyMap(), null, null, null,
            Collections.emptyList(), toolConfigEnv);

        assertEquals("http://192.168.5.17:8013", info.toolConfigEnv().get("OCR_SERVER_URL"));
        assertEquals("https://example.com", info.toolConfigEnv().get("OPENWEBUI_URL"));
    }

    @Test
    @DisplayName("toolConfigEnv 省略コンストラクタは空マップになる")
    void sessionInfo_withoutToolConfigEnv_isEmptyMap() {
        SessionInfo info = new SessionInfo(
            "sess-002", "testuser", STUB_PLUGIN,
            List.of(), null, "default",
            Collections.emptyMap(), null, null, null,
            Collections.emptyList());

        assertNotNull(info.toolConfigEnv());
        assertTrue(info.toolConfigEnv().isEmpty());
    }

    @Test
    @DisplayName("静的プラグインのセッションは toolConfigEnv が空")
    void sessionInfo_staticPlugin_toolConfigEnvIsEmpty() {
        SessionInfo info = new SessionInfo(
            "sess-003", "testuser", STUB_PLUGIN,
            List.of(), null, "default");

        assertTrue(info.toolConfigEnv().isEmpty());
    }
}
