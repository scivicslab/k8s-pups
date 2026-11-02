package com.scivicslab.k8spups.usage;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Keeps the records of how long the Pods of the users ran (table {@code pod_usage}), which the billing of the
 * controller is computed from. A plain POJO; the controller runs one instance as the actor {@code pod-usage}. The
 * sessions tell it what happened to their Pods; an actor handles its messages in the order they arrive, so the
 * creation, the ready time and the end of one session are written in that order.
 *
 * <p>A failure to write a record does not reach the session: a database that is down must not stop a user from
 * working, and an exception in an actor's message is not reported to the sender anyway. The write is kept and done
 * again, in the original order, at the next message and at the next minute tick, until the database takes it. Every
 * write can be done twice without harm (the repository ignores what it already has), so a write whose answer was lost
 * is safe to repeat. The writes that wait are kept in memory only: when the controller stops they are gone, and the
 * rows of the Pods that still run are made again from the Pods when the next controller takes them over.
 */
public class PodUsageService {

    /** Name the controller registers this service under in the actor system. */
    public static final String ACTOR_NAME = "pod-usage";

    /** End reason of a session whose Pod was gone when the controller started. */
    public static final String REASON_CONTROLLER_LOST = "the Pod was gone when the controller started";

    /** How many failed writes are kept for another try; the oldest are given up beyond this. */
    static final int DEFAULT_MAX_WAITING = 10_000;

    private static final Logger LOG = Logger.getLogger(PodUsageService.class.getName());

    private record Waiting(String what, Runnable write) {}

    private final PodUsageRepository repository;
    private final int maxWaiting;
    private final Deque<Waiting> waiting = new ArrayDeque<>();

    public PodUsageService(PodUsageRepository repository) {
        this(repository, DEFAULT_MAX_WAITING);
    }

    PodUsageService(PodUsageRepository repository, int maxWaiting) {
        this.repository = repository;
        this.maxWaiting = maxWaiting;
    }

    /** A Pod was created for a session. */
    public void created(PodUsageRepository.Start start) {
        record("the creation of session " + start.sessionId() + " (user " + start.user() + ")",
            () -> repository.open(start));
    }

    /** A session of a user became ready: billing of its Pod starts at {@code at}. */
    public void ready(String sessionId, Instant at) {
        record("the ready time of session " + sessionId, () -> repository.ready(sessionId, at));
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
        record("the end of session " + sessionId, () -> repository.end(sessionId, at, reason));
    }

    /**
     * The controller saw these sessions alive just now. Called every minute with every session it has; it also tries
     * again the writes that failed.
     */
    public void seen(Set<String> sessionIds) {
        flush();
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
        record("the sessions that ended while no controller was watching", () -> {
            int n = repository.endOpenExcept(takenOverSessionIds, REASON_CONTROLLER_LOST);
            if (n > 0) LOG.warning("Pod usage: " + n + " session(s) ended while no controller was watching");
        });
    }

    /** Puts a write behind the writes that wait and tries them all. */
    private void record(String what, Runnable write) {
        waiting.addLast(new Waiting(what, write));
        while (waiting.size() > maxWaiting) {
            Waiting given = waiting.removeFirst();
            LOG.severe("Pod usage: " + given.what() + " is given up, because " + maxWaiting
                + " writes wait for the database");
        }
        flush();
    }

    /** Does the waiting writes in order; stops at the first one the database refuses, which stays first. */
    private void flush() {
        while (!waiting.isEmpty()) {
            Waiting next = waiting.peekFirst();
            try {
                next.write().run();
            } catch (RuntimeException e) {
                LOG.severe("Pod usage: " + next.what() + " was not recorded and will be tried again ("
                    + waiting.size() + " write(s) wait): " + e.getMessage());
                return;
            }
            waiting.removeFirst();
        }
    }
}
