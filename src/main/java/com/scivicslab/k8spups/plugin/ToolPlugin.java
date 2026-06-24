package com.scivicslab.k8spups.plugin;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Service Provider Interface for tool plugins.
 * Each plugin defines how to create a user Pod for a specific tool.
 * Discovered via ServiceLoader.
 */
public interface ToolPlugin {

    /** Internal name used as identifier (e.g. "chat-ui"). */
    String name();

    /** Human-readable name (e.g. "LLM Coding Agent"). */
    String displayName();

    /** Container image including registry and tag. */
    String containerImage();

    /** Port the tool listens on inside the container. */
    int containerPort();

    /** Whether the tool is accessed via HTTP (Ingress) or VNC (Guacamole). */
    ConnectionType connectionType();

    /** Icon path (relative to static resources root) displayed on the dashboard tool card. */
    default String icon() {
        return "icons/" + name() + ".png";
    }

    /** Short description displayed on the dashboard tool card. */
    default String description() {
        return "";
    }

    /** Environment variables to inject into the container. */
    default Map<String, String> environmentVariables() {
        return Collections.emptyMap();
    }

    /**
     * Override the container entrypoint (Kubernetes command:).
     * When non-empty, replaces the image's default ENTRYPOINT.
     * Use a shell invocation (e.g. ["/bin/sh", "-c", "..."]) to expand env vars like $PUPS_SESSION_PATH.
     * Default: empty (use image ENTRYPOINT as-is).
     */
    default List<String> containerCommand() {
        return Collections.emptyList();
    }

    /** CPU/memory requests (e.g. "cpu" -> "500m", "memory" -> "1Gi"). */
    default Map<String, String> resourceRequests() {
        return Map.of("cpu", "500m", "memory", "1Gi");
    }

    /** CPU/memory limits. */
    default Map<String, String> resourceLimits() {
        return Map.of("cpu", "2", "memory", "4Gi");
    }

    /** Additional paths that need a writable emptyDir (besides /tmp). */
    default List<String> writablePaths() {
        return Collections.emptyList();
    }

    /**
     * Mount path for the per-user persistent PVC inside the container.
     * If non-null, a PVC named "pups-data-{userId}" is created (if absent)
     * and mounted at this path. The PVC persists across sessions.
     * Return null to skip PVC mounting.
     */
    default String userDataMountPath() {
        return null;
    }

    /**
     * Whether to mount the container's root filesystem as read-only.
     * Default true (secure). Override to false for tools that need to write to system paths
     * (e.g. desktop environments that write to /var/log, /run, /var/lib, etc.).
     */
    default boolean readOnlyRootFilesystem() {
        return true;
    }

    /**
     * UID to run the container process as. Default 1000.
     * Return null to omit runAsUser from the security context (allows the image's default,
     * which may be root for tools that need to manage multiple system services).
     */
    default Long runAsUser() {
        return 1000L;
    }

    /**
     * Whether to enforce non-root execution at the pod level.
     * Default true. Override to false for tools that need to run system services as root
     * (e.g. supervisord managing a desktop environment).
     */
    default boolean runAsNonRoot() {
        return true;
    }

    /**
     * Seccomp profile type for the pod-level security context.
     * Default "RuntimeDefault". Override to "Unconfined" for tools that need
     * syscalls blocked by the default profile (e.g. close_range used by GLib 2.78+
     * on Ubuntu 24.04, which is needed by desktop environments like MATE).
     */
    default String seccompProfileType() {
        return "RuntimeDefault";
    }

    /**
     * Whether to pass the session path through to the container without URL rewriting.
     *
     * false (default): HTTPRoute rewrites /session/{id}/* -> /* before forwarding.
     *   Use this for tools that serve content relative to their own root (e.g. chat-ui).
     *
     * true: HTTPRoute forwards /session/{id}/* as-is without rewriting.
     *   Use this for tools that need to know their own base URL (e.g. JupyterLab).
     *   The env var PUPS_SESSION_PATH=/session/{id}/ is always injected so the tool
     *   can configure itself accordingly.
     */
    default boolean passthroughPath() {
        return false;
    }

    /**
     * HTTP path for the readiness probe on containerPort().
     * Return null (default) to skip the readiness probe.
     * When set, k8s will not route traffic to the pod until this endpoint returns 200.
     */
    default String readinessProbePath() {
        return null;
    }

    /**
     * Initial delay in seconds before the first readiness probe check.
     * Default 10 seconds. Only used when readinessProbePath() is non-null.
     */
    default int readinessProbeInitialDelay() {
        return 10;
    }

    /**
     * Period in seconds between readiness probe checks.
     * Default 2 seconds. Only used when readinessProbePath() is non-null.
     */
    default int readinessProbePeriod() {
        return 2;
    }

    /**
     * Available resource profiles for this tool.
     * Default: single profile using resourceRequests()/resourceLimits().
     * Override to offer multiple sizes (e.g. Light / Standard).
     */
    default List<ResourceProfile> resourceProfiles() {
        return List.of(new ResourceProfile("default", "Default", resourceRequests(), resourceLimits()));
    }

    /**
     * User-provided parameters that are shown as input fields on the dashboard.
     * Each parameter maps to an environment variable injected into the Pod.
     * Values are provided by the user at session start time.
     *
     * @return list of parameter definitions (default: empty)
     */
    default List<UserParameter> userParameters() {
        return Collections.emptyList();
    }

    /**
     * Whether this tool supports workspace (NFS home directory) mounting.
     * When true and the user has a POSIX account in LDAP, the user's home directory
     * is mounted via NFS. If false or the user has no POSIX account, the standard
     * per-user PVC (userDataMountPath) is used instead.
     */
    default boolean workspaceEnabled() {
        return false;
    }

    /**
     * Mount path for the workspace (NFS home directory) inside the container.
     * Only used when workspaceEnabled() is true.
     * If null, defaults to userDataMountPath() — workspace replaces the per-user PVC.
     */
    default String workspaceMountPath() {
        return null;
    }

    /**
     * Sub-path within the user's NFS home directory to mount.
     * Only used when workspaceEnabled() is true.
     * If null, the entire home directory is mounted.
     * E.g. "works" to mount only ~/works at workspaceMountPath().
     */
    default String workspaceSubPath() {
        return null;
    }

    /**
     * Sidecar specification for workspace mode.
     * When workspace is active and this returns non-null, the Pod is created with
     * two containers instead of one:
     * - "tool": runs the service (containerPort), keeps the plugin's default UID
     * - "desktop": runs the user workspace as the LDAP UID with NFS mounted
     *
     * This allows services like Guacamole (which require UID 1000 for Tomcat) to
     * coexist with a desktop running as the NFS-owning LDAP UID.
     *
     * Return null (default) to use single-container mode.
     */
    default SidecarSpec workspaceSidecar() {
        return null;
    }

    /**
     * Whether only one instance per user is allowed.
     * When true, creating a second session for the same user and tool is rejected.
     * Default false (multiple instances allowed).
     */
    default boolean singleInstance() {
        return false;
    }

    /**
     * Additional NFS volumes to mount into the container.
     * Each entry is a {@link NfsVolumeSpec} specifying server, path, mount point, and read-only flag.
     * Default: empty (no additional NFS volumes).
     */
    default List<NfsVolumeSpec> nfsVolumes() {
        return Collections.emptyList();
    }

    /**
     * Whether this plugin runs as a batch job.
     * When true, the session is automatically cleaned up after the Pod
     * completes (exits with code 0). The Pod will not stay alive after
     * the main process finishes.
     * Default: false (long-running interactive session).
     */
    default boolean batchMode() {
        return false;
    }

    /**
     * Linux capabilities to add to the container security context.
     * Applied alongside the "drop ALL" baseline (capabilities are first dropped,
     * then only those listed here are added back).
     * Use for tools that require raw socket access or network control
     * (e.g. nmap needs NET_RAW; network bridge tools need NET_ADMIN).
     * Default: empty (no capabilities restored after dropping ALL).
     */
    default List<String> capabilities() {
        return Collections.emptyList();
    }
}
