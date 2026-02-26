package com.scivicslab.k8spups.plugin;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tool plugin for Apache Guacamole remote desktop.
 *
 * The container image must be an all-in-one image that includes:
 *   - guacd (Guacamole protocol daemon)
 *   - Guacamole web application (Tomcat)
 *   - A desktop environment (e.g. Xfce) + VNC server
 *
 * Guacamole listens on port 8080 (Tomcat).
 * The container must run as UID 1000 (non-root).
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
    public String icon() {
        return "🖥️";
    }

    @Override
    public String description() {
        return "Browser-based remote Linux desktop via Apache Guacamole. Full GUI environment accessible from any browser without a VNC client.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/guacamole-desktop:3.1.1-2602261515";
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
            new ResourceProfile("light", "Light (1 CPU / 2 GB)",
                Map.of("cpu", "250m", "memory", "512Mi"),
                Map.of("cpu", "1", "memory", "2Gi")),
            new ResourceProfile("standard", "Standard (2 CPU / 4 GB)",
                Map.of("cpu", "500m", "memory", "1Gi"),
                Map.of("cpu", "2", "memory", "4Gi"))
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
}
