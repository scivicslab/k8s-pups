package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.k8s.LdapUserInfoClient;
import com.scivicslab.k8spups.k8s.MountSpec;
import com.scivicslab.k8spups.k8s.SessionInfo;
import com.scivicslab.k8spups.k8s.WorkspaceInfo;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.k8spups.tool.DynamicToolPlugin;
import com.scivicslab.k8spups.tool.ToolMetadata;
import com.scivicslab.k8spups.tool.ToolRegistryEntry;
import com.scivicslab.k8spups.tool.ToolRoleFilter;
import com.scivicslab.pojoactor.core.ActorRef;
import io.fabric8.kubernetes.api.model.Pod;

import java.util.*;
import java.util.concurrent.CompletableFuture;
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
    private final String controllerNamespace;
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
                               String controllerNamespace,
                               int maxSessions, int maxSessionsPerUser, long idleTimeoutMinutes,
                               long maxLifetimeMinutes,
                               Set<String> unlimitedUsers, LdapUserInfoClient ldapClient) {
        this.k8sClient = k8sClient;
        this.plugins = plugins;
        this.controllerNamespace = controllerNamespace;
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
        return createSession(self, userId, toolName, allowedProjects, labId, resourceProfile, userParams, null);
    }

    /**
     * Create a new session with storage type override (no additional mounts).
     */
    public SessionStatus createSession(ActorRef<SessionManagerActor> self,
                                       String userId, String toolName,
                                       List<String> allowedProjects, String labId,
                                       String resourceProfile,
                                       Map<String, String> userParams,
                                       String overrideStorageType) {
        return createSession(self, userId, toolName, allowedProjects, labId, resourceProfile,
            userParams, overrideStorageType, Collections.emptyList());
    }

    /**
     * Create a new session with optional storage type override and additional mounts.
     * Returns the SessionStatus, or null if creation was rejected.
     */
    public SessionStatus createSession(ActorRef<SessionManagerActor> self,
                                       String userId, String toolName,
                                       List<String> allowedProjects, String labId,
                                       String resourceProfile,
                                       Map<String, String> userParams,
                                       String overrideStorageType,
                                       List<MountSpec> additionalMounts) {
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
        Map<String, String> toolConfigEnv = Collections.emptyMap();
        if (plugin == null) {
            // Try dynamic tool from tool-registry
            ToolRegistryEntry registryEntry = k8sClient.getToolRegistry(controllerNamespace).getTool(toolName);
            if (registryEntry == null) {
                LOG.warning("Unknown tool: " + toolName);
                return null;
            }
            if (!k8sClient.isToolEnabled(controllerNamespace, toolName)) {
                LOG.warning("Tool not enabled: " + toolName);
                return null;
            }
            plugin = DynamicToolPlugin.from(registryEntry);
            toolConfigEnv = k8sClient.getToolConfig(controllerNamespace, toolName);
        }

        // Reject duplicate if plugin is single-instance
        if (plugin.singleInstance()) {
            List<String> userSessionIds = userSessions.getOrDefault(userId, List.of());
            // Fire ask() to all user sessions in parallel
            Map<String, CompletableFuture<String>> toolFutures = new HashMap<>();
            for (String existingId : userSessionIds) {
                ActorRef<SessionActor> existing = sessions.get(existingId);
                if (existing != null) {
                    toolFutures.put(existingId, existing.ask(SessionActor::getToolName));
                }
            }
            for (var entry : toolFutures.entrySet()) {
                try {
                    if (toolName.equals(entry.getValue().get())) {
                        LOG.info("Rejected duplicate " + toolName + " for user " + userId
                            + " (existing session: " + entry.getKey() + ")");
                        return null;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        // Look up user's storage preferences from ConfigMap
        Map<String, String> storageInfo = k8sClient.getUserStorageInfo(userId);
        String storagePref = storageInfo.get("storageSize");
        // Per-session override takes priority, then activeStorageType from ConfigMap, then legacy
        String storageType = (overrideStorageType != null && !overrideStorageType.isBlank())
            ? overrideStorageType
            : storageInfo.getOrDefault("activeStorageType", storageInfo.get("storageType"));

        // Look up POSIX account for workspace mounting (silently skip if not found)
        WorkspaceInfo workspaceInfo = null;
        if (plugin.workspaceEnabled() && ldapClient != null) {
            workspaceInfo = ldapClient.lookup(userId).orElse(null);
        }

        SessionInfo info = new SessionInfo(sessionId, userId, plugin, allowedProjects, labId, resourceProfile,
            userParams != null ? userParams : Collections.emptyMap(), storagePref, storageType, workspaceInfo,
            additionalMounts != null ? additionalMounts : Collections.emptyList(),
            toolConfigEnv);
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
     * Restore a session from an existing Running Pod (used after controller restart).
     * Does NOT create Pod/Service/HTTPRoute — they already exist in k8s.
     */
    public void restoreSession(ActorRef<SessionManagerActor> self,
                                String sessionId, String userId, String toolName, Pod pod) {
        ToolPlugin plugin = plugins.get(toolName);
        if (plugin == null) {
            LOG.warning("Cannot restore session " + sessionId + ": unknown tool '" + toolName + "'");
            return;
        }
        if (sessions.containsKey(sessionId)) {
            LOG.info("Session already known, skipping restore: " + sessionId);
            return;
        }

        // Look up POSIX account for workspace mounting (silently skip if not found)
        WorkspaceInfo workspaceInfo = null;
        if (plugin.workspaceEnabled() && ldapClient != null) {
            workspaceInfo = ldapClient.lookup(userId).orElse(null);
        }

        // Restore storage type from Pod label (set during Pod creation)
        String storageType = null;
        if (pod.getMetadata().getLabels() != null) {
            storageType = pod.getMetadata().getLabels().get("storage-type");
        }
        // Fallback: infer from PVC claim names in Pod volumes (for Pods created before label was added)
        if (storageType == null && pod.getSpec() != null && pod.getSpec().getVolumes() != null) {
            for (var vol : pod.getSpec().getVolumes()) {
                if (vol.getPersistentVolumeClaim() != null) {
                    String claim = vol.getPersistentVolumeClaim().getClaimName();
                    if (claim != null) {
                        if (claim.endsWith("-longhorn")) { storageType = "longhorn"; break; }
                        if (claim.endsWith("-nfs-k8s")) { storageType = "nfs-k8s"; break; }
                        if (claim.startsWith("pups-workspace-")) { storageType = "nfs-home"; break; }
                    }
                }
            }
        }

        SessionInfo info = new SessionInfo(sessionId, userId, plugin, null, null, null,
            Collections.emptyMap(), null, storageType, workspaceInfo);
        SessionActor actor = new SessionActor(info, k8sClient);

        ActorRef<SessionActor> childRef = self.createChild("session-" + sessionId, actor);
        sessions.put(sessionId, childRef);
        userSessions.computeIfAbsent(userId, k -> new ArrayList<>()).add(sessionId);

        // Attach to existing Pod (async)
        childRef.tell(sa -> sa.attachToExisting(childRef, pod));

        LOG.info("Session restored: user=" + userId + ", tool=" + toolName + ", session=" + sessionId);
    }

    /**
     * Stop and remove a session by sessionId.
     * Sends stop() via tell() and waits for completion before closing the actor,
     * ensuring the k8s resource cleanup finishes before the actor thread terminates.
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
            try {
                // Wait for stop() to complete before closing the actor
                ref.tell(SessionActor::stop).get();
            } catch (Exception e) {
                LOG.warning("Error stopping session " + sessionId + ": " + e.getMessage());
            }
            ref.close();
            LOG.info("Session destroyed: " + sessionId);
        }
    }

    /**
     * Get the status of all sessions belonging to a user.
     * Fires ask() to all SessionActors in parallel, then collects results.
     */
    public List<SessionStatus> getUserSessions(String userId) {
        List<String> sessionIds = userSessions.getOrDefault(userId, List.of());
        // Fire all ask() calls in parallel
        List<CompletableFuture<SessionStatus>> futures = new ArrayList<>();
        for (String sessionId : sessionIds) {
            ActorRef<SessionActor> ref = sessions.get(sessionId);
            if (ref != null) {
                futures.add(ref.ask(SessionActor::getStatus));
            }
        }
        // Collect results
        List<SessionStatus> result = new ArrayList<>();
        for (var future : futures) {
            try {
                SessionStatus status = future.get();
                if (status != null) {
                    result.add(status);
                }
            } catch (Exception e) {
                LOG.warning("Failed to get session status: " + e.getMessage());
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

    public String getSessionLogs(String sessionId, int tailLines) {
        ActorRef<SessionActor> ref = sessions.get(sessionId);
        if (ref == null) return "";
        return ref.ask(sa -> sa.getPodLogs(tailLines)).join();
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
     * Fires ask() to all SessionActors in parallel (non-blocking), then collects
     * results. Sessions that should stop are destroyed via destroySession().
     */
    public void checkIdleSessions() {
        // Fire all ask() calls in parallel — each ask is queued in its SessionActor's mailbox
        Map<String, CompletableFuture<Boolean>> futures = new HashMap<>();
        for (var entry : sessions.entrySet()) {
            futures.put(entry.getKey(),
                entry.getValue().ask(sa -> sa.shouldStop(idleTimeoutMinutes, maxLifetimeMinutes)));
        }
        // Collect results
        List<String> toRemove = new ArrayList<>();
        for (var entry : futures.entrySet()) {
            try {
                if (Boolean.TRUE.equals(entry.getValue().get())) {
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
     * Return dynamic tools from tool-registry visible to the given user roles.
     * Only returns enabled tools that match at least one of the provided roles.
     */
    public List<ToolMetadata> listDynamicToolsForRoles(List<String> roles) {
        List<ToolRegistryEntry> entries = ToolRoleFilter.filterForRoles(
            k8sClient.getToolRegistry(controllerNamespace),
            roles,
            name -> k8sClient.isToolEnabled(controllerNamespace, name));

        List<ToolMetadata> result = new ArrayList<>();
        for (ToolRegistryEntry entry : entries) {
            com.scivicslab.k8spups.tool.ToolDescriptor descriptor =
                k8sClient.getToolDescriptor(controllerNamespace, entry.name());
            List<String> configuredParams = new ArrayList<>(
                k8sClient.getToolConfig(controllerNamespace, entry.name()).keySet());
            if (descriptor != null) {
                result.add(ToolMetadata.configured(entry.name(), entry.image(), descriptor, configuredParams));
            } else {
                result.add(ToolMetadata.pulled(entry.name(), entry.image(),
                    new com.scivicslab.k8spups.tool.ToolDescriptor(entry.name(), "", "", List.of())));
            }
        }
        return result;
    }

    /**
     * Check if user has any active session.
     */
    public boolean hasSession(String userId) {
        return userSessions.containsKey(userId) && !userSessions.get(userId).isEmpty();
    }

    /**
     * Returns an aggregated summary of all sessions by state.
     * Fires ask() to all SessionActors in parallel, then aggregates.
     */
    public SessionSummary getSessionSummary() {
        int total = sessions.size();
        // Fire all ask() calls in parallel
        List<CompletableFuture<SessionState>> futures = new ArrayList<>();
        for (ActorRef<SessionActor> ref : sessions.values()) {
            futures.add(ref.ask(SessionActor::getState));
        }
        // Collect results
        int ready = 0;
        int starting = 0;
        int failed = 0;
        for (var future : futures) {
            try {
                SessionState state = future.get();
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

    /** Returns information about a user's PVC for a specific storage type. */
    public Map<String, String> getUserPvcInfo(String userId, String storageType) {
        return k8sClient.getUserPvcInfo(userId, storageType);
    }

    /** Returns PVC info for all storage types. */
    public Map<String, Object> getAllUserPvcInfo(String userId) {
        return k8sClient.getAllUserPvcInfo(userId);
    }

    /** Returns the user's storage preferences from ConfigMap. */
    public Map<String, String> getUserStorageInfo(String userId) {
        return k8sClient.getUserStorageInfo(userId);
    }

    /** Saves the user's storage preferences. */
    public void saveUserStoragePreference(String userId, String storageSize, String storageType) {
        k8sClient.saveUserStoragePreference(userId, storageSize, storageType);
    }

    /** Sets the active storage type in the user's ConfigMap. */
    public void setActiveStorageType(String userId, String storageType) {
        k8sClient.setActiveStorageType(userId, storageType);
    }

    /**
     * Creates a PVC for the user. Dispatches to type-specific method.
     * @return error message or null on success
     */
    public String createUserPvc(String userId, String storageType, String storageSize) {
        try {
            switch (storageType) {
                case "longhorn" -> k8sClient.createLonghornPvc(userId, storageSize);
                case "nfs-k8s" -> k8sClient.createNfsK8sPvPvc(userId, storageSize);
                case "nfs-home" -> {
                    if (ldapClient == null) {
                        return "LDAP client not configured";
                    }
                    var wsInfo = ldapClient.lookup(userId);
                    if (wsInfo.isEmpty()) {
                        return "LDAP POSIX account not found for user: " + userId;
                    }
                    k8sClient.createNfsHomePvPvc(userId, wsInfo.get());
                }
                default -> { return "Unknown storage type: " + storageType; }
            }
            return null;
        } catch (Exception e) {
            LOG.warning("Failed to create PVC for " + userId + " (" + storageType + "): " + e.getMessage());
            return "PVC creation failed: " + e.getMessage();
        }
    }

    /**
     * Deletes a user's PVC. Refuses if in use.
     * @return error message or null on success
     */
    public String deleteUserPvc(String userId, String storageType) {
        try {
            boolean deleted = k8sClient.deleteUserPvc(userId, storageType);
            return deleted ? null : "PVC not found or currently in use";
        } catch (Exception e) {
            LOG.warning("Failed to delete PVC for " + userId + " (" + storageType + "): " + e.getMessage());
            return "PVC deletion failed: " + e.getMessage();
        }
    }

    /** Expands a Longhorn PVC to the given size. */
    public void expandUserPvc(String userId, String storageSize) {
        k8sClient.expandLonghornPvc(userId, storageSize);
    }

    // -- Shared NFS operations --

    /** Creates a shared NFS PV/PVC pointing to sourceUser's nfs-k8s directory. */
    public String createSharedNfsPvPvc(String ownerUserId, String sourceUserId) {
        try {
            if (!k8sClient.isShareAllowed(sourceUserId, ownerUserId)) {
                return "Share not allowed: " + sourceUserId + " has not granted access to " + ownerUserId;
            }
            // Check source user has nfs-k8s PVC
            Map<String, String> srcPvc = k8sClient.getUserPvcInfo(sourceUserId, "nfs-k8s");
            if (!"true".equals(srcPvc.get("exists"))) {
                return "Source user " + sourceUserId + " does not have an nfs-k8s PVC";
            }
            Map<String, String> srcPrefs = k8sClient.getUserStorageInfo(sourceUserId);
            boolean readOnly = "ro".equals(srcPrefs.getOrDefault("share.mode", "ro"));
            k8sClient.createSharedNfsPvPvc(ownerUserId, sourceUserId, readOnly);
            return null;
        } catch (Exception e) {
            LOG.warning("Failed to create shared NFS PV/PVC: " + e.getMessage());
            return "Shared PVC creation failed: " + e.getMessage();
        }
    }

    /** Deletes a shared NFS PV/PVC. Refuses if in use. */
    public String deleteSharedNfsPvPvc(String ownerUserId, String sourceUserId) {
        try {
            if (k8sClient.isSharedPvcInUse(ownerUserId, sourceUserId)) {
                return "Shared PVC is currently in use by a running Pod";
            }
            k8sClient.deleteSharedNfsPvPvc(ownerUserId, sourceUserId);
            return null;
        } catch (Exception e) {
            LOG.warning("Failed to delete shared NFS PV/PVC: " + e.getMessage());
            return "Shared PVC deletion failed: " + e.getMessage();
        }
    }

    /** Lists shared PVCs owned by a user. */
    public List<Map<String, String>> listSharedPvcs(String ownerUserId) {
        return k8sClient.listSharedPvcs(ownerUserId);
    }

    /** Lists users who allow sharing their nfs-k8s volumes. */
    public List<Map<String, String>> listSharableVolumes(String requestingUserId) {
        return k8sClient.listSharableVolumes(requestingUserId);
    }

    /** Saves share settings for a user. */
    public void saveShareSettings(String userId, boolean enabled, String allowList, String mode) {
        k8sClient.saveShareSettings(userId, enabled, allowList, mode);
    }

    /** Returns the user's share settings from ConfigMap. */
    public Map<String, String> getShareSettings(String userId) {
        Map<String, String> prefs = k8sClient.getUserStorageInfo(userId);
        Map<String, String> result = new HashMap<>();
        result.put("share.enabled", prefs.getOrDefault("share.enabled", "false"));
        result.put("share.allow", prefs.getOrDefault("share.allow", ""));
        result.put("share.mode", prefs.getOrDefault("share.mode", "ro"));
        return result;
    }

}
