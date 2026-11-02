package com.scivicslab.k8spups.actor;

/**
 * Decides when a session is offered to the user and when the billing of its Pod starts.
 *
 * <p>The billing starts when the Service of the Pod accepts a connection, because that is when the user can use the
 * tool. A tool whose port cannot be probed must not hold the user back for ever, so the session is offered to the user
 * when the limit passes; but then nothing is billed until a connection is accepted.
 */
final class ReadinessPolicy {

    private ReadinessPolicy() {}

    enum Decision {
        /** The Service accepts no connection yet and the limit has not passed. */
        KEEP_WAITING,
        /** The Service accepted a connection: the session is ready and the billing starts. */
        READY,
        /** The limit passed without a connection: the session is offered to the user, the billing is still pending. */
        READY_BILLING_PENDING
    }

    static Decision decide(boolean reachable, long waitedMillis, long limitMillis) {
        if (reachable) return Decision.READY;
        return waitedMillis > limitMillis ? Decision.READY_BILLING_PENDING : Decision.KEEP_WAITING;
    }
}
