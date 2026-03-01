package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.k8s.LdapUserInfoClient;
import com.scivicslab.k8spups.k8s.SessionInfo;
import com.scivicslab.k8spups.k8s.WorkspaceInfo;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.*;
import java.util.logging.Logger;

/**
 * Singleton actor that manages all user sessions.
 * Supports multiple sessions per user (up to maxSessionsPerUser).
 * A value of -1 for maxSessionsPerUser means unlimited.
 */
public class SessionManagerActor {

    private static final Logger LOG = Logger.getLogger(SessionManagerActor.class.getName());

    private final K8sApiClient k8sClient;
    private final Map<String, ToolPlugin> plugins;
    private final int maxSessions;
    private final int maxSessionsPerUser;
    private final long idleTimeoutMinutes;
    private final long maxLifetimeMinutes;
    private final Set<String> unlimitedUsers;
    private final LdapUserInfoClient ldapClient;

    /** sessionId -> child ActorRef<SessionActor> */
    private final Map<String, ActorRef<SessionActor>> sessions = new HashMap<>();

    /** userId -> list of sessionIds owned by that user */
    private final Map<String, List<String>> userSessions = new HashMap<>();

    public SessionManagerActor(K8sApiClient k8sClient, Map<String, ToolPlugin> plugins,
                               int maxSessions, int maxSessionsPerUser, long idleTimeoutMinutes,
                               long maxLifetimeMinutes,
                               Set<String> unlimitedUsers, LdapUserInfoClient ldapClient) {
        this.k8sClient = k8sClient;
        this.plugins = plugins;
        this.maxSessions = maxSessions;
        this.maxSessionsPerUser = maxSessionsPerUser;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
        this.maxLifetimeMinutes = maxLifetimeMinutes;
        this.unlimitedUsers = unlimitedUsers;
        this.ldapClient = ldapClient;
    }

    /**
     * Create a new session for the user.
     * Returns the SessionStatus, or null if creation was rejected.
     */
    public SessionStatus createSession(ActorRef<SessionManagerActor> self,
                                       String userId, String toolName,
                                       List<String> allowedProjects, String labId,
                                       String resourceProfile,
                                       Map<String, String> userParams) {
        // Check global limit
        if (sessions.size() >= maxSessions) {
            LOG.warning("Max sessions reached: " + maxSessions);
            return null;
        }
        // Check per-user limit (-1 means unlimited, unlimitedUsers bypass the check)
        int userCount = userSessions.getOrDefault(userId, List.of()).size();
        if (!unlimitedUsers.contains(userId) && maxSessionsPerUser >= 0 && userCount >= maxSessionsPerUser) {
            LOG.warning("User " + userId + " reached max sessions: " + maxSessionsPerUser);
            return null;
        }

        ToolPlugin plugin = plugins.get(toolName);
        if (plugin == null) {
            LOG.warning("Unknown tool: " + toolName);
            return null;
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        // Look up user's storage preference from ConfigMap
        String storagePref = k8sClient.getUserStoragePreference(userId);

        // Look up POSIX account for workspace mounting (silently skip if not found)
        WorkspaceInfo workspaceInfo = null;
        if (plugin.workspaceEnabled() && ldapClient != null) {
            workspaceInfo = ldapClient.lookup(userId).orElse(null);
        }

        SessionInfo info = new SessionInfo(sessionId, userId, plugin, allowedProjects, labId, resourceProfile,
            userParams != null ? userParams : Collections.emptyMap(), storagePref, workspaceInfo);
        SessionActor actor = new SessionActor(info, k8sClient);

        // Create as child actor
        ActorRef<SessionActor> childRef = self.createChild("session-" + sessionId, actor);
        sessions.put(sessionId, childRef);
        userSessions.computeIfAbsent(userId, k -> new ArrayList<>()).add(sessionId);

        // Auto-populate memo from the first non-secret user parameter (e.g. project path)
        String autoMemo = "";
        if (userParams != null && plugin.userParameters() != null) {
            for (var p : plugin.userParameters()) {
                if (!p.secret()) {
                    String val = userParams.get(p.envVarName());
                    if (val != null && !val.isBlank()) {
                        autoMemo = val;
                        break;
                    }
                }
            }
        }
        if (!autoMemo.isEmpty()) {
            String m = autoMemo;
            childRef.tell(sa -> sa.setMemo(m));
        }

        // Tell the session to start (async, non-blocking)
        childRef.tell(sa -> sa.start(childRef));

        LOG.info("Session created: user=" + userId + ", tool=" + toolName
            + ", session=" + sessionId + " (user total: " + (userCount + 1) + ")");

        return new SessionStatus(sessionId, userId, plugin.displayName(), SessionState.STARTING, info.podName(), null, autoMemo);
    }

    /**
     * Stop and remove a session by sessionId.
     */
    public void destroySession(String sessionId) {
        ActorRef<SessionActor> ref = sessions.remove(sessionId);
        if (ref != null) {
            // Remove from userSessions index
            for (var entry : userSessions.entrySet()) {
                if (entry.getValue().remove(sessionId)) {
                    if (entry.getValue().isEmpty()) {
                        userSessions.remove(entry.getKey());
                    }
                    break;
                }
            }
            ref.tell(SessionActor::stop);
            ref.close();
            LOG.info("Session destroyed: " + sessionId);
        }
    }

    /**
     * Get the status of all sessions belonging to a user.
     */
    public List<SessionStatus> getUserSessions(String userId) {
        List<String> sessionIds = userSessions.getOrDefault(userId, List.of());
        List<SessionStatus> result = new ArrayList<>();
        for (String sessionId : sessionIds) {
            ActorRef<SessionActor> ref = sessions.get(sessionId);
            if (ref != null) {
                try {
                    SessionStatus status = ref.ask(SessionActor::getStatus).get();
                    if (status != null) {
                        result.add(status);
                    }
                } catch (Exception e) {
                    LOG.warning("Failed to get session status for " + sessionId + ": " + e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * Update the memo text for a session.
     */
    public void updateMemo(String sessionId, String memo) {
        ActorRef<SessionActor> ref = sessions.get(sessionId);
        if (ref != null) {
            ref.tell(sa -> sa.setMemo(memo));
        }
    }

    /**
     * Touch a session to reset idle timer.
     */
    public void touchSession(String sessionId) {
        ActorRef<SessionActor> ref = sessions.get(sessionId);
        if (ref != null) {
            ref.tell(SessionActor::touch);
        }
    }

    /**
     * Touch all sessions belonging to a user.
     */
    public void touchUserSessions(String userId) {
        List<String> sessionIds = userSessions.getOrDefault(userId, List.of());
        for (String sessionId : sessionIds) {
            ActorRef<SessionActor> ref = sessions.get(sessionId);
            if (ref != null) {
                ref.tell(SessionActor::touch);
            }
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
                    .ask(sa -> sa.checkIdle(idleTimeoutMinutes, maxLifetimeMinutes)).get();
                if (Boolean.TRUE.equals(idle)) {
                    toRemove.add(entry.getKey());
                }
            } catch (Exception e) {
                LOG.warning("Error checking idle for " + entry.getKey() + ": " + e.getMessage());
            }
        }
        for (String sessionId : toRemove) {
            destroySession(sessionId);
        }
    }

    /**
     * Return list of available tool plugins.
     */
    public List<ToolPlugin> listAvailableTools() {
        return List.copyOf(plugins.values());
    }

    /**
     * Check if user has any active session.
     */
    public boolean hasSession(String userId) {
        return userSessions.containsKey(userId) && !userSessions.get(userId).isEmpty();
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
        return new HashSet<>(sessions.keySet());
    }

    /** Returns information about a user's PVC. */
    public Map<String, String> getUserPvcInfo(String userId) {
        return k8sClient.getUserPvcInfo(userId);
    }

    /** Returns the user's storage size preference, or null if not set. */
    public String getUserStoragePreference(String userId) {
        return k8sClient.getUserStoragePreference(userId);
    }

    /** Saves the user's storage size preference. */
    public void saveUserStoragePreference(String userId, String storageSize) {
        k8sClient.saveUserStoragePreference(userId, storageSize);
    }

    /**
     * Expands the user's PVC to the given size if it exists and is smaller.
     * Creates the PVC if it does not exist.
     */
    public void expandUserPvc(String userId, String storageSize) {
        k8sClient.createUserPvcIfAbsent(userId, storageSize);
    }

    /**
     * Destroy all active sessions. Called during graceful shutdown.
     */
    public void destroyAllSessions() {
        LOG.info("Destroying all sessions (" + sessions.size() + ")");
        List<String> sessionIds = new ArrayList<>(sessions.keySet());
        for (String sessionId : sessionIds) {
            ActorRef<SessionActor> ref = sessions.remove(sessionId);
            if (ref != null) {
                ref.tell(SessionActor::stop);
                ref.close();
            }
        }
        userSessions.clear();
    }
}
