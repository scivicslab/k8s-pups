package com.scivicslab.k8spups.actor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SessionSummary record.
 */
class SessionSummaryTest {

    @Test
    @DisplayName("SessionSummary stores all counters correctly")
    void storesAllCounters() {
        SessionSummary summary = new SessionSummary(10, 7, 2, 1);
        assertEquals(10, summary.total());
        assertEquals(7, summary.ready());
        assertEquals(2, summary.starting());
        assertEquals(1, summary.failed());
    }

    @Test
    @DisplayName("SessionSummary zero state is valid")
    void zeroStateIsValid() {
        SessionSummary empty = new SessionSummary(0, 0, 0, 0);
        assertEquals(0, empty.total());
        assertEquals(0, empty.ready());
        assertEquals(0, empty.starting());
        assertEquals(0, empty.failed());
    }

    @Test
    @DisplayName("Two SessionSummary records with same values are equal")
    void recordEqualityHolds() {
        SessionSummary a = new SessionSummary(5, 3, 1, 1);
        SessionSummary b = new SessionSummary(5, 3, 1, 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
