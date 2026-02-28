package com.scivicslab.k8spups.plugin;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tool plugin for Apache Guacamole remote desktop.
 *
 * The container image includes:
 *   - guacd (Guacamole protocol daemon) + Tomcat (Guacamole web app)
 *   - MATE desktop environment + TigerVNC server
 *
 * In standalone mode (no workspace), all services run in a single container as UID 1000.
 * In workspace mode (NFS home mounted), the Pod splits into two sidecar containers:
 *   - "tool" (Guacamole): guacd + Tomcat, UID 1000 (image default, lightweight)
 *   - "desktop" (VNC + MATE): runs as LDAP UID for correct NFS ownership
 *
 * Guacamole listens on port 8080 (Tomcat).
 */
public class GuacamolePlugin implements ToolPlugin {

    @Override
    public String name() {
        return "guacamole";
    }

    @Override
    public String displayName() {
        return "Remote Desktop";
    }

    @Override
    public String description() {
        return "Browser-based remote Linux desktop via Apache Guacamole. Full GUI environment accessible from any browser without a VNC client.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/guacamole-desktop:3.1.1-2602281555";
    }

    @Override
    public int containerPort() {
        return 8080;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public Map<String, String> environmentVariables() {
        return Map.of(
            "HOME", "/home/user",
            "DISPLAY", ":1"
        );
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "500m", "memory", "1Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "2", "memory", "4Gi");
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(
            new ResourceProfile("light", "Light (4 CPU / 16 GB)",
                Map.of("cpu", "250m", "memory", "512Mi"),
                Map.of("cpu", "4", "memory", "16Gi")),
            new ResourceProfile("standard", "Standard (8 CPU / 32 GB)",
                Map.of("cpu", "500m", "memory", "1Gi"),
                Map.of("cpu", "8", "memory", "32Gi"))
        );
    }

    @Override
    public List<String> writablePaths() {
        // readOnlyRootFilesystem=false so system paths are writable; no emptyDirs needed.
        return Collections.emptyList();
    }

    @Override
    public String userDataMountPath() {
        return "/home/user";
    }

    @Override
    public boolean workspaceEnabled() {
        // Mount user's NFS $HOME at /home/user when POSIX account exists.
        return true;
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        // Desktop environment writes to /var/log, /run, /var/lib etc. at startup.
        return false;
    }

    @Override
    public Long runAsUser() {
        // Run as UID 1000 (user). No su needed; entrypoint starts Xvnc/MATE/websockify directly.
        return 1000L;
    }

    @Override
    public boolean runAsNonRoot() {
        return true;
    }

    @Override
    public String seccompProfileType() {
        // GLib 2.78+ (Ubuntu 24.04) uses close_range() syscall to close inherited
        // file descriptors in spawned child processes.  The default k8s seccomp
        // profile blocks this, causing all MATE desktop components to fail with
        // "Failed to close file descriptor for child process (Operation not permitted)".
        return "Unconfined";
    }

    @Override
    public String readinessProbePath() {
        // Tomcat serves Guacamole on /. Probe waits until Tomcat is fully started
        // before routing traffic, preventing "Connection refused" on first access.
        return "/";
    }

    @Override
    public int readinessProbeInitialDelay() {
        // entrypoint.sh: Xvnc(3s) + MATE(2s) + guacd(1s) + Tomcat startup ~5s = ~11s total
        return 10;
    }

    @Override
    public int readinessProbePeriod() {
        return 2;
    }

    @Override
    public SidecarSpec workspaceSidecar() {
        // In workspace mode, split into two containers:
        // - "tool": Guacamole gateway (guacd + Tomcat) as UID 1000 (lightweight)
        // - "desktop": VNC + MATE as LDAP UID with NFS mounted (gets profile resources)
        return new SidecarSpec(
            List.of("/usr/local/bin/entrypoint-guacamole.sh"),
            Map.of("cpu", "100m", "memory", "128Mi"),
            Map.of("cpu", "500m", "memory", "512Mi"),
            List.of("/usr/local/bin/entrypoint-desktop.sh"),
            Map.of("HOME", "/home/user", "DISPLAY", ":1")
        );
    }
}
