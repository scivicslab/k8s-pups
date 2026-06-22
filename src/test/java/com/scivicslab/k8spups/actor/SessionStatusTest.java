package com.scivicslab.k8spups.actor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SessionStatus record.
 * Verifies constructors and accessor methods work correctly.
 */
class SessionStatusTest {

    @Test
    @DisplayName("Full constructor stores all fields correctly")
    void fullConstructorStoresAllFields() {
        SessionStatus status = new SessionStatus(
            "sess-001", "alice", "Jupyter Lab", SessionState.READY,
            "pups-jupyter-lab-sess-001", "/session/sess-001/", "my notebook", "longhorn"
        );

        assertEquals("sess-001", status.sessionId());
        assertEquals("alice", status.userId());
        assertEquals("Jupyter Lab", status.toolName());
        assertEquals(SessionState.READY, status.state());
        assertEquals("pups-jupyter-lab-sess-001", status.podName());
        assertEquals("/session/sess-001/", status.accessUrl());
        assertEquals("my notebook", status.memo());
        assertEquals("longhorn", status.storageType());
    }

    @Test
    @DisplayName("Backward-compatible constructor defaults storageType to null")
    void backwardCompatConstructorDefaultsStorageTypeToNull() {
        SessionStatus status = new SessionStatus(
            "sess-002", "bob", "Chat UI", SessionState.STARTING,
            "pups-chat-ui-sess-002", null, ""
        );

        assertNull(status.storageType());
        assertEquals("sess-002", status.sessionId());
        assertEquals("bob", status.userId());
        assertEquals(SessionState.STARTING, status.state());
    }

    @Test
    @DisplayName("SessionStatus with null accessUrl and empty memo is valid")
    void nullAccessUrlAndEmptyMemoAreValid() {
        SessionStatus status = new SessionStatus(
            "sess-003", "carol", "AI Toolkit", SessionState.FAILED,
            "pups-ai-toolkit-sess-003", null, "", "nfs-k8s"
        );

        assertNull(status.accessUrl());
        assertEquals("", status.memo());
        assertEquals(SessionState.FAILED, status.state());
    }

    @Test
    @DisplayName("Two SessionStatus records with same values are equal")
    void recordEqualityHoldsForSameValues() {
        SessionStatus a = new SessionStatus(
            "s1", "user1", "Tool", SessionState.READY,
            "pod-1", "/session/s1/", "memo", "longhorn"
        );
        SessionStatus b = new SessionStatus(
            "s1", "user1", "Tool", SessionState.READY,
            "pod-1", "/session/s1/", "memo", "longhorn"
        );

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
