package com.scivicslab.k8spups.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TR.002.1 — ツール設定のプレフィックス付き保存と取得
 * ToolConfigStore が "toolName.PARAM" 形式でキーを管理することを検証する。
 */
@Tag("TR.002.1")
@DisplayName("TR.002.1 — Config Storage With Prefix")
class ConfigStorageWithPrefixTest {

    private static final String TOOL = "ocr-file-manager";

    @Test
    @DisplayName("configMapKey が toolName.paramName の形式を返す")
    void configMapKey_returnsCorrectFormat() {
        assertEquals("ocr-file-manager.OCR_SERVER_URL",
            ToolConfigStore.configMapKey(TOOL, "OCR_SERVER_URL"));
    }

    @Test
    @DisplayName("enableKey が toolName.enable の形式を返す")
    void enableKey_returnsCorrectFormat() {
        assertEquals("ocr-file-manager.enable", ToolConfigStore.enableKey(TOOL));
    }

    @Test
    @DisplayName("非秘密パラメータは cmOut にプレフィックスつきで保存される")
    void partition_nonSecretParam_goesToConfigMap() {
        ParameterSpec spec = new ParameterSpec("OCR_SERVER_URL", "desc", null, true, false);
        ToolDescriptor desc = new ToolDescriptor(TOOL, "", "1.0", List.of(spec));

        Map<String, String> cmOut = new HashMap<>();
        Map<String, String> secretOut = new HashMap<>();
        ToolConfigStore.partition(TOOL, Map.of("OCR_SERVER_URL", "http://example"),
            desc, cmOut, secretOut);

        assertTrue(cmOut.containsKey("ocr-file-manager.OCR_SERVER_URL"));
        assertEquals("http://example", cmOut.get("ocr-file-manager.OCR_SERVER_URL"));
        assertTrue(secretOut.isEmpty());
    }

    @Test
    @DisplayName("秘密パラメータは secretOut に Base64 でプレフィックスつき保存される")
    void partition_secretParam_goesToSecretBase64() {
        ParameterSpec spec = new ParameterSpec("OPENWEBUI_API_KEY", "desc", null, true, true);
        ToolDescriptor desc = new ToolDescriptor(TOOL, "", "1.0", List.of(spec));

        Map<String, String> cmOut = new HashMap<>();
        Map<String, String> secretOut = new HashMap<>();
        ToolConfigStore.partition(TOOL, Map.of("OPENWEBUI_API_KEY", "sk-secret"),
            desc, cmOut, secretOut);

        assertTrue(secretOut.containsKey("ocr-file-manager.OPENWEBUI_API_KEY"));
        String decoded = new String(Base64.getDecoder().decode(
            secretOut.get("ocr-file-manager.OPENWEBUI_API_KEY")));
        assertEquals("sk-secret", decoded);
        assertTrue(cmOut.isEmpty());
    }

    @Test
    @DisplayName("extractFromConfigMap がプレフィックスを除去して返す")
    void extractFromConfigMap_stripsPrefix() {
        Map<String, String> cmData = Map.of(
            "ocr-file-manager.OCR_SERVER_URL", "http://example",
            "ocr-file-manager.OPENWEBUI_URL", "https://other",
            "another-tool.SOME_KEY", "should-not-appear"
        );
        Map<String, String> result = ToolConfigStore.extractFromConfigMap(TOOL, cmData);

        assertEquals(2, result.size());
        assertEquals("http://example", result.get("OCR_SERVER_URL"));
        assertEquals("https://other", result.get("OPENWEBUI_URL"));
        assertFalse(result.containsKey("another-tool.SOME_KEY"));
    }

    @Test
    @DisplayName("extractFromSecret がプレフィックス除去と Base64 デコードを行う")
    void extractFromSecret_stripsPrefixAndDecodes() {
        String encoded = Base64.getEncoder().encodeToString("sk-secret".getBytes());
        Map<String, String> secretData = Map.of("ocr-file-manager.OPENWEBUI_API_KEY", encoded);

        Map<String, String> result = ToolConfigStore.extractFromSecret(TOOL, secretData);
        assertEquals("sk-secret", result.get("OPENWEBUI_API_KEY"));
    }

    @Test
    @DisplayName("別ツールの設定が混在していても自ツールの設定のみ返る")
    void extractFromConfigMap_onlyReturnsOwnTool() {
        Map<String, String> cmData = Map.of(
            "ocr-file-manager.OCR_SERVER_URL", "http://ocr",
            "quarkus-chat-ui.CHAT_URL", "http://chat"
        );
        Map<String, String> ocr = ToolConfigStore.extractFromConfigMap("ocr-file-manager", cmData);
        Map<String, String> chat = ToolConfigStore.extractFromConfigMap("quarkus-chat-ui", cmData);

        assertEquals(1, ocr.size());
        assertTrue(ocr.containsKey("OCR_SERVER_URL"));
        assertEquals(1, chat.size());
        assertTrue(chat.containsKey("CHAT_URL"));
    }
}
