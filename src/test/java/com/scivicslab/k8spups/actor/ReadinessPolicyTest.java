package com.scivicslab.k8spups.actor;

import org.junit.jupiter.api.Test;

import static com.scivicslab.k8spups.actor.ReadinessPolicy.Decision.KEEP_WAITING;
import static com.scivicslab.k8spups.actor.ReadinessPolicy.Decision.READY;
import static com.scivicslab.k8spups.actor.ReadinessPolicy.Decision.READY_BILLING_PENDING;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadinessPolicyTest {

    private static final long LIMIT = 60_000;

    @Test
    void aSessionIsReadyAndBilledWhenTheServiceAcceptsAConnection() {
        assertEquals(READY, ReadinessPolicy.decide(true, 0, LIMIT));
        assertEquals(READY, ReadinessPolicy.decide(true, 59_000, LIMIT));
    }

    @Test
    void aSessionKeepsWaitingWhileTheServiceAcceptsNoneWithinTheLimit() {
        assertEquals(KEEP_WAITING, ReadinessPolicy.decide(false, 0, LIMIT));
        assertEquals(KEEP_WAITING, ReadinessPolicy.decide(false, LIMIT, LIMIT));
    }

    @Test
    void aSessionIsOfferedToTheUserButNotBilledWhenTheLimitPassesWithoutAConnection() {
        assertEquals(READY_BILLING_PENDING, ReadinessPolicy.decide(false, LIMIT + 1, LIMIT));
    }

    @Test
    void aConnectionAfterTheLimitStillStartsTheBilling() {
        assertEquals(READY, ReadinessPolicy.decide(true, LIMIT + 5_000, LIMIT));
    }
}
