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

    /** Internal name used as identifier (e.g. "coder-agent"). */
    String name();

    /** Human-readable name (e.g. "LLM Coding Agent"). */
    String displayName();

    /** Container image including registry and tag. */
    String containerImage();

    /** Port the tool listens on inside the container. */
    int containerPort();

    /** Whether the tool is accessed via HTTP (Ingress) or VNC (Guacamole). */
    ConnectionType connectionType();

    /** Emoji icon displayed on the dashboard tool card. */
    default String icon() {
        return "🔧";
    }

    /** Short description displayed on the dashboard tool card. */
    default String description() {
        return "";
    }

    /** Environment variables to inject into the container. */
    default Map<String, String> environmentVariables() {
        return Collections.emptyMap();
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
     *   Use this for tools that serve content relative to their own root (e.g. coder-agent).
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
}
