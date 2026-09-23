package com.scivicslab.k8spups.tool;

import java.util.Collection;
import java.util.List;

/**
 * Who may Launch or Stop a shared service. Everyone who is logged in may see its state and open it;
 * scaling it is for administrators, named either by user id ({@code k8spups.admin-users}) or by a
 * Keycloak role ({@code k8spups.admin-roles}). Pure logic, so the rule is unit-tested without a
 * token or a cluster. The check is applied at the endpoint, not only in the page: hiding a button
 * does not stop a request.
 */
public final class SharedServiceAccess {

    private SharedServiceAccess() {
    }

    /**
     * True when {@code userId} is one of {@code adminUsers} or {@code roles} contains one of
     * {@code adminRoles}. Both lists are comma-separated; blanks are ignored.
     */
    public static boolean isAdmin(String userId, Collection<String> roles, String adminUsers, String adminRoles) {
        for (String u : split(adminUsers)) {
            if (u.equals(userId)) {
                return true;
            }
        }
        if (roles != null) {
            for (String r : split(adminRoles)) {
                if (roles.contains(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
