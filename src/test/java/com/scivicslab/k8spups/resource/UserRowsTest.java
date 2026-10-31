package com.scivicslab.k8spups.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.scivicslab.k8spups.dataset.DatasetRepository.GrantCount;
import com.scivicslab.k8spups.security.KeycloakUsers.User;
import com.scivicslab.k8spups.storage.StorageAllocationRepository.Allocation;

class UserRowsTest {

    static final List<User> KEYCLOAK = List.of(
            new User("e2eadmin", "E2E Admin", "e2eadmin@nig.ac.jp", true),
            new User("newcomer", "New Comer", "newcomer@nig.ac.jp", true),
            new User("testuser1", "Test One", "testuser1@nig.ac.jp", true));

    static Allocation posix(String user, String size) {
        return new Allocation(1, user, "posix", size, "active", "e2eadmin", Instant.parse("2026-10-30T01:02:03Z"),
                null, null, null, Optional.empty());
    }

    @Test
    void aNewUserWithNoRecordIsListedWithEmptyItems() {
        var rows = UserRows.build(KEYCLOAK, Set.of(), "", Map.of(), List.of(), Map.of(), Map.of());
        assertEquals(List.of("e2eadmin", "newcomer", "testuser1"), rows.stream().map(UserRows.Row::user).toList());
        UserRows.Row r = rows.get(1);
        assertEquals("never", r.signedIn());
        assertEquals("", r.storage());
        assertEquals("", r.groups());
        assertEquals("0", r.datasets());
        assertTrue(r.inKeycloak());
    }

    @Test
    void joinsLoginStorageGroupsAndDatasets() {
        var rows = UserRows.build(KEYCLOAK, Set.of("testuser1"), "testuser1",
                Map.of("testuser1", Instant.parse("2026-10-31T12:34:56Z")),
                List.of(posix("testuser1", "10Gi")),
                Map.of("testuser1", Map.of("teaching", "ro")),
                Map.of("testuser1", new GrantCount(2, 1)));
        assertEquals(1, rows.size());
        UserRows.Row r = rows.get(0);
        assertEquals("2026-10-31 12:34", r.signedIn());
        assertEquals("Shared file storage 10Gi", r.storage());
        assertEquals("teaching ro", r.groups());
        assertEquals("2 +1 files", r.datasets());
    }

    @Test
    void aNameOnlyTheDatabaseKnowsIsMarkedAsNotInKeycloak() {
        var rows = UserRows.build(KEYCLOAK, Set.of("e2e-other-user"), "", Map.of(), List.of(), Map.of(),
                Map.of("e2e-other-user", new GrantCount(1, 0)));
        UserRows.Row other = rows.stream().filter(r -> r.user().equals("e2e-other-user")).findFirst().orElseThrow();
        assertFalse(other.inKeycloak());
        assertEquals("1", other.datasets());
    }

    @Test
    void whenKeycloakCannotBeReadOnlyTheDatabaseNamesAreListedAndNoneIsMarked() {
        var rows = UserRows.build(null, Set.of("testuser1", "e2eadmin"), "", Map.of(), List.of(), Map.of(), Map.of());
        assertEquals(List.of("e2eadmin", "testuser1"), rows.stream().map(UserRows.Row::user).toList());
        assertTrue(rows.stream().allMatch(UserRows.Row::inKeycloak));
    }

    @Test
    void theFilterMatchesTheMailAddressToo() {
        var rows = UserRows.build(KEYCLOAK, Set.of(), "newcomer@", Map.of(), List.of(), Map.of(), Map.of());
        assertEquals(List.of("newcomer"), rows.stream().map(UserRows.Row::user).toList());
    }
}
