package com.scivicslab.k8spups.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Filters tool registry entries by user roles.
 * Pure logic — no CDI, no k8s dependency.
 */
public class ToolRoleFilter {

    private ToolRoleFilter() {}

    /**
     * Return tools visible to any of the given roles, filtered by an enablement check.
     *
     * @param registry    the tool registry to filter
     * @param roles       user roles (union semantics)
     * @param isEnabled   predicate that returns true for enabled tool names
     * @return deduplicated list of visible, enabled ToolRegistryEntry instances
     */
    public static List<ToolRegistryEntry> filterForRoles(
            ToolRegistry registry,
            List<String> roles,
            Predicate<String> isEnabled) {

        if (roles == null || roles.isEmpty()) {
            return List.of();
        }

        List<ToolRegistryEntry> result = new ArrayList<>();
        for (String role : roles) {
            for (ToolRegistryEntry entry : registry.getToolsByRole(role)) {
                boolean alreadyAdded = result.stream()
                    .anyMatch(e -> e.name().equals(entry.name()));
                if (!alreadyAdded && isEnabled.test(entry.name())) {
                    result.add(entry);
                }
            }
        }
        return result;
    }
}
