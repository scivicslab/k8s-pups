package com.scivicslab.k8spups.plugin;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tool plugin for Kali Linux desktop — browser-based security testing environment.
 *
 * Runs as root (UID 0) so network security tools (nmap, tcpdump, etc.) can use
 * raw sockets. NET_RAW and NET_ADMIN capabilities are added to the container.
 *
 * Access: Apache Guacamole + TigerVNC on port 8080, same infrastructure as the
 * Ubuntu MATE desktop (GuacamolePlugin). Desktop environment: MATE.
 */
public class KaliPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "kali";
    }

    @Override
    public String displayName() {
        return "Kali Linux Desktop";
    }

    @Override
    public String description() {
        return "Browser-based Kali Linux desktop for security testing and vulnerability research. Includes network scanning, penetration testing, and traffic analysis tools.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/kali-desktop:0.1.5-2606251032";
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
            "HOME", "/root",
            "DISPLAY", ":1"
        );
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
        // readOnlyRootFilesystem=false so no extra emptyDirs needed.
        return Collections.emptyList();
    }

    @Override
    public String userDataMountPath() {
        // Per-user persistent PVC mounted at /root/data.
        // Avoids overwriting root's dotfiles while still providing persistent storage.
        return "/root/data";
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        // Desktop environment and security tools write to /var/log, /run, /tmp, etc.
        return false;
    }

    @Override
    public Long runAsUser() {
        // Run as root — security tools (nmap, tcpdump, etc.) require it.
        return null;
    }

    @Override
    public boolean runAsNonRoot() {
        return false;
    }

    @Override
    public String seccompProfileType() {
        // Unconfined: same reason as GuacamolePlugin (GLib 2.78+ close_range() syscall),
        // plus security tools use additional syscalls blocked by the default profile.
        return "Unconfined";
    }

    @Override
    public List<String> capabilities() {
        // NET_RAW: raw socket access for nmap, tcpdump, ping, etc.
        // NET_ADMIN: network interface configuration for wireless tools, etc.
        return List.of("NET_RAW", "NET_ADMIN");
    }

    @Override
    public String readinessProbePath() {
        return "/";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 15;
    }

    @Override
    public int readinessProbePeriod() {
        return 2;
    }
}
