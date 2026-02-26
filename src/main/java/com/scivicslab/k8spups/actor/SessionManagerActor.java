package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.k8s.SessionInfo;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.*;
import java.util.logging.Logger;

/**
 * Singleton actor that manages all user sessions.
 * Driven by POJO-actor: SessionManagerActor is a plain POJO,
 * wrapped in ActorRef by K8sPupsActorSystem.
 */
public class SessionManagerActor {

    private static final Logger LOG = Logger.getLogger(SessionManagerActor.class.getName());

    private final K8sApiClient k8sClient;
    private final Map<String, ToolPlugin> plugins;
    private final int maxSessions;
    private final int maxSessionsPerUser;
    private final long idleTimeoutMinutes;

    /** userId -> child ActorRef<SessionActor> */
    private final Map<String, ActorRef<SessionActor>> sessions = new HashMap<>();

    public SessionManagerActor(K8sApiClient k8sClient, Map<String, ToolPlugin> plugins,
                               int maxSessions, int maxSessionsPerUser, long idleTimeoutMinutes) {
        this.k8sClient = k8sClient;
        this.plugins = plugins;
        this.maxSessions = maxSessions;
        this.maxSessionsPerUser = maxSessionsPerUser;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    /**
     * Create a new session for the user.
     * Returns the SessionStatus, or null if creation was rejected.
     */
    public SessionStatus createSession(ActorRef<SessionManagerActor> self,
                                       String userId, String toolName,
                                       List<String> allowedProjects, String labId,
                                       String resourceProfile) {
        // Check limits
        if (sessions.size() >= maxSessions) {
            LOG.warning("Max sessions reached: " + maxSessions);
            return null;
        }
        // sessions is keyed by userId, so one entry per user.
        // If the user already has a session, reject.
        if (sessions.containsKey(userId)) {
            LOG.warning("User already has an active session: " + userId);
            return null;
        }

        ToolPlugin plugin = plugins.get(toolName);
        if (plugin == null) {
            LOG.warning("Unknown tool: " + toolName);
            return null;
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        SessionInfo info = new SessionInfo(sessionId, userId, plugin, allowedProjects, labId, resourceProfile);
        SessionActor actor = new SessionActor(info, k8sClient);

        // Create as child actor
        ActorRef<SessionActor> childRef = self.createChild("session-" + sessionId, actor);
        sessions.put(userId, childRef);

        // Tell the session to start (async, non-blocking)
        // Pass childRef as the self reference so pod watch events are routed via the actor's message queue.
        childRef.tell(sa -> sa.start(childRef));

        LOG.info("Session created: user=" + userId + ", tool=" + toolName
            + ", session=" + sessionId);

        return new SessionStatus(sessionId, userId, toolName, SessionState.STARTING, info.podName(), null);
    }

    /**
     * Stop and remove a user's session.
     */
    public void destroySession(String userId) {
        ActorRef<SessionActor> ref = sessions.remove(userId);
        if (ref != null) {
            ref.tell(SessionActor::stop);
            ref.close();
            LOG.info("Session destroyed for user: " + userId);
        }
    }

    /**
     * Get the current status of a user's session.
     */
    public SessionStatus getSessionStatus(String userId) {
        ActorRef<SessionActor> ref = sessions.get(userId);
        if (ref == null) {
            return null;
        }
        try {
            return ref.ask(SessionActor::getStatus).get();
        } catch (Exception e) {
            LOG.warning("Failed to get session status for " + userId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Touch a user's session to reset idle timer.
     */
    public void touchSession(String userId) {
        ActorRef<SessionActor> ref = sessions.get(userId);
        if (ref != null) {
            ref.tell(SessionActor::touch);
        }
    }

    /**
     * Check all sessions for idle timeout. Called periodically by Scheduler.
     */
    public void checkIdleSessions() {
        List<String> toRemove = new ArrayList<>();
        for (var entry : sessions.entrySet()) {
            try {
                Boolean idle = entry.getValue()
                    .ask(sa -> sa.checkIdle(idleTimeoutMinutes)).get();
                if (Boolean.TRUE.equals(idle)) {
                    toRemove.add(entry.getKey());
                }
            } catch (Exception e) {
                LOG.warning("Error checking idle for " + entry.getKey() + ": " + e.getMessage());
            }
        }
        for (String userId : toRemove) {
            ActorRef<SessionActor> ref = sessions.remove(userId);
            if (ref != null) {
                ref.close();
            }
        }
    }

    /**
     * Return list of available tool plugins.
     */
    public List<ToolPlugin> listAvailableTools() {
        return List.copyOf(plugins.values());
    }

    /**
     * Check if user has an active session.
     */
    public boolean hasSession(String userId) {
        return sessions.containsKey(userId);
    }

    /**
     * Returns an aggregated summary of all sessions by state.
     */
    public SessionSummary getSessionSummary() {
        int total = sessions.size();
        int ready = 0;
        int starting = 0;
        int failed = 0;
        for (ActorRef<SessionActor> ref : sessions.values()) {
            try {
                SessionState state = ref.ask(SessionActor::getState).get();
                switch (state) {
                    case READY -> ready++;
                    case CREATING, STARTING -> starting++;
                    case FAILED -> failed++;
                    default -> { /* STOPPING, STOPPED */ }
                }
            } catch (Exception e) {
                LOG.warning("Failed to get session state: " + e.getMessage());
            }
        }
        return new SessionSummary(total, ready, starting, failed);
    }

    /**
     * Returns the set of sessionIds currently managed by this actor.
     * Used by startup reconciliation to identify orphaned k8s resources.
     */
    public Set<String> getSessionIds() {
        Set<String> ids = new HashSet<>();
        for (ActorRef<SessionActor> ref : sessions.values()) {
            try {
                String sessionId = ref.ask(SessionActor::getSessionId).get();
                if (sessionId != null) {
                    ids.add(sessionId);
                }
            } catch (Exception e) {
                LOG.warning("Failed to get sessionId from actor: " + e.getMessage());
            }
        }
        return ids;
    }

    /**
     * Destroy all active sessions. Called during graceful shutdown.
     */
    public void destroyAllSessions() {
        LOG.info("Destroying all sessions (" + sessions.size() + ")");
        List<String> userIds = new ArrayList<>(sessions.keySet());
        for (String userId : userIds) {
            ActorRef<SessionActor> ref = sessions.remove(userId);
            if (ref != null) {
                ref.tell(SessionActor::stop);
                ref.close();
            }
        }
    }
}
