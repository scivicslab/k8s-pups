package com.scivicslab.k8spups.plugin;

import java.util.List;
import java.util.Map;

/**
 * Tool plugin for the Storage Settings web UI.
 * Launches a lightweight Pod that serves a full-page storage configuration interface.
 * The UI communicates with the k8s-pups controller API to read/write storage preferences.
 */
public class StorageSettingsPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "storage-settings";
    }

    @Override
    public String displayName() {
        return "Storage Settings";
    }

    @Override
    public String description() {
        return "Configure your personal storage: Longhorn (fast block), NFS (shared), and S3 object storage.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/quarkus-storage-settings:1.0.0-2604210045";
    }

    @Override
    public int containerPort() {
        return 8090;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "50m", "memory", "128Mi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "500m", "memory", "512Mi");
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(
            new ResourceProfile("default", "Default",
                Map.of("cpu", "50m", "memory", "128Mi"),
                Map.of("cpu", "500m", "memory", "512Mi"))
        );
    }

    @Override
    public String readinessProbePath() {
        return "/health";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 5;
    }

    @Override
    public int readinessProbePeriod() {
        return 3;
    }

    @Override
    public boolean singleInstance() {
        return true;
    }
}
