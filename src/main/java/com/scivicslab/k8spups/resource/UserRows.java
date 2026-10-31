package com.scivicslab.k8spups.resource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.scivicslab.k8spups.dataset.DatasetRepository;
import com.scivicslab.k8spups.k8s.StorageTypes;
import com.scivicslab.k8spups.security.KeycloakUsers;
import com.scivicslab.k8spups.storage.StorageAllocationRepository;

/**
 * The rows of the user list of the administrator screens. A row joins what Keycloak knows about the account with what
 * the controller's database knows about the user: the last login, the storage, the groups and the datasets.
 */
final class UserRows {

    private UserRows() {}

    /**
     * One row. {@code inKeycloak} is false for a name that only the database knows (a test name, or an account that
     * was deleted from Keycloak). The text items are ready to show.
     */
    record Row(String user, String name, String email, boolean enabled, boolean inKeycloak,
               String signedIn, String storage, String groups, String datasets) {}

    /**
     * @param keycloak    the accounts of the realm, or null when they could not be read
     * @param databaseNames  the user names the database knows
     */
    static List<Row> build(List<KeycloakUsers.User> keycloak, Collection<String> databaseNames, String filter,
                           Map<String, Instant> logins, List<StorageAllocationRepository.Allocation> allocations,
                           Map<String, Map<String, String>> memberships,
                           Map<String, DatasetRepository.GrantCount> grants) {
        Map<String, KeycloakUsers.User> all = new TreeMap<>();
        if (keycloak != null) keycloak.forEach(u -> all.put(u.username(), u));
        for (String n : databaseNames) all.putIfAbsent(n, new KeycloakUsers.User(n, "", "", true));
        for (String n : grants.keySet()) all.putIfAbsent(n, new KeycloakUsers.User(n, "", "", true));
        Map<String, List<String>> storage = new TreeMap<>();
        for (var a : allocations) {
            all.putIfAbsent(a.user(), new KeycloakUsers.User(a.user(), "", "", true));
            storage.computeIfAbsent(a.user(), k -> new ArrayList<>()).add(StorageTypes.name(a.type()) + " " + a.size());
        }
        List<Row> rows = new ArrayList<>();
        for (KeycloakUsers.User u : KeycloakUsers.filter(new ArrayList<>(all.values()), filter)) {
            boolean known = keycloak != null && keycloak.stream().anyMatch(k -> k.username().equals(u.username()));
            rows.add(new Row(u.username(), u.name(), u.email(), u.enabled(), known || keycloak == null,
                    logins.containsKey(u.username()) ? day(logins.get(u.username())) : "never",
                    String.join(", ", storage.getOrDefault(u.username(), List.of())),
                    groups(memberships.getOrDefault(u.username(), Map.of())),
                    datasets(grants.get(u.username()))));
        }
        return rows;
    }

    /** {@code teaching ro, lab rw}. */
    static String groups(Map<String, String> memberships) {
        List<String> parts = new ArrayList<>();
        memberships.forEach((group, mode) -> parts.add(group + " " + mode));
        return String.join(", ", parts);
    }

    /** The number of whole datasets, with the number of single files after it when there are any: {@code 2 +1 files}. */
    static String datasets(DatasetRepository.GrantCount c) {
        if (c == null) return "0";
        return c.files() > 0 ? c.datasets() + " +" + c.files() + " files" : String.valueOf(c.datasets());
    }

    /** The time as {@code 2026-10-31 12:34} (UTC). */
    static String day(Instant at) {
        return at == null ? "never" : at.toString().substring(0, 16).replace('T', ' ');
    }
}
