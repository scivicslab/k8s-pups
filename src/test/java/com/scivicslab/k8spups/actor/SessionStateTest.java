package com.scivicslab.k8spups.actor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SessionState enum.
 * Verifies all expected states exist and no accidental renames happened.
 */
class SessionStateTest {

    @Test
    @DisplayName("SessionState contains all expected lifecycle states")
    void allStatesPresent() {
        SessionState[] states = SessionState.values();
        assertEquals(6, states.length,
            "Expected 6 states: CREATING, STARTING, READY, STOPPING, STOPPED, FAILED");
    }

    @Test
    @DisplayName("SessionState.valueOf parses each state name correctly")
    void valueOfRoundTrip() {
        for (SessionState state : SessionState.values()) {
            assertSame(state, SessionState.valueOf(state.name()));
        }
    }

    @Test
    @DisplayName("All expected state names are present")
    void expectedNamesPresent() {
        assertDoesNotThrow(() -> SessionState.valueOf("CREATING"));
        assertDoesNotThrow(() -> SessionState.valueOf("STARTING"));
        assertDoesNotThrow(() -> SessionState.valueOf("READY"));
        assertDoesNotThrow(() -> SessionState.valueOf("STOPPING"));
        assertDoesNotThrow(() -> SessionState.valueOf("STOPPED"));
        assertDoesNotThrow(() -> SessionState.valueOf("FAILED"));
    }
}
