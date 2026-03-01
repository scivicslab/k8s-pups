package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.k8s.SessionInfo;
import com.scivicslab.k8spups.plugin.ConnectionType;
import com.scivicslab.k8spups.plugin.ResourceProfile;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.pojoactor.core.ActorRef;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;

import java.time.Instant;
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
            boolean workspaceActive = plugin.workspaceEnabled() && info.workspaceInfo() != null;
            String workspaceMountTarget = null;
            if (workspaceActive) {
                workspaceMountTarget = plugin.workspaceMountPath() != null
                    ? plugin.workspaceMountPath()
                    : plugin.userDataMountPath();
            }
            boolean workspaceReplacesUserData = workspaceActive
                && workspaceMountTarget != null
                && workspaceMountTarget.equals(plugin.userDataMountPath());

            // Create workspace NFS PV/PVC if needed
            if (workspaceActive) {
                k8sClient.createWorkspacePvPvcIfAbsent(info.userId(), info.workspaceInfo());
            }

            // Create per-user PVC if needed (skipped when workspace replaces it)
            if (plugin.userDataMountPath() != null && !workspaceReplacesUserData) {
                String storageSize = resolveStorageSize();
                k8sClient.createUserPvcIfAbsent(info.userId(), storageSize);
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

        try {
            if (info.toolPlugin().connectionType() == ConnectionType.HTTP) {
                // TODO: Re-enable after SSO issue is resolved
                // k8sClient.deleteSecurityPolicy(info.sessionId());
                k8sClient.deleteHTTPRoute(info.sessionId());
            }
            k8sClient.deleteService(info.serviceName());
            k8sClient.deletePod(info.podName());
        } catch (Exception e) {
            LOG.warning("Error during session cleanup " + info.sessionId() + ": " + e.getMessage());
        }

        state = SessionState.STOPPED;
        LOG.info("Session stopped: " + info.sessionId());
    }

    /**
     * Check if session has been idle longer than the given timeout,
     * or exceeded maximum lifetime. Returns true if stopped.
     */
    public boolean checkIdle(long idleTimeoutMinutes, long maxLifetimeMinutes) {
        if (state != SessionState.READY) {
            return false;
        }
        long lifetimeMinutes = java.time.Duration.between(createdTime, Instant.now()).toMinutes();
        if (maxLifetimeMinutes > 0 && lifetimeMinutes >= maxLifetimeMinutes) {
            LOG.info("Session max lifetime reached: " + info.sessionId()
                + " (alive " + lifetimeMinutes + " min, limit " + maxLifetimeMinutes + " min)");
            stop();
            return true;
        }
        long idleMinutes = java.time.Duration.between(lastAccessTime, Instant.now()).toMinutes();
        if (idleTimeoutMinutes > 0 && idleMinutes >= idleTimeoutMinutes) {
            LOG.info("Session idle timeout: " + info.sessionId()
                + " (idle " + idleMinutes + " min)");
            stop();
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
            memo
        );
    }

    public SessionState getState() {
        return state;
    }

    public String getSessionId() {
        return info.sessionId();
    }

    public String getUserId() {
        return info.userId();
    }

    /** Set a user-provided memo for this session. */
    public void setMemo(String text) {
        this.memo = text != null ? text : "";
    }

    // -- Internal --

    private String resolveStorageSize() {
        // User's storage preference takes priority over plugin default
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
