package com.scivicslab.k8spups.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TR.001.1 — JWT ロール抽出
 * JwtRoleExtractor.extractRoles() が realm_access.roles と
 * resource_access.*.roles の両方を正しく抽出することを検証する。
 */
@Tag("TR.001.1")
@DisplayName("TR.001.1 — JWT Role Extraction")
class JwtRoleExtractionTest {

    @Test
    @DisplayName("realm_access.roles から単一ロールを抽出できる")
    void extractRoles_realmAccessSingleRole_returnsRole() {
        Map<String, Object> realmAccess = Map.of("roles", List.of("knowledge-editor"));
        List<String> roles = JwtRoleExtractor.extractRoles(realmAccess, null);
        assertEquals(List.of("knowledge-editor"), roles);
    }

    @Test
    @DisplayName("realm_access.roles から複数ロールを抽出できる")
    void extractRoles_realmAccessMultipleRoles_returnsAll() {
        Map<String, Object> realmAccess = Map.of(
            "roles", List.of("user", "knowledge-editor", "offline_access"));
        List<String> roles = JwtRoleExtractor.extractRoles(realmAccess, null);
        assertEquals(3, roles.size());
        assertTrue(roles.contains("user"));
        assertTrue(roles.contains("knowledge-editor"));
        assertTrue(roles.contains("offline_access"));
    }

    @Test
    @DisplayName("resource_access の全クライアントのロールを抽出できる")
    void extractRoles_resourceAccessMultipleClients_returnsAll() {
        Map<String, Object> resourceAccess = Map.of(
            "k8s-pups",  Map.of("roles", List.of("admin")),
            "account",   Map.of("roles", List.of("manage-account"))
        );
        List<String> roles = JwtRoleExtractor.extractRoles(null, resourceAccess);
        assertTrue(roles.contains("admin"));
        assertTrue(roles.contains("manage-account"));
    }

    @Test
    @DisplayName("realm_access と resource_access を合算して返す")
    void extractRoles_bothSources_returnsCombined() {
        Map<String, Object> realmAccess = Map.of("roles", List.of("user"));
        Map<String, Object> resourceAccess = Map.of(
            "k8s-pups", Map.of("roles", List.of("knowledge-editor")));
        List<String> roles = JwtRoleExtractor.extractRoles(realmAccess, resourceAccess);
        assertTrue(roles.contains("user"));
        assertTrue(roles.contains("knowledge-editor"));
    }

    @Test
    @DisplayName("両方のソースに同じロールがあっても重複しない")
    void extractRoles_duplicateAcrossSources_deduplicates() {
        Map<String, Object> realmAccess = Map.of("roles", List.of("user", "knowledge-editor"));
        Map<String, Object> resourceAccess = Map.of(
            "k8s-pups", Map.of("roles", List.of("knowledge-editor")));
        List<String> roles = JwtRoleExtractor.extractRoles(realmAccess, resourceAccess);
        assertEquals(1, roles.stream().filter(r -> r.equals("knowledge-editor")).count(),
            "knowledge-editor must appear exactly once");
    }

    @Test
    @DisplayName("realmAccess が null のとき空リストを返す")
    void extractRoles_bothNull_returnsEmpty() {
        List<String> roles = JwtRoleExtractor.extractRoles(null, null);
        assertTrue(roles.isEmpty());
    }

    @Test
    @DisplayName("realm_access に roles キーがないとき空リストを返す")
    void extractRoles_realmAccessWithoutRolesKey_returnsEmpty() {
        Map<String, Object> realmAccess = Map.of("other", "value");
        List<String> roles = JwtRoleExtractor.extractRoles(realmAccess, null);
        assertTrue(roles.isEmpty());
    }
}
