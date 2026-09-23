package com.scivicslab.k8spups.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Launch / Stop of a shared service is for administrators named by user id or by role. */
class SharedServiceAccessTest {

    @Test
    void adminByUserId() {
        assertTrue(SharedServiceAccess.isAdmin("testadmin", List.of(), "testadmin, oogasawa", "admin"));
        assertFalse(SharedServiceAccess.isAdmin("alice", List.of(), "testadmin, oogasawa", "admin"));
    }

    @Test
    void adminByRole() {
        assertTrue(SharedServiceAccess.isAdmin("alice", List.of("knowledge-editor", "admin"), "", "admin"));
        assertFalse(SharedServiceAccess.isAdmin("alice", List.of("knowledge-editor"), "", "admin"));
        assertFalse(SharedServiceAccess.isAdmin("alice", null, "", "admin"));
    }

    @Test
    void blankConfigurationAdmitsNobody() {
        assertFalse(SharedServiceAccess.isAdmin("testadmin", List.of("admin"), "", ""));
        assertFalse(SharedServiceAccess.isAdmin("testadmin", List.of("admin"), null, null));
    }
}
