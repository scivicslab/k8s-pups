package com.scivicslab.k8spups.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TR.001.2 — ツールレジストリ YAML パース
 * tools.yaml の文字列を ToolRegistry Java クラスに正しく変換できることを検証する。
 */
@Tag("TR.001.2")
@DisplayName("TR.001.2 — Tool Registry YAML Parsing")
class ToolRegistryYamlParsingTest {

    private static final ObjectMapper YAML = new YAMLMapper();

    private static final String VALID_YAML = """
            tools:
              - name: ocr-file-manager
                image: 192.168.5.23:32000/ocr-file-manager:latest
                roles:
                  - knowledge-editor
                  - admin
              - name: quarkus-chat-ui
                image: 192.168.5.23:32000/quarkus-chat-ui:latest
                roles:
                  - user
                  - knowledge-editor
                  - admin
            """;

    @Test
    @DisplayName("有効な tools.yaml を ToolRegistry に変換できる")
    void parse_validYaml_returnsToolRegistry() throws Exception {
        ToolRegistry registry = YAML.readValue(VALID_YAML, ToolRegistry.class);
        assertNotNull(registry);
        assertEquals(2, registry.tools().size());
    }

    @Test
    @DisplayName("ツール名・イメージ・ロールが正しくマップされる")
    void parse_validYaml_fieldsAreCorrect() throws Exception {
        ToolRegistry registry = YAML.readValue(VALID_YAML, ToolRegistry.class);
        ToolRegistryEntry first = registry.tools().get(0);
        assertEquals("ocr-file-manager", first.name());
        assertEquals("192.168.5.23:32000/ocr-file-manager:latest", first.image());
        assertEquals(List.of("knowledge-editor", "admin"), first.roles());
    }

    @Test
    @DisplayName("tools が空の YAML は空のリストを返す")
    void parse_emptyTools_returnsEmptyList() throws Exception {
        String yaml = "tools: []\n";
        ToolRegistry registry = YAML.readValue(yaml, ToolRegistry.class);
        assertTrue(registry.tools().isEmpty());
    }

    @Test
    @DisplayName("getTool() で名前によるルックアップができる")
    void getTool_existingName_returnsEntry() throws Exception {
        ToolRegistry registry = YAML.readValue(VALID_YAML, ToolRegistry.class);
        ToolRegistryEntry entry = registry.getTool("quarkus-chat-ui");
        assertNotNull(entry);
        assertEquals("192.168.5.23:32000/quarkus-chat-ui:latest", entry.image());
    }

    @Test
    @DisplayName("getTool() に存在しない名前を渡すと null を返す")
    void getTool_unknownName_returnsNull() throws Exception {
        ToolRegistry registry = YAML.readValue(VALID_YAML, ToolRegistry.class);
        assertNull(registry.getTool("nonexistent-tool"));
    }
}
