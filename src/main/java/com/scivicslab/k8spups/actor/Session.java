package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.k8s.SessionPath;
import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.k8s.MountSpec;
import com.scivicslab.k8spups.k8s.SessionInfo;
import com.scivicslab.k8spups.plugin.ConnectionType;
import com.scivicslab.k8spups.plugin.ResourceProfile;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.k8spups.usage.CpuQuantity;
import com.scivicslab.k8spups.usage.PodCores;
import com.scivicslab.k8spups.usage.PodUsageRepository;
import com.scivicslab.k8spups.usage.PodUsageService;
import com.scivicslab.pojoactor.core.ActorRef;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of a single user session Pod.
 * One instance per user session, driven by POJO-actor tell/ask messages.
 */
public class Session {

    private static final Logger LOG = Logger.getLogger(Session.class.getName());

    // Not final: start() resolves the effective storage type once and writes it back,
    // so Pod building uses the same value instead of resolving it a second time.
    private SessionInfo info;
    private final K8sApiClient k8sClient;

    private SessionState state = SessionState.CREATING;
    private final Instant createdTime = Instant.now();
    private Instant lastAccessTime = Instant.now();
    private Watch podWatch;
    private String memo = "";

    /** When the Pod became Ready while the session was starting; null until then. */
    private Instant podReadyAt;
    /** True when the session was offered to the user before the Service accepted a connection; billing waits for one. */
    private boolean billingPending;
    /** True when somebody other than this session deleted its Pod. */
    private boolean podDeletedExternally;
    /** How long to wait for the Service to accept a connection after the Pod is Ready, before declaring ready anyway. */
    private static final Duration SERVICE_WAIT = Duration.ofSeconds(60);
    private static final int PROBE_TIMEOUT_MILLIS = 1000;

    /** Keeps the billing records of the Pods; null when there is none (the session then records nothing). */
    private final ActorRef<PodUsageService> usage;

    public Session(SessionInfo info, K8sApiClient k8sClient) {
        this(info, k8sClient, null);
    }

    /**
     * @param usage the actor that records how long the Pod of this session ran; null to record nothing
     */
    public Session(SessionInfo info, K8sApiClient k8sClient, ActorRef<PodUsageService> usage) {
        this.info = info;
        this.k8sClient = k8sClient;
        this.usage = usage;
    }

    /**
     * Start the session: create Pod, Service, HTTPRoute, then watch Pod status.
     * Takes self reference so pod watch events are routed through the actor message queue.
     */
    public void start(ActorRef<Session> self) {
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
            // Write the resolved type back so createPod() mounts the volume this
            // method provisions. Without this, a user with no stored preference had
            // the PVC created for one type and the Pod built for another.
            info = info.withUserStorageType(storageType);

            // Storage type determines what gets mounted at userDataMountPath:
            //   longhorn  -> Longhorn PVC (RWO block storage)
                        //   posix     -> shared file storage PVC (RWX)
            // The PVC exists only for an administrator's allocation; SessionManager checked the allocation and created
            // the PVC before this session. A missing PVC therefore means the storage was taken away in between.
            if (plugin.userDataMountPath() != null) {
                String pvcName = k8sClient.userPvcName(info.userId(), storageType);
                Map<String, String> pvcInfo = k8sClient.getUserPvcInfo(info.userId(), storageType);
                if (!"true".equals(pvcInfo.get("exists"))) {
                    LOG.warning("Session start rejected: PVC not found: " + pvcName
                        + " (user=" + info.userId() + ", type=" + storageType + ")");
                    state = SessionState.FAILED;
                    memo = "No storage of this type is allocated to you. Ask an administrator.";
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
                    // The dataset view's PV/PVC is created by the controller while the view is built
                    // (K8sApiClient.syncDatasetView), right before this session is created.
                    if (K8sApiClient.isDatasetStorageType(extra.storageType())) {
                        continue;
                    }
                    // PVC existence check (a group's PVC is created when an administrator gives the group storage)
                    boolean group = K8sApiClient.GROUP_STORAGE_TYPE.equals(extra.storageType());
                    String extraPvcName = group ? k8sClient.groupPvcName(extra.sharedFrom())
                        : k8sClient.userPvcName(info.userId(), extra.storageType());
                    boolean exists = group ? k8sClient.pvcExists(extraPvcName)
                        : "true".equals(k8sClient.getUserPvcInfo(info.userId(), extra.storageType()).get("exists"));
                    if (!exists) {
                        state = SessionState.FAILED;
                        memo = "Storage to mount not found: " + extraPvcName;
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

            // Create Pod and Service. No per-session HTTPRoute is created;
            // all /session/{id}/* traffic routes through the controller catch-all
            // which enforces ownership before proxying to the pod Service.
            var podFuture = k8sClient.createPod(info);

            k8sClient.createService(info);

            // Wait for Pod object to exist before setting up the watch.
            podFuture.get();
            recordCreated();

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
    public void attachToExisting(ActorRef<Session> self, Pod pod) {
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
        recordAttached(pod, ready);

        // Restore memo from Pod annotation
        if (pod.getMetadata().getAnnotations() != null) {
            String savedMemo = pod.getMetadata().getAnnotations().get("k8s-pups/memo");
            if (savedMemo != null) {
                this.memo = savedMemo;
            }
        }

        // Re-create Service if missing (may have been lost on controller restart).
        // No per-session HTTPRoute is needed; the controller catch-all handles routing.
        try {
            k8sClient.ensureService(info);
        } catch (Exception e) {
            LOG.warning("Failed to ensure Service for " + info.sessionId() + ": " + e.getMessage());
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

    /** Stop the session: delete HTTPRoute, Service, and Pod. */
    public void stop() {
        stop("stopped");
    }

    /**
     * Stop the session: delete HTTPRoute, Service, and Pod.
     *
     * @param reason why the session ends; it is kept with the billing record of the Pod
     */
    public void stop(String reason) {
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
            k8sClient.deleteService(info.serviceName());
        } catch (Exception e) {
            LOG.warning("Failed to delete Service for " + info.sessionId() + ": " + e.getMessage());
        }
        try {
            k8sClient.deletePod(info.podName());
        } catch (Exception e) {
            LOG.warning("Failed to delete Pod for " + info.sessionId() + ": " + e.getMessage());
        }
        recordEnded(reason);

        state = SessionState.STOPPED;
        LOG.info("Session stopped: " + info.sessionId());
    }

    /**
     * Check if session should be stopped due to idle timeout or max lifetime.
     * Returns true if the session should be stopped.
     * Does NOT call stop() — the caller (SessionManager) is responsible
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
            url = SessionPath.of(info.sessionId()) + "/";
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

    public String getPodLogs(int tailLines) {
        return k8sClient.getPodLogs(info.podName(), tailLines);
    }

    public String getToolName() {
        return info.toolPlugin().name();
    }

    /** What the audit trail records about this session: who, which Pod, which datasets it mounted. */
    public com.scivicslab.k8spups.dataset.DatasetService.SessionMounts getMounts() {
        boolean fileView = info.additionalMounts() != null && info.additionalMounts().stream()
            .anyMatch(m -> K8sApiClient.DATASET_STORAGE_TYPE.equals(m.storageType()));
        return new com.scivicslab.k8spups.dataset.DatasetService.SessionMounts(
            info.userId(), info.sessionId(), info.podName(), info.toolPlugin().name(), getDatasetNames(), fileView);
    }

    /** Group name to read-only flag of the group storage this session mounted (fixed when the Pod was created). */
    public Map<String, Boolean> getGroupMounts() {
        Map<String, Boolean> groups = new java.util.HashMap<>();
        if (info.additionalMounts() != null) {
            for (MountSpec m : info.additionalMounts()) {
                if (K8sApiClient.GROUP_STORAGE_TYPE.equals(m.storageType())) groups.put(m.sharedFrom(), m.readOnly());
            }
        }
        return groups;
    }

    /** Names of the whole datasets this session mounted (fixed when the Pod was created). */
    public List<String> getDatasetNames() {
        List<String> names = new ArrayList<>();
        if (info.additionalMounts() != null) {
            for (MountSpec m : info.additionalMounts()) {
                if (K8sApiClient.DATASET_ROOT_STORAGE_TYPE.equals(m.storageType()) && m.subPath() != null) names.add(m.subPath());
            }
        }
        // Datasets of an S3 server: the Pod holds credentials made for them, listed in its environment.
        String s3 = info.toolConfigEnv() == null ? null : info.toolConfigEnv().get(com.scivicslab.k8spups.passport.S3Access.DATASETS_ENV);
        if (s3 != null && !s3.isBlank()) {
            for (String d : s3.split(",")) if (!d.isBlank() && !names.contains(d)) names.add(d);
        }
        return names;
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

    // -- Billing records: the Pod of this session, from its creation to its end (see PodUsageService) --

    /** The session is ready, so the user can use the tool: billing of the Pod starts. */
    private void becomeReady() {
        state = SessionState.READY;
        startBilling();
    }

    /** The Service of the Pod accepted a connection (or the Pod is a batch job): the billing of the Pod starts. */
    private void startBilling() {
        if (usage == null) return;
        String sessionId = info.sessionId();
        Instant at = Instant.now();
        usage.tell(u -> u.ready(sessionId, at));
    }

    /** Kubernetes accepted the Pod of this session. */
    private void recordCreated() {
        if (usage == null) return;
        PodCores cores;
        try {
            cores = PodCores.of(info.toolPlugin().resourceProfiles(), info.resourceProfile());
        } catch (IllegalArgumentException e) {
            // A resource profile with an unreadable CPU quantity must not stop a user from working.
            LOG.severe("Pod usage: the CPU of the resource profile of session " + info.sessionId()
                + " is unreadable and is recorded as unknown: " + e.getMessage());
            cores = PodCores.UNKNOWN;
        }
        PodUsageRepository.Start start = new PodUsageRepository.Start(info.sessionId(), info.userId(),
            info.toolPlugin().name(), info.podName(), cores, Instant.now());
        usage.tell(u -> u.created(start));
    }

    /**
     * The controller took over the Pod of this session after a restart. For a Pod that has no billing row yet (it was
     * created before billing records existed), the row is made from the Pod itself: its creation time and the CPU of
     * its tool container.
     */
    private void recordAttached(Pod pod, boolean ready) {
        if (usage == null) return;
        Instant created = parseTime(pod.getMetadata().getCreationTimestamp());
        if (created == null) created = Instant.now();
        Instant readyAt = null;
        if (ready) {
            readyAt = pod.getStatus().getConditions().stream()
                .filter(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()))
                .map(c -> parseTime(c.getLastTransitionTime()))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(Instant.now());
        }
        PodUsageRepository.Start start = new PodUsageRepository.Start(info.sessionId(), info.userId(),
            info.toolPlugin().name(), info.podName(), coresOf(pod), created);
        Instant at = readyAt;
        usage.tell(u -> u.attached(start, at));
    }

    /** The Pod of this session is gone (stopped, failed, or deleted by someone else). */
    private void recordEnded(String reason) {
        if (usage == null) return;
        String sessionId = info.sessionId();
        Instant at = Instant.now();
        usage.tell(u -> u.ended(sessionId, at, reason));
    }

    /** The CPU request and the CPU limit of the tool container of a Pod; unknown when they cannot be read. */
    private static PodCores coresOf(Pod pod) {
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) return PodCores.UNKNOWN;
        try {
            for (io.fabric8.kubernetes.api.model.Container c : pod.getSpec().getContainers()) {
                if (!"tool".equals(c.getName()) || c.getResources() == null) continue;
                return new PodCores(null, cpuOf(c.getResources().getRequests()), cpuOf(c.getResources().getLimits()));
            }
        } catch (IllegalArgumentException e) {
            LOG.severe("Pod usage: the CPU of Pod " + pod.getMetadata().getName() + " is unreadable: " + e.getMessage());
        }
        return PodCores.UNKNOWN;
    }

    private static Integer cpuOf(Map<String, io.fabric8.kubernetes.api.model.Quantity> resources) {
        io.fabric8.kubernetes.api.model.Quantity cpu = resources == null ? null : resources.get("cpu");
        return cpu == null ? null : CpuQuantity.toMillicores(cpu.toString());
    }

    private static Instant parseTime(String text) {
        try {
            return text == null ? null : Instant.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

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
        // No per-user preference (user launched a tool before visiting Storage Settings):
        // fall back to the configured default storage type instead of a hardcoded value,
        // so the K8SPUPS_DEFAULT_STORAGE_TYPE default actually applies. A hardcoded
        // "longhorn" here contradicted that default and silently placed new users on
        // single-replica Longhorn volumes. Read from env to mirror resolveStorageSize().
        return System.getenv().getOrDefault("K8SPUPS_DEFAULT_STORAGE_TYPE", "posix");
    }

    /**
     * The Pod is Ready. The session is ready when the Service of the Pod also accepts a connection, because the
     * controller's proxy reaches the Pod through that Service and a request before it accepts one is refused (the
     * user would see an error on the first Open). Batch jobs listen on no port and are ready at once.
     */
    private void podIsReady() {
        if (info.toolPlugin().batchMode() || k8sClient == null) {
            becomeReady();
            LOG.info("Session ready: " + info.sessionId());
            return;
        }
        if (podReadyAt == null) podReadyAt = Instant.now();
        confirmReachable();
    }

    /**
     * Declares the session ready once the Service of its Pod accepts a connection. Called when the Pod becomes Ready,
     * and every second after that by the scheduler of the session manager until it succeeds. It does nothing for a session
     * that is not waiting for this. When no connection is accepted within a minute, the session is declared ready
     * anyway and the reason is logged, so that a tool whose port cannot be probed is not held back for ever.
     */
    public void confirmReachable() {
        boolean waitingForReady = state == SessionState.STARTING;
        if ((!waitingForReady && !billingPending) || podReadyAt == null) return;
        String host = ServiceProbe.host(info.serviceName(), k8sClient.userPodsNamespace());
        long waitedMillis = Duration.between(podReadyAt, Instant.now()).toMillis();
        boolean reachable = ServiceProbe.reachable(host, info.toolPlugin().containerPort(), PROBE_TIMEOUT_MILLIS);
        if (!waitingForReady) {
            // The session was offered to the user without a connection; the billing starts with the first one.
            if (reachable) {
                billingPending = false;
                startBilling();
                LOG.info("Billing started: " + info.sessionId() + " (the Service accepted a connection " + waitedMillis
                    + " ms after the Pod became Ready)");
            }
            return;
        }
        switch (ReadinessPolicy.decide(reachable, waitedMillis, SERVICE_WAIT.toMillis())) {
            case READY -> {
                becomeReady();
                LOG.info("Session ready: " + info.sessionId() + " (the Service accepted a connection " + waitedMillis
                    + " ms after the Pod became Ready)");
            }
            case READY_BILLING_PENDING -> {
                state = SessionState.READY;
                billingPending = true;
                LOG.warning("Session ready without a connection to its Service, not billed until one is accepted: "
                    + info.sessionId() + " (" + host + ":" + info.toolPlugin().containerPort()
                    + " accepted none within " + SERVICE_WAIT.toSeconds() + " s)");
            }
            case KEEP_WAITING -> { }
        }
    }

    /**
     * Somebody other than this session deleted its Pod (for example an administrator with {@code kubectl delete pod}).
     * The session deletes the Service of the Pod, ends the billing record, and marks itself so that the
     * {@code SessionManager} actor removes it at its next periodic check ({@link #podWasDeletedExternally()}).
     */
    private void podDeletedByOthers() {
        if (podDeletedExternally) return;
        podDeletedExternally = true;
        LOG.info("Pod deleted externally: " + info.podName());
        if (podWatch != null) {
            podWatch.close();
            podWatch = null;
        }
        state = SessionState.STOPPED;
        deleteServiceOfGonePod();
        recordEnded(PodEndReason.DELETED_EXTERNALLY);
    }

    /** Deletes the Service of the Pod that is gone. Kubernetes does not report an error when the Service does not exist. */
    void deleteServiceOfGonePod() {
        try {
            k8sClient.deleteService(info.serviceName());
        } catch (Exception e) {
            LOG.warning("Failed to delete Service for " + info.sessionId() + ": " + e.getMessage());
        }
    }

    /** True when somebody other than this session deleted its Pod; the {@code SessionManager} actor removes such a session. */
    public boolean podWasDeletedExternally() {
        return podDeletedExternally;
    }

    void onPodEvent(Watcher.Action action, Pod pod) {
        if (action == Watcher.Action.MODIFIED) {
            if (pod.getStatus() != null && pod.getStatus().getConditions() != null) {
                boolean ready = pod.getStatus().getConditions().stream()
                    .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
                if (ready && state == SessionState.STARTING) {
                    podIsReady();
                }
            }
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
            if ("Succeeded".equals(phase) && info.toolPlugin().batchMode()) {
                LOG.info("Batch job completed: " + info.podName() + " — auto-stopping session");
                stop("batch job completed");
                return;
            }
            if ("Failed".equals(phase) || "Unknown".equals(phase)) {
                String reason = PodEndReason.of(pod);
                if (PodEndReason.DELETED_EXTERNALLY.equals(reason)) {
                    podDeletedByOthers();
                } else {
                    LOG.warning("Pod failed: " + info.podName() + " phase=" + phase);
                    state = SessionState.FAILED;
                    recordEnded(reason);
                }
            }
        } else if (action == Watcher.Action.DELETED) {
            podDeletedByOthers();
        }
    }
}
