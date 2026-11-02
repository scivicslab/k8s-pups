package com.scivicslab.k8spups.tool;

import com.scivicslab.k8spups.plugin.ToolAudience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolAudienceAccessTest {

    @Test
    @DisplayName("A developer is named by user id or by a Keycloak role, the way an administrator is")
    void developerByUserOrRole() {
        assertTrue(ToolAudienceAccess.isDeveloper("alice", List.of(), "alice, bob", "developer"));
        assertTrue(ToolAudienceAccess.isDeveloper("carol", List.of("offline_access", "developer"), "alice", "developer"));
        assertFalse(ToolAudienceAccess.isDeveloper("carol", List.of("offline_access"), "alice", "developer"));
        assertFalse(ToolAudienceAccess.isDeveloper("carol", null, "", ""));
    }

    @Test
    @DisplayName("A tool for everyone is open to every user")
    void everyone() {
        assertTrue(ToolAudienceAccess.allowed(ToolAudience.EVERYONE, false, false));
    }

    @Test
    @DisplayName("A tool for developers is open to developers and to administrators, and to no one else")
    void developers() {
        assertFalse(ToolAudienceAccess.allowed(ToolAudience.DEVELOPERS, false, false));
        assertTrue(ToolAudienceAccess.allowed(ToolAudience.DEVELOPERS, false, true));
        assertTrue(ToolAudienceAccess.allowed(ToolAudience.DEVELOPERS, true, false));
    }

    @Test
    @DisplayName("A tool for administrators is not open to a developer who is not an administrator")
    void administrators() {
        assertFalse(ToolAudienceAccess.allowed(ToolAudience.ADMINISTRATORS, false, false));
        assertFalse(ToolAudienceAccess.allowed(ToolAudience.ADMINISTRATORS, false, true));
        assertTrue(ToolAudienceAccess.allowed(ToolAudience.ADMINISTRATORS, true, false));
    }

    @Test
    @DisplayName("A deployment can keep a tool for developers, and can never open a tool that the tool itself restricts")
    void effective() {
        assertEquals(ToolAudience.DEVELOPERS,
            ToolAudienceAccess.effective(ToolAudience.EVERYONE, "jupyter-lab", Set.of("jupyter-lab")));
        assertEquals(ToolAudience.EVERYONE,
            ToolAudienceAccess.effective(ToolAudience.EVERYONE, "jupyter-lab", Set.of("html-saurus")));
        assertEquals(ToolAudience.DEVELOPERS,
            ToolAudienceAccess.effective(ToolAudience.DEVELOPERS, "html-saurus", Set.of()));
        assertEquals(ToolAudience.ADMINISTRATORS,
            ToolAudienceAccess.effective(ToolAudience.ADMINISTRATORS, "tools-admin", Set.of("tools-admin")));
    }
}
