package com.scivicslab.k8spups.actor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class SessionExpiryTest {

    static final SessionTimeouts LIMITS = new SessionTimeouts(1440, 10080, 15, 10);
    static final Instant T0 = Instant.parse("2026-11-06T00:00:00Z");

    static Instant plus(long minutes) {
        return T0.plusSeconds(minutes * 60);
    }

    @Test
    void aSessionThatDoesNotBecomeReadyIsStoppedAfterTheStartLimit() {
        for (SessionState s : new SessionState[] {SessionState.CREATING, SessionState.STARTING}) {
            assertNull(SessionExpiry.reasonToStop(s, T0, T0, null, plus(14), LIMITS));
            String reason = SessionExpiry.reasonToStop(s, T0, T0, null, plus(15), LIMITS);
            assertNotNull(reason);
            assertTrue(reason.startsWith("did not become ready"), reason);
        }
    }

    @Test
    void aFailedSessionIsKeptForTheUserToSeeThenStopped() {
        assertNull(SessionExpiry.reasonToStop(SessionState.FAILED, T0, T0, plus(30), plus(39), LIMITS));
        String reason = SessionExpiry.reasonToStop(SessionState.FAILED, T0, T0, plus(30), plus(40), LIMITS);
        assertNotNull(reason);
        assertTrue(reason.startsWith("failed"), reason);
        // Not yet seen failed by the periodic check: nothing to measure from
        assertNull(SessionExpiry.reasonToStop(SessionState.FAILED, T0, T0, null, plus(1000), LIMITS));
    }

    @Test
    void aReadySessionIsStoppedWhenIdleOrPastItsLifetime() {
        assertNull(SessionExpiry.reasonToStop(SessionState.READY, T0, plus(100), null, plus(100 + 1439), LIMITS));
        assertTrue(SessionExpiry.reasonToStop(SessionState.READY, T0, plus(100), null, plus(100 + 1440), LIMITS).startsWith("idle"));
        assertTrue(SessionExpiry.reasonToStop(SessionState.READY, T0, plus(10079), null, plus(10080), LIMITS).startsWith("lifetime"));
        // A ready session is never stopped for the time it took to start
        assertNull(SessionExpiry.reasonToStop(SessionState.READY, T0, plus(59), null, plus(60), LIMITS));
    }

    @Test
    void aLimitOfZeroIsOff() {
        SessionTimeouts off = new SessionTimeouts(0, 0, 0, 0);
        for (SessionState s : SessionState.values()) {
            assertNull(SessionExpiry.reasonToStop(s, T0, T0, T0, plus(1_000_000), off), s.name());
        }
    }
}
