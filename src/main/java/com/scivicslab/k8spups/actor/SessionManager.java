package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.storage.StorageAllocationService;
import com.scivicslab.k8spups.dataset.DatasetService;
import com.scivicslab.k8spups.settings.SettingsEnv;
import com.scivicslab.k8spups.settings.SettingsService;
import com.scivicslab.k8spups.settings.SystemSettings;
import com.scivicslab.k8spups.k8s.MountSpec;
import com.scivicslab.k8spups.k8s.SessionInfo;
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
public class SessionManager {

    private static final Logger LOG = Logger.getLogger(SessionManager.class.getName());

    private final K8sApiClient k8sClient;
    private final Map<String, ToolPlugin> plugins;
    private final String controllerNamespace;
    private final int maxSessions;
    private final int maxSessionsPerUser;
    private final long idleTimeoutMinutes;
    private final long maxLifetimeMinutes;
    private final Set<String> unlimitedUsers;

    /** Every session is a child actor named {@code session-<sessionId>}; the library keeps the list of children. */
    private static final String CHILD_PREFIX = "session-";

    public SessionManager(K8sApiClient k8sClient, Map<String, ToolPlugin> plugins,
                               String controllerNamespace,
                               int maxSessions, int maxSessionsPerUser, long idleTimeoutMinutes,
                               long maxLifetimeMinutes,
                               Set<String> unlimitedUsers) {
        this.k8sClient = k8sClient;
        this.plugins = plugins;
        this.controllerNamespace = controllerNamespace;
        this.maxSessions = maxSessions;
        this.maxSessionsPerUser = maxSessionsPerUser;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
        this.maxLifetimeMinutes = maxLifetimeMinutes;
        this.unlimitedUsers = unlimitedUsers;
    }

    /** All sessions: the children of this actor. */
    private List<ActorRef<Session>> sessions(ActorRef<SessionManager> self) {
        List<ActorRef<Session>> result = new ArrayList<>();
        for (String name : self.getNamesOfChildren()) {
            ActorRef<Session> ref = self.system().getActor(name);
            if (ref != null) result.add(ref);
        }
        return result;
    }

    /** One session by id, or null when this actor has no such child. */
    private ActorRef<Session> session(ActorRef<SessionManager> self, String sessionId) {
        String name = CHILD_PREFIX + sessionId;
        return self.getNamesOfChildren().contains(name) ? self.system().getActor(name) : null;   // null for a closed child
    }

    private static String sessionIdOf(ActorRef<Session> ref) {
        return ref.getName().substring(CHILD_PREFIX.length());
    }

    /** The sessions of one user; each session is asked for its owner. */
    private List<ActorRef<Session>> sessionsOfUser(ActorRef<SessionManager> self, String userId) {
        List<ActorRef<Session>> all = sessions(self);
        List<CompletableFuture<String>> owners = new ArrayList<>();
        for (ActorRef<Session> ref : all) owners.add(ref.ask(Session::getUserId));
        List<ActorRef<Session>> result = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            try {
                if (userId.equals(owners.get(i).get())) result.add(all.get(i));
            } catch (Exception e) {
                LOG.warning("Failed to read the owner of " + all.get(i).getName() + ": " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * Create a new session for the user.
     * Returns the SessionStatus, or null if creation was rejected.
     */
    public SessionStatus createSession(ActorRef<SessionManager> self,
                                       String userId, String toolName,
                                       List<String> allowedProjects, String labId,
                                       String resourceProfile,
                                       Map<String, String> userParams) {
        return createSession(self, userId, toolName, allowedProjects, labId, resourceProfile, userParams, null);
    }

    /**
     * Create a new session with storage type override (no additional mounts).
     */
    public SessionStatus createSession(ActorRef<SessionManager> self,
                                       String userId, String toolName,
                                       List<String> allowedProjects, String labId,
                                       String resourceProfile,
                                       Map<String, String> userParams,
                                       String overrideStorageType) {
        return createSession(self, userId, toolName, allowedProjects, labId, resourceProfile,
            userParams, overrideStorageType, Collections.emptyList());
    }

    /**
     * The environment the administrator's shared settings set for this tool, under the tool's own registry
     * config: a value in {@code toolConfigEnv} still wins. Without settings the deployment's values stay.
     */
    private Map<String, String> withSharedSettings(ActorRef<SessionManager> self, String toolName,
                                                   Map<String, String> toolConfigEnv) {
        Map<String, String> env = new HashMap<>();
        ActorRef<SettingsService> settings = self.system().getActor(SettingsService.ACTOR_NAME);
        if (settings != null) {
            try {
                SystemSettings s = settings.ask(SettingsService::load).get();
                env.putAll(SettingsEnv.forTool(toolName, s));
            } catch (Exception e) {
                LOG.warning("Shared settings not read for " + toolName + ": " + e.getMessage());
            }
        }
        if (toolConfigEnv != null) env.putAll(toolConfigEnv);
        return env;
    }

    /**
     * Hands the Visas of a user, taken from the UserInfo response of the user's login, to the dataset service; they decide
     * which datasets the next sessions of the user get. Returns whether the Passport support wants the Visas supplied.
     */
    public boolean supplyVisas(ActorRef<SessionManager> self, String userId, List<String> visas) {
        ActorRef<DatasetService> datasets = self.system().getActor(DatasetService.ACTOR_NAME);
        if (datasets == null) return false;
        try {
            if (!datasets.ask(DatasetService::wantsSuppliedVisas).get()) return false;
            datasets.ask(d -> { d.supplyVisas(userId, visas); return null; }).get();
            return true;
        } catch (Exception e) {
            LOG.warning("Visas of " + userId + " could not be supplied: " + e.getMessage());
            return false;
        }
    }

    /**
     * Adds to the environment the credentials of the S3 server for the datasets that the user's Visas grant, when the
     * S3 access is on. A Pod that gets them is stopped when the user loses one of these datasets, like a Pod that
     * mounts a dataset.
     */
    private Map<String, String> withS3Access(ActorRef<SessionManager> self, String userId, Map<String, String> env) {
        ActorRef<DatasetService> datasets = self.system().getActor(DatasetService.ACTOR_NAME);
        if (datasets == null) return env;
        try {
            Map<String, String> s3Env = datasets.ask(d -> d.s3Env(userId)).get();
            if (s3Env.isEmpty()) return env;
            Map<String, String> merged = new HashMap<>(env);
            merged.putAll(s3Env);
            return merged;
        } catch (Exception e) {
            LOG.warning("S3 access failed for " + userId + ": " + e.getMessage());
            return env;
        }
    }

    /**
     * The mounts the user chose at launch, checked: a dataset only if the user may read it now (from the database or a
     * Visa), the file-level view only if files are granted one by one, a group's storage only if the user is a member
     * (read-only for a read-only member). Nothing is mounted that the user did not choose. Returns null when a chosen
     * mount is not allowed, so the session is refused instead of starting without it.
     */
    private List<MountSpec> resolveMounts(ActorRef<SessionManager> self, String userId, List<MountSpec> requested) {
        List<MountSpec> mounts = new ArrayList<>();
        if (requested == null || requested.isEmpty()) return mounts;
        boolean wantsDatasets = requested.stream().anyMatch(m -> K8sApiClient.isDatasetStorageType(m.storageType()));
        DatasetService.Mounts granted = DatasetService.Mounts.NONE;
        if (wantsDatasets) {
            ActorRef<DatasetService> datasets = self.system().getActor(DatasetService.ACTOR_NAME);
            try {
                if (datasets != null) granted = datasets.ask(d -> d.prepareMounts(userId)).get();
            } catch (Exception e) {
                LOG.warning("Dataset mounts failed for " + userId + ": " + e.getMessage());
                return null;
            }
        }
        Map<String, String> groups = Map.of();
        if (requested.stream().anyMatch(m -> K8sApiClient.GROUP_STORAGE_TYPE.equals(m.storageType()))) {
            ActorRef<StorageAllocationService> alloc = self.system().getActor(StorageAllocationService.ACTOR_NAME);
            try {
                if (alloc != null) groups = alloc.ask(a -> a.memberships(userId)).get();
            } catch (Exception e) {
                LOG.warning("Group memberships of " + userId + " could not be read: " + e.getMessage());
                return null;
            }
        }
        for (MountSpec m : requested) {
            if (K8sApiClient.DATASET_ROOT_STORAGE_TYPE.equals(m.storageType())) {
                if (m.subPath() == null || !granted.datasets().contains(m.subPath())) {
                    LOG.warning("Refused mount of dataset " + m.subPath() + " for " + userId + ": not granted");
                    return null;
                }
                mounts.add(new MountSpec(m.storageType(), m.mountPath(), null, m.subPath(), true));
            } else if (K8sApiClient.DATASET_STORAGE_TYPE.equals(m.storageType())) {
                if (!granted.fileView()) {
                    LOG.warning("Refused mount of the granted files for " + userId + ": no files are granted");
                    return null;
                }
                mounts.add(new MountSpec(m.storageType(), m.mountPath(), null, null, true));
            } else if (K8sApiClient.GROUP_STORAGE_TYPE.equals(m.storageType())) {
                String mode = groups.get(m.sharedFrom());
                if (mode == null) {
                    LOG.warning("Refused mount of group " + m.sharedFrom() + " for " + userId + ": not a member");
                    return null;
                }
                mounts.add(new MountSpec(m.storageType(), m.mountPath(), m.sharedFrom(), null, "ro".equals(mode)));
            } else {
                mounts.add(m);   // the user's own storage, checked against the allocations in createSession
            }
        }
        return mounts;
    }

    /** Type to size of the user's allocated storage; empty when the allocation service is missing or fails. */
    private Map<String, String> allocatedStorage(ActorRef<SessionManager> self, String userId) {
        ActorRef<StorageAllocationService> alloc = self.system().getActor(StorageAllocationService.ACTOR_NAME);
        if (alloc == null) return Map.of();
        try {
            return alloc.ask(a -> a.activeTypes(userId)).get();
        } catch (Exception e) {
            LOG.warning("Storage allocations of " + userId + " could not be read: " + e.getMessage());
            return Map.of();
        }
    }

    /** Creates the PVCs of the user's allocations that do not exist yet (and the default allocation at first use). */
    private void ensureStorage(ActorRef<SessionManager> self, String userId) {
        ActorRef<StorageAllocationService> alloc = self.system().getActor(StorageAllocationService.ACTOR_NAME);
        if (alloc == null) return;
        try {
            alloc.ask(a -> { a.ensureStorage(userId); return null; }).get();
        } catch (Exception e) {
            LOG.warning("Storage of " + userId + " could not be prepared: " + e.getMessage());
        }
    }

    /**
     * Create a new session with optional storage type override and additional mounts.
     * Returns the SessionStatus, or null if creation was rejected.
     */
    public SessionStatus createSession(ActorRef<SessionManager> self,
                                       String userId, String toolName,
                                       List<String> allowedProjects, String labId,
                                       String resourceProfile,
                                       Map<String, String> userParams,
                                       String overrideStorageType,
                                       List<MountSpec> additionalMounts) {
        // Check global limit
        if (sessions(self).size() >= maxSessions) {
            LOG.warning("Max sessions reached: " + maxSessions);
            return null;
        }
        // Check per-user limit (-1 means unlimited, unlimitedUsers bypass the check)
        int userCount = sessionsOfUser(self, userId).size();
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
            // Fire ask() to all user sessions in parallel
            Map<String, CompletableFuture<String>> toolFutures = new HashMap<>();
            for (ActorRef<Session> existing : sessionsOfUser(self, userId)) {
                toolFutures.put(sessionIdOf(existing), existing.ask(Session::getToolName));
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

        // Storage comes only from an administrator's allocation: the primary storage and every additional storage mount
        // must be an allocated type of the user. ensureStorage first gives a new user the default allocation and creates
        // the missing PVCs of the allocations.
        if (plugin.userDataMountPath() != null || (additionalMounts != null && !additionalMounts.isEmpty())) {
            ensureStorage(self, userId);
            Map<String, String> allocated = allocatedStorage(self, userId);
            if (plugin.userDataMountPath() != null && (storageType == null || !allocated.containsKey(storageType))) {
                if (overrideStorageType != null && !overrideStorageType.isBlank()) {
                    LOG.warning("Rejected " + toolName + " for " + userId + ": storage " + overrideStorageType
                        + " is not allocated to the user");
                    return null;
                }
                storageType = allocated.isEmpty() ? null : allocated.keySet().iterator().next();
                if (storageType == null) {
                    LOG.warning("Rejected " + toolName + " for " + userId + ": no storage is allocated to the user");
                    return null;
                }
            }
            if (additionalMounts != null) {
                for (MountSpec m : additionalMounts) {
                    if (K8sApiClient.isDatasetStorageType(m.storageType())
                            || K8sApiClient.GROUP_STORAGE_TYPE.equals(m.storageType())) continue;   // see resolveMounts
                    if (m.sharedFrom() != null || !allocated.containsKey(m.storageType())) {
                        LOG.warning("Rejected " + toolName + " for " + userId + ": mount " + m.storageType()
                            + " is not allocated to the user");
                        return null;
                    }
                }
            }
        }

        List<MountSpec> mounts = resolveMounts(self, userId, additionalMounts);
        if (mounts == null) {
            LOG.warning("Rejected " + toolName + " for " + userId + ": a chosen mount is not allowed");
            return null;
        }
        SessionInfo info = new SessionInfo(sessionId, userId, plugin, allowedProjects, labId, resourceProfile,
            userParams != null ? userParams : Collections.emptyMap(), storagePref, storageType,
            mounts,
            withS3Access(self, userId, withSharedSettings(self, toolName, toolConfigEnv)));
        Session actor = new Session(info, k8sClient);

        // Create as child actor
        ActorRef<Session> childRef = self.createChild(CHILD_PREFIX + sessionId, actor);
        auditSessionStart(self, childRef);

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
    public void restoreSession(ActorRef<SessionManager> self,
                                String sessionId, String userId, String toolName, Pod pod) {
        ToolPlugin plugin = plugins.get(toolName);
        if (plugin == null) {
            LOG.warning("Cannot restore session " + sessionId + ": unknown tool '" + toolName + "'");
            return;
        }
        if (session(self, sessionId) != null) {
            LOG.info("Session already known, skipping restore: " + sessionId);
            return;
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
                        if (claim.endsWith("-lustre-csi")) { storageType = "lustre-csi"; break; }
                        if (claim.endsWith("-longhorn")) { storageType = "longhorn"; break; }
                        if (claim.endsWith("-nfs-k8s")) { storageType = "nfs-k8s"; break; }
                    }
                }
            }
        }

        SessionInfo info = new SessionInfo(sessionId, userId, plugin, null, null, null,
            Collections.emptyMap(), null, storageType);
        Session actor = new Session(info, k8sClient);

        ActorRef<Session> childRef = self.createChild(CHILD_PREFIX + sessionId, actor);

        // Attach to existing Pod (async)
        childRef.tell(sa -> sa.attachToExisting(childRef, pod));

        LOG.info("Session restored: user=" + userId + ", tool=" + toolName + ", session=" + sessionId);
    }

    /**
     * Stop and remove a session by sessionId.
     * Sends stop() via tell() and waits for completion before closing the actor,
     * ensuring the k8s resource cleanup finishes before the actor thread terminates.
     */
    public void destroySession(ActorRef<SessionManager> self, String sessionId) {
        destroySession(self, sessionId, "stopped by the user");
    }

    /** Records in the audit trail that a session that mounted datasets ended, and why. */
    private void auditSessionStop(ActorRef<SessionManager> self, ActorRef<Session> ref, String reason) {
        ActorRef<DatasetService> datasets = self.system().getActor(DatasetService.ACTOR_NAME);
        if (datasets == null) return;
        try {
            DatasetService.SessionMounts mounts = ref.ask(Session::getMounts).get();
            if (!mounts.datasets().isEmpty() || mounts.fileView()) datasets.tell(d -> d.auditSessionStop(mounts, reason));
        } catch (Exception e) {
            LOG.warning("Audit of the stop of " + ref.getName() + " failed: " + e.getMessage());
        }
    }

    private void auditSessionStart(ActorRef<SessionManager> self, ActorRef<Session> ref) {
        ActorRef<DatasetService> datasets = self.system().getActor(DatasetService.ACTOR_NAME);
        if (datasets == null) return;
        try {
            DatasetService.SessionMounts mounts = ref.ask(Session::getMounts).get();
            if (!mounts.datasets().isEmpty() || mounts.fileView()) datasets.tell(d -> d.auditSessionStart(mounts));
        } catch (Exception e) {
            LOG.warning("Audit of the start of " + ref.getName() + " failed: " + e.getMessage());
        }
    }

    public void destroySession(ActorRef<SessionManager> self, String sessionId, String reason) {
        ActorRef<Session> ref = session(self, sessionId);
        if (ref != null) {
            auditSessionStop(self, ref, reason);
            try {
                // Wait for stop() to complete before closing the actor
                ref.tell(Session::stop).get();
            } catch (Exception e) {
                LOG.warning("Error stopping session " + sessionId + ": " + e.getMessage());
            }
            ref.close();
            // pojo-actor 3.0.0 does not take a closed child out of its parent's list; newer versions do, and this is then a no-op
            self.getNamesOfChildren().remove(CHILD_PREFIX + sessionId);
            LOG.info("Session destroyed: " + sessionId);
        }
    }

    /**
     * Get the status of all sessions belonging to a user.
     * Fires ask() to all SessionActors in parallel, then collects results.
     */
    public List<SessionStatus> getUserSessions(ActorRef<SessionManager> self, String userId) {
        // Fire all ask() calls in parallel
        List<CompletableFuture<SessionStatus>> futures = new ArrayList<>();
        for (ActorRef<Session> ref : sessionsOfUser(self, userId)) {
            futures.add(ref.ask(Session::getStatus));
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
    public void updateMemo(ActorRef<SessionManager> self, String sessionId, String memo) {
        ActorRef<Session> ref = session(self, sessionId);
        if (ref != null) {
            ref.tell(sa -> sa.setMemo(memo));
        }
    }

    public String getSessionLogs(ActorRef<SessionManager> self, String sessionId, int tailLines) {
        ActorRef<Session> ref = session(self, sessionId);
        if (ref == null) return "";
        return ref.ask(sa -> sa.getPodLogs(tailLines)).join();
    }

    /**
     * Touch a session to reset idle timer.
     */
    public void touchSession(ActorRef<SessionManager> self, String sessionId) {
        ActorRef<Session> ref = session(self, sessionId);
        if (ref != null) {
            ref.tell(Session::touch);
        }
    }

    /**
     * Touch all sessions belonging to a user.
     */
    public void touchUserSessions(ActorRef<SessionManager> self, String userId) {
        for (ActorRef<Session> ref : sessionsOfUser(self, userId)) {
            ref.tell(Session::touch);
        }
    }

    /**
     * Check all sessions for idle timeout. Called periodically by Scheduler.
     * Fires ask() to all SessionActors in parallel (non-blocking), then collects
     * results. Sessions that should stop are destroyed via destroySession().
     */
    public void checkIdleSessions(ActorRef<SessionManager> self) {
        // Fire all ask() calls in parallel — each ask is queued in its Session's mailbox
        Map<String, CompletableFuture<Boolean>> futures = new HashMap<>();
        for (ActorRef<Session> ref : sessions(self)) {
            futures.put(sessionIdOf(ref),
                ref.ask(sa -> sa.shouldStop(idleTimeoutMinutes, maxLifetimeMinutes)));
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
            destroySession(self, sessionId, "idle or lifetime limit reached");
        }
    }

    /**
     * Stops every session of a user who has a session mounting a dataset the user may no longer read (access
     * revoked, dataset deleted, or expired). The set of mounted datasets is fixed when a Pod is created, so the Pod
     * must go; the user starts the tools again and gets the datasets that are still allowed. Called after every change of access and periodically
     * (for expiry).
     */
    public void enforceDatasetAccess(ActorRef<SessionManager> self) {
        ActorRef<DatasetService> datasets = self.system().getActor(DatasetService.ACTOR_NAME);
        if (datasets == null) return;
        Map<String, Set<String>> activeByUser = new HashMap<>();
        Map<String, String> userOfSession = new HashMap<>();
        Set<String> revokedUsers = new HashSet<>();
        for (ActorRef<Session> ref : sessions(self)) {
            try {
                String user = ref.ask(Session::getUserId).get();
                userOfSession.put(sessionIdOf(ref), user);
                List<String> mounted = ref.ask(Session::getDatasetNames).get();
                if (mounted.isEmpty()) continue;
                Set<String> active = activeByUser.get(user);
                if (active == null) {
                    active = new HashSet<>(datasets.ask(d -> d.accessibleDatasets(user)).get());
                    activeByUser.put(user, active);
                }
                if (!active.containsAll(mounted)) revokedUsers.add(user);
            } catch (Exception e) {
                LOG.warning("Dataset access check failed for " + ref.getName() + ": " + e.getMessage());
            }
        }
        for (var entry : userOfSession.entrySet()) {
            if (!revokedUsers.contains(entry.getValue())) continue;
            LOG.info("Stopping session " + entry.getKey() + ": its user lost access to a mounted dataset");
            destroySession(self, entry.getKey(), "access to a mounted dataset was taken away or expired");
        }
    }

    /**
     * Stops the sessions that mount a group's storage their user may no longer use as mounted: the user was removed
     * from the group, the group's storage was taken away, or the user became read-only while the Pod mounts it
     * read-write. Called periodically and after an administrator changes a group.
     */
    public void enforceStorageAccess(ActorRef<SessionManager> self) {
        ActorRef<StorageAllocationService> alloc = self.system().getActor(StorageAllocationService.ACTOR_NAME);
        if (alloc == null) return;
        Map<String, Map<String, String>> byUser = new HashMap<>();
        List<String> stop = new ArrayList<>();
        for (ActorRef<Session> ref : sessions(self)) {
            try {
                Map<String, Boolean> mounted = ref.ask(Session::getGroupMounts).get();
                if (mounted.isEmpty()) continue;
                String user = ref.ask(Session::getUserId).get();
                Map<String, String> member = byUser.get(user);
                if (member == null) {
                    member = alloc.ask(a -> a.memberships(user)).get();
                    byUser.put(user, member);
                }
                for (var e : mounted.entrySet()) {
                    String mode = member.get(e.getKey());
                    if (mode == null || ("ro".equals(mode) && !e.getValue())) { stop.add(sessionIdOf(ref)); break; }
                }
            } catch (Exception e) {
                LOG.warning("Group storage check failed for " + ref.getName() + ": " + e.getMessage());
            }
        }
        for (String id : stop) {
            LOG.info("Stopping session " + id + ": its user may no longer use a mounted group storage as mounted");
            destroySession(self, id, "access to a mounted group storage was taken away");
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
    public boolean hasSession(ActorRef<SessionManager> self, String userId) {
        return !sessionsOfUser(self, userId).isEmpty();
    }

    /**
     * Returns an aggregated summary of all sessions by state.
     * Fires ask() to all SessionActors in parallel, then aggregates.
     */
    public SessionSummary getSessionSummary(ActorRef<SessionManager> self) {
        List<ActorRef<Session>> all = sessions(self);
        int total = all.size();
        // Fire all ask() calls in parallel
        List<CompletableFuture<SessionState>> futures = new ArrayList<>();
        for (ActorRef<Session> ref : all) {
            futures.add(ref.ask(Session::getState));
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
    public Set<String> getSessionIds(ActorRef<SessionManager> self) {
        Set<String> ids = new HashSet<>();
        for (ActorRef<Session> ref : sessions(self)) ids.add(sessionIdOf(ref));
        return ids;
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













}
