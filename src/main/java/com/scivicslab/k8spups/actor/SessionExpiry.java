package com.scivicslab.k8spups.actor;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether a session has outlived one of its limits ({@link SessionTimeouts}), and which. Pure logic: the session
 * passes in its state and times.
 */
public final class SessionExpiry {

    private SessionExpiry() {
    }

    /**
     * @param failedSince when the session was first seen failed; null when it is not failed
     * @return the reason to stop the session, as written to the log and the audit record; null to keep it
     */
    public static String reasonToStop(SessionState state, Instant created, Instant lastAccess, Instant failedSince,
                                      Instant now, SessionTimeouts limits) {
        switch (state) {
            case READY -> {
                long alive = Duration.between(created, now).toMinutes();
                if (limits.lifetimeMinutes() > 0 && alive >= limits.lifetimeMinutes()) {
                    return "lifetime limit reached (" + alive + " min, limit " + limits.lifetimeMinutes() + " min)";
                }
                long idle = Duration.between(lastAccess, now).toMinutes();
                if (limits.idleMinutes() > 0 && idle >= limits.idleMinutes()) {
                    return "idle for " + idle + " min (limit " + limits.idleMinutes() + " min)";
                }
                return null;
            }
            case CREATING, STARTING -> {
                long starting = Duration.between(created, now).toMinutes();
                if (limits.startMinutes() > 0 && starting >= limits.startMinutes()) {
                    return "did not become ready in " + starting + " min (limit " + limits.startMinutes() + " min)";
                }
                return null;
            }
            case FAILED -> {
                if (failedSince == null) return null;
                long failed = Duration.between(failedSince, now).toMinutes();
                if (limits.failedMinutes() > 0 && failed >= limits.failedMinutes()) {
                    return "failed " + failed + " min ago (kept " + limits.failedMinutes() + " min)";
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }
}
