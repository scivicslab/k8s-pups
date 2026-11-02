package com.scivicslab.k8spups.usage;

import java.time.Instant;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Keeps the records of how long the Pods of the users ran (table {@code pod_usage}), which the billing of the
 * controller is computed from. A plain POJO; the controller runs one instance as the actor {@code pod-usage}. The
 * sessions tell it what happened to their Pods; an actor handles its messages in the order they arrive, so the
 * creation, the ready time and the end of one session are written in that order.
 *
 * <p>A failure to write a record is logged at SEVERE and does not reach the session: a database that is down must not
 * stop a user from working, and an exception in an actor's message is not reported to the sender anyway.
 */
public class PodUsageService {

    /** Name the controller registers this service under in the actor system. */
    public static final String ACTOR_NAME = "pod-usage";

    /** End reason of a session whose Pod was gone when the controller started. */
    public static final String REASON_CONTROLLER_LOST = "the Pod was gone when the controller started";

    private static final Logger LOG = Logger.getLogger(PodUsageService.class.getName());

    private final PodUsageRepository repository;

    public PodUsageService(PodUsageRepository repository) {
        this.repository = repository;
    }

    /** A Pod was created for a session. */
    public void created(PodUsageRepository.Start start) {
        try {
            repository.open(start);
        } catch (RuntimeException e) {
            LOG.severe("Pod usage: the creation of session " + start.sessionId() + " (user " + start.user()
                + ") was not recorded: " + e.getMessage());
        }
    }

    /** A session of a user became ready: billing of its Pod starts at {@code at}. */
    public void ready(String sessionId, Instant at) {
        try {
            repository.ready(sessionId, at);
        } catch (RuntimeException e) {
            LOG.severe("Pod usage: the ready time of session " + sessionId + " was not recorded: " + e.getMessage());
        }
    }

    /**
     * The controller took over a Pod that was already running (it was restarted). The row exists from before; a Pod
     * that was created before records existed gets its row now, with the time Kubernetes created the Pod.
     *
     * @param readyAt when the Pod became Ready; null when it is not Ready yet
     */
    public void attached(PodUsageRepository.Start start, Instant readyAt) {
        created(start);
        if (readyAt != null) ready(start.sessionId(), readyAt);
    }

    /** The Pod of a session is gone (stopped, failed, deleted by someone else): billing of it ends at {@code at}. */
    public void ended(String sessionId, Instant at, String reason) {
        try {
            repository.end(sessionId, at, reason);
        } catch (RuntimeException e) {
            LOG.severe("Pod usage: the end of session " + sessionId + " was not recorded: " + e.getMessage());
        }
    }

    /** The controller saw these sessions alive just now. Called every minute with every session it has. */
    public void seen(Set<String> sessionIds) {
        try {
            repository.seen(sessionIds);
        } catch (RuntimeException e) {
            LOG.severe("Pod usage: the sessions seen alive were not recorded: " + e.getMessage());
        }
    }

    /**
     * Ends every session that was open before this controller started and whose Pod it did not take over, at the last
     * time the previous controller saw it alive.
     */
    public void endOpenExcept(Set<String> takenOverSessionIds) {
        try {
            int n = repository.endOpenExcept(takenOverSessionIds, REASON_CONTROLLER_LOST);
            if (n > 0) LOG.warning("Pod usage: " + n + " session(s) ended while no controller was watching");
        } catch (RuntimeException e) {
            LOG.severe("Pod usage: the sessions that ended while no controller was watching were not recorded: "
                + e.getMessage());
        }
    }
}
