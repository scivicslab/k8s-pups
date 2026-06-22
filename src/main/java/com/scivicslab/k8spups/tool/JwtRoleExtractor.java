package com.scivicslab.k8spups.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts user roles from Keycloak JWT claims.
 * Reads both realm_access.roles and resource_access.*.roles.
 * Pure logic — no CDI, no k8s dependency.
 */
public class JwtRoleExtractor {

    private JwtRoleExtractor() {}

    /**
     * Extract all roles from Keycloak JWT claim maps.
     *
     * @param realmAccess value of the "realm_access" JWT claim (may be null)
     * @param resourceAccess value of the "resource_access" JWT claim (may be null)
     * @return deduplicated list of role strings
     */
    public static List<String> extractRoles(
            Map<String, Object> realmAccess,
            Map<String, Object> resourceAccess) {

        List<String> roles = new ArrayList<>();

        if (realmAccess != null) {
            appendRolesFrom(realmAccess, roles);
        }

        if (resourceAccess != null) {
            for (Object clientAccess : resourceAccess.values()) {
                if (clientAccess instanceof Map<?, ?> ca) {
                    appendRolesFrom(ca, roles);
                }
            }
        }

        return roles;
    }

    private static void appendRolesFrom(Map<?, ?> claimMap, List<String> target) {
        Object rolesObj = claimMap.get("roles");
        if (rolesObj instanceof List<?> list) {
            for (Object r : list) {
                if (r instanceof String s && !target.contains(s)) {
                    target.add(s);
                }
            }
        }
    }
}
