package com.scivicslab.k8spups.actor;

import java.time.Duration;
import java.time.Instant;

/** When the S3 credentials of a session are renewed. Pure logic. */
public final class S3Renewal {

    private S3Renewal() {
    }

    /**
     * @param expiration when the credentials last written expire; null when not known (the controller restarted)
     * @return whether to renew now: they are unknown, or expire within {@code before}
     */
    public static boolean due(Instant expiration, Instant now, Duration before) {
        return expiration == null || !now.plus(before).isBefore(expiration);
    }
}
