package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.k8s.MountSpec;
import com.scivicslab.k8spups.k8s.SessionInfo;
import com.scivicslab.k8spups.plugin.ConnectionType;
import com.scivicslab.k8spups.plugin.ResourceProfile;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.pojoactor.core.ActorRef;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of a single user session Pod.
 * One instance per user session, driven by POJO-actor tell/ask messages.
 */
public class SessionActor {

    private static final Logger LOG = Logger.getLogger(SessionActor.class.getName());

    private final SessionInfo info;
    private final K8sApiClient k8sClient;

    private SessionState state = SessionState.CREATING;
    private final Instant createdTime = Instant.now();
    private Instant lastAccessTime = Instant.now();
    private Watch podWatch;
    private String memo = "";

    public SessionActor(SessionInfo info, K8sApiClient k8sClient) {
        this.info = info;
        this.k8sClient = k8sClient;
    }

    /**
     * Start the session: create Pod, Service, HTTPRoute, then watch Pod status.
     * Takes self reference so pod watch events are routed through the actor message queue.
     */
    public void start(ActorRef<SessionActor> self) {
        LOG.info("Starting session: user=" + info.userId()
            + ", tool=" + info.toolPlugin().name()
            + ", session=" + info.sessionId());

        state = SessionState.STARTING;

        // start() is invoked via tell() and runs on this actor's virtual thread.
        // Blocking on createPod().get() suspends the virtual thread (unmounts from carrier)
        // while the ForkJoinPool creates the Pod, then resumes here when done.
        // All subsequent k8s API calls are blocking I/O, which virtual threads handle fine.
        try {
            ToolPlugin plugin = info.toolPlugin();
            String storageType = resolveStorageType();

            // Storage type determines what gets mounted at userDataMountPath:
            //   nfs-home  -> workspace NFS home directory (LDAP POSIX account)
            //   longhorn  -> Longhorn PVC (RWO block storage)
            //   nfs-k8s   -> NFS k8s-dedicated PVC (RWX)
            boolean useNfsHome = "nfs-home".equals(storageType)
                && plugin.workspaceEnabled() && info.workspaceInfo() != null;

            // Create workspace NFS PV/PVC if nfs-home is selected
            if (useNfsHome) {
                k8sClient.createWorkspacePvPvcIfAbsent(info.userId(), info.workspaceInfo());
            }

            // Verify that the selected storage PVC exists (no auto-creation)
            if (plugin.userDataMountPath() != null && !useNfsHome) {
                String pvcName = k8sClient.userPvcName(info.userId(), storageType);
                Map<String, String> pvcInfo = k8sClient.getUserPvcInfo(info.userId(), storageType);
                if (!"true".equals(pvcInfo.get("exists"))) {
                    LOG.warning("Session start rejected: PVC not found: " + pvcName
                        + " (user=" + info.userId() + ", type=" + storageType + ")."
                        + " Create the PVC in Storage Settings first.");
                    state = SessionState.FAILED;
                    memo = "Storage PVC not found. Create it in Storage Settings first.";
                    return;
                }
                // RWO conflict check for Longhorn
                if ("longhorn".equals(storageType) && k8sClient.isUserPvcInUse(info.userId(), storageType)) {
                    LOG.warning("Session start rejected: Longhorn PVC " + pvcName
                        + " is already mounted by another Pod (RWO)");
                    state = SessionState.FAILED;
                    memo = "Longhorn PVC is in use by another session. Stop it first.";
                    return;
                }
            }

            // Validate additional mounts
            if (info.additionalMounts() != null && !info.additionalMounts().isEmpty()) {
                Set<String> usedPaths = new HashSet<>();
                if (plugin.userDataMountPath() != null) {
                    usedPaths.add(plugin.userDataMountPath());
                }
                for (MountSpec extra : info.additionalMounts()) {
                    // Mount path conflict check
                    if (!usedPaths.add(extra.mountPath())) {
                        state = SessionState.FAILED;
                        memo = "Duplicate mount path: " + extra.mountPath();
                        return;
                    }
                    // PVC existence check
                    String extraPvcName = extra.sharedFrom() != null
                        ? k8sClient.sharedPvcName(info.userId(), extra.sharedFrom())
                        : k8sClient.userPvcName(info.userId(), extra.storageType());
                    Map<String, String> extraPvcInfo = k8sClient.getUserPvcInfo(info.userId(), extra.storageType());
                    if (extra.sharedFrom() == null && !"true".equals(extraPvcInfo.get("exists"))) {
                        state = SessionState.FAILED;
                        memo = "Additional mount PVC not found: " + extraPvcName;
                        return;
                    }
                    // RWO conflict check for Longhorn
                    if ("longhorn".equals(extra.storageType()) && k8sClient.isUserPvcInUse(info.userId(), extra.storageType())) {
                        state = SessionState.FAILED;
                        memo = "Longhorn PVC is in use by another session (additional mount).";
                        return;
                    }
                }
            }

            // Create Pod, Service, and HTTPRoute in parallel.
            // Service selector matches Pod labels, and HTTPRoute references Service —
            // neither requires the Pod container to be running, only the Pod object to exist.
            var podFuture = k8sClient.createPod(info);

            k8sClient.createService(info);

            if (info.toolPlugin().connectionType() == ConnectionType.HTTP) {
                k8sClient.createHTTPRoute(
                    info.sessionId(),
                    info.serviceName(),
                    info.toolPlugin().containerPort(),
                    info.toolPlugin().passthroughPath()
                );
                // TODO: Re-enable after SSO issue is resolved
                // k8sClient.createSecurityPolicy(info.sessionId(), info.userId());
            }

            // Wait for Pod object to exist before setting up the watch.
            podFuture.get();

            // Route pod watch events through the actor message queue via self.tell(),
            // ensuring onPodEvent runs on the actor's virtual thread (not fabric8's watch thread).
            podWatch = k8sClient.watchPod(info.podName(),
                (action, pod) -> self.tell(sa -> sa.onPodEvent(action, pod)));
        } catch (Exception ex) {
            LOG.severe("Failed to start session " + info.sessionId() + ": " + ex.getMessage());
            state = SessionState.FAILED;
        }
    }

    /**
     * Attach to an existing Running Pod (used for session restoration on controller restart).
     * Re-creates Service and HTTPRoute if they are missing (e.g. after controller restart).
     */
    public void attachToExisting(ActorRef<SessionActor> self, Pod pod) {
        LOG.info("Restoring session: user=" + info.userId()
            + ", tool=" + info.toolPlugin().name()
            + ", session=" + info.sessionId());

        // Check if Pod is Running and Ready
        boolean running = pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase());
        boolean ready = running && pod.getStatus().getConditions() != null
            && pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));

        if (ready) {
            state = SessionState.READY;
        } else if (running) {
            state = SessionState.STARTING;
        } else {
            LOG.warning("Pod not running during restore: " + info.podName()
                + " phase=" + (pod.getStatus() != null ? pod.getStatus().getPhase() : "null"));
            state = SessionState.FAILED;
            return;
        }

        // Restore memo from Pod annotation
        if (pod.getMetadata().getAnnotations() != null) {
            String savedMemo = pod.getMetadata().getAnnotations().get("k8s-pups/memo");
            if (savedMemo != null) {
                this.memo = savedMemo;
            }
        }

        // Re-create Service and HTTPRoute if missing (they may have been lost on controller restart)
        try {
            k8sClient.ensureService(info);
        } catch (Exception e) {
            LOG.warning("Failed to ensure Service for " + info.sessionId() + ": " + e.getMessage());
        }
        if (info.toolPlugin().connectionType() == ConnectionType.HTTP) {
            try {
                k8sClient.ensureHTTPRoute(
                    info.sessionId(),
                    info.serviceName(),
                    info.toolPlugin().containerPort(),
                    info.toolPlugin().passthroughPath()
                );
            } catch (Exception e) {
                LOG.warning("Failed to ensure HTTPRoute for " + info.sessionId() + ": " + e.getMessage());
            }
        }

        // Set up pod watch for ongoing status monitoring
        try {
            podWatch = k8sClient.watchPod(info.podName(),
                (action, p) -> self.tell(sa -> sa.onPodEvent(action, p)));
            LOG.info("Session restored: " + info.sessionId() + " state=" + state
                + (memo.isEmpty() ? "" : " memo=\"" + memo + "\""));
        } catch (Exception ex) {
            LOG.severe("Failed to set up pod watch for restored session " + info.sessionId() + ": " + ex.getMessage());
            state = SessionState.FAILED;
        }
    }

    /**
     * Stop the session: delete HTTPRoute, Service, and Pod.
     */
    public void stop() {
        if (state == SessionState.STOPPING || state == SessionState.STOPPED) {
            return;
        }
        LOG.info("Stopping session: " + info.sessionId());
        state = SessionState.STOPPING;

        if (podWatch != null) {
            podWatch.close();
            podWatch = null;
        }

        if (info.toolPlugin().connectionType() == ConnectionType.HTTP) {
            try {
                k8sClient.deleteHTTPRoute(info.sessionId());
            } catch (Exception e) {
                LOG.warning("Failed to delete HTTPRoute for " + info.sessionId() + ": " + e.getMessage());
            }
        }
        try {
            k8sClient.deleteService(info.serviceName());
        } catch (Exception e) {
            LOG.warning("Failed to delete Service for " + info.sessionId() + ": " + e.getMessage());
        }
        try {
            k8sClient.deletePod(info.podName());
        } catch (Exception e) {
            LOG.warning("Failed to delete Pod for " + info.sessionId() + ": " + e.getMessage());
        }

        state = SessionState.STOPPED;
        LOG.info("Session stopped: " + info.sessionId());
    }

    /**
     * Check if session should be stopped due to idle timeout or max lifetime.
     * Returns true if the session should be stopped.
     * Does NOT call stop() — the caller (SessionManagerActor) is responsible
     * for sending a separate tell(stop) message to maintain actor model semantics.
     */
    public boolean shouldStop(long idleTimeoutMinutes, long maxLifetimeMinutes) {
        if (state != SessionState.READY) {
            return false;
        }
        long lifetimeMinutes = java.time.Duration.between(createdTime, Instant.now()).toMinutes();
        if (maxLifetimeMinutes > 0 && lifetimeMinutes >= maxLifetimeMinutes) {
            LOG.info("Session max lifetime reached: " + info.sessionId()
                + " (alive " + lifetimeMinutes + " min, limit " + maxLifetimeMinutes + " min)");
            return true;
        }
        long idleMinutes = java.time.Duration.between(lastAccessTime, Instant.now()).toMinutes();
        if (idleTimeoutMinutes > 0 && idleMinutes >= idleTimeoutMinutes) {
            LOG.info("Session idle timeout: " + info.sessionId()
                + " (idle " + idleMinutes + " min)");
            return true;
        }
        return false;
    }

    /** Record user activity to reset idle timer. */
    public void touch() {
        lastAccessTime = Instant.now();
    }

    /** Return current session status. */
    public SessionStatus getStatus() {
        String url = null;
        if (state == SessionState.READY && info.toolPlugin().connectionType() == ConnectionType.HTTP) {
            url = "/session/" + info.sessionId() + "/";
        }
        return new SessionStatus(
            info.sessionId(),
            info.userId(),
            info.toolPlugin().displayName(),
            state,
            info.podName(),
            url,
            memo,
            info.userStorageType()
        );
    }

    public SessionState getState() {
        return state;
    }

    public String getSessionId() {
        return info.sessionId();
    }

    public String getToolName() {
        return info.toolPlugin().name();
    }

    public String getUserId() {
        return info.userId();
    }

    /** Set a user-provided memo for this session. Persists to Pod annotation. */
    public void setMemo(String text) {
        this.memo = text != null ? text : "";
        try {
            k8sClient.setPodAnnotation(info.podName(), "k8s-pups/memo", this.memo);
        } catch (Exception e) {
            LOG.warning("Failed to persist memo to pod annotation: " + e.getMessage());
        }
    }

    // -- Internal --

    private String resolveStorageSize() {
        if (info.userStoragePreference() != null && !info.userStoragePreference().isBlank()) {
            return info.userStoragePreference();
        }
        var profiles = info.toolPlugin().resourceProfiles();
        String profileName = info.resourceProfile();
        if (profileName != null) {
            for (ResourceProfile p : profiles) {
                if (p.name().equals(profileName)) {
                    return p.storageSize();
                }
            }
        }
        return profiles.isEmpty() ? "20Gi" : profiles.get(0).storageSize();
    }

    private String resolveStorageType() {
        // SessionInfo.userStorageType() carries activeStorageType from ConfigMap
        if (info.userStorageType() != null && !info.userStorageType().isBlank()) {
            return info.userStorageType();
        }
        return "longhorn";
    }

    private void onPodEvent(Watcher.Action action, Pod pod) {
        if (action == Watcher.Action.MODIFIED) {
            if (pod.getStatus() != null && pod.getStatus().getConditions() != null) {
                boolean ready = pod.getStatus().getConditions().stream()
                    .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
                if (ready && state == SessionState.STARTING) {
                    state = SessionState.READY;
                    LOG.info("Session ready: " + info.sessionId());
                }
            }
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
            if ("Failed".equals(phase) || "Unknown".equals(phase)) {
                LOG.warning("Pod failed: " + info.podName() + " phase=" + phase);
                state = SessionState.FAILED;
            }
        } else if (action == Watcher.Action.DELETED) {
            LOG.info("Pod deleted externally: " + info.podName());
            state = SessionState.STOPPED;
        }
    }
}
