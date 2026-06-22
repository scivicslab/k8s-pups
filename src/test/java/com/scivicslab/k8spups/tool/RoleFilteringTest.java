package com.scivicslab.k8spups.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TR.001.3 — ロールによるツールフィルタリング
 * ToolRoleFilter.filterForRoles() が期待するロール別フィルタリングを行うことを検証する。
 */
@Tag("TR.001.3")
@DisplayName("TR.001.3 — Role-Based Tool Filtering")
class RoleFilteringTest {

    private static final String REGISTRY_YAML = """
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

    private ToolRegistry registry;
    private static final Set<String> ALL_ENABLED = Set.of("ocr-file-manager", "quarkus-chat-ui");

    @BeforeEach
    void setup() throws Exception {
        registry = new YAMLMapper().readValue(REGISTRY_YAML, ToolRegistry.class);
    }

    @Test
    @DisplayName("knowledge-editor ロールには ocr-file-manager と quarkus-chat-ui が返る")
    void filter_knowledgeEditorRole_returnsBothTools() {
        List<ToolRegistryEntry> tools = ToolRoleFilter.filterForRoles(
            registry, List.of("knowledge-editor"), ALL_ENABLED::contains);
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("ocr-file-manager")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("quarkus-chat-ui")));
    }

    @Test
    @DisplayName("user ロールには quarkus-chat-ui のみが返る")
    void filter_userRole_returnsChatUiOnly() {
        List<ToolRegistryEntry> tools = ToolRoleFilter.filterForRoles(
            registry, List.of("user"), ALL_ENABLED::contains);
        assertEquals(1, tools.size());
        assertEquals("quarkus-chat-ui", tools.get(0).name());
    }

    @Test
    @DisplayName("admin ロールには全ツールが返る")
    void filter_adminRole_returnsAllTools() {
        List<ToolRegistryEntry> tools = ToolRoleFilter.filterForRoles(
            registry, List.of("admin"), ALL_ENABLED::contains);
        assertEquals(2, tools.size());
    }

    @Test
    @DisplayName("複数ロールの場合は和集合が返り重複しない")
    void filter_multipleRoles_returnsUnionWithoutDuplicates() {
        List<ToolRegistryEntry> tools = ToolRoleFilter.filterForRoles(
            registry, List.of("user", "knowledge-editor"), ALL_ENABLED::contains);
        assertEquals(2, tools.size());
        assertEquals(2,
            tools.stream().map(ToolRegistryEntry::name).distinct().count(),
            "No duplicate tool names");
    }

    @Test
    @DisplayName("ロールなし（空リスト）のとき 0 件返る")
    void filter_emptyRoles_returnsEmpty() {
        List<ToolRegistryEntry> tools = ToolRoleFilter.filterForRoles(
            registry, List.of(), ALL_ENABLED::contains);
        assertTrue(tools.isEmpty());
    }

    @Test
    @DisplayName("無効化されたツールは返らない")
    void filter_disabledTool_excluded() {
        Set<String> onlyChatUiEnabled = Set.of("quarkus-chat-ui");
        List<ToolRegistryEntry> tools = ToolRoleFilter.filterForRoles(
            registry, List.of("knowledge-editor"), onlyChatUiEnabled::contains);
        assertEquals(1, tools.size());
        assertEquals("quarkus-chat-ui", tools.get(0).name());
    }

    @Test
    @DisplayName("全ツールが無効化されているとき 0 件返る")
    void filter_allDisabled_returnsEmpty() {
        List<ToolRegistryEntry> tools = ToolRoleFilter.filterForRoles(
            registry, List.of("admin"), name -> false);
        assertTrue(tools.isEmpty());
    }

    @Test
    @DisplayName("ツールレジストリが空のとき 0 件返る")
    void filter_emptyRegistry_returnsEmpty() {
        ToolRegistry empty = new ToolRegistry(List.of());
        List<ToolRegistryEntry> tools = ToolRoleFilter.filterForRoles(
            empty, List.of("admin"), ALL_ENABLED::contains);
        assertTrue(tools.isEmpty());
    }
}
