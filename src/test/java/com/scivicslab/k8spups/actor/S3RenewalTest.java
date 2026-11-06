package com.scivicslab.k8spups.actor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class S3RenewalTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final Duration BEFORE = Duration.ofMinutes(6);

    @Test
    void credentialsNotKnownAreRenewedAtOnce() {
        assertTrue(S3Renewal.due(null, NOW, BEFORE));
    }

    @Test
    void credentialsAreRenewedWhenTheyExpireWithinTheMargin() {
        assertTrue(S3Renewal.due(NOW.plus(Duration.ofMinutes(5)), NOW, BEFORE));
        assertTrue(S3Renewal.due(NOW.plus(BEFORE), NOW, BEFORE));
        assertTrue(S3Renewal.due(NOW.minusSeconds(1), NOW, BEFORE));
    }

    @Test
    void credentialsWithTimeLeftAreKept() {
        assertFalse(S3Renewal.due(NOW.plus(Duration.ofMinutes(7)), NOW, BEFORE));
    }
}
