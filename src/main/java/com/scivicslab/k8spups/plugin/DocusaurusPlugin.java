package com.scivicslab.k8spups.plugin;

import java.util.List;
import java.util.Map;

/**
 * Docusaurus preview plugin.
 * Mounts the user's ~/works directory via NFS workspace and runs yarn start
 * on a specified Docusaurus project path.
 *
 * UID/GID are determined dynamically from LDAP (POSIX account).
 */
public class DocusaurusPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "docusaurus";
    }

    @Override
    public String displayName() {
        return "Docusaurus";
    }

    @Override
    public String description() {
        return "Preview Docusaurus sites from your ~/works directory.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/docusaurus-runner:0.1.3-2602281735";
    }

    @Override
    public int containerPort() {
        return 3000;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "100m", "memory", "256Mi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "1", "memory", "2Gi");
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(
            new ResourceProfile("standard", "Standard (1 CPU / 2 GB)",
                Map.of("cpu", "100m", "memory", "256Mi"),
                Map.of("cpu", "1", "memory", "2Gi"))
        );
    }

    @Override
    public String userDataMountPath() {
        // PVC fallback when no NFS workspace exists.
        return "/workspace";
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        return false;
    }

    @Override
    public boolean workspaceEnabled() {
        return true;
    }

    @Override
    public String workspaceMountPath() {
        return "/workspace";
    }

    @Override
    public String workspaceSubPath() {
        // Mount only ~/works from the user's NFS home directory.
        return "works";
    }

    @Override
    public boolean passthroughPath() {
        return true;
    }

    @Override
    public List<UserParameter> userParameters() {
        return List.of(
            new UserParameter("DOCUSAURUS_PATH", "Project path (relative to ~/works)",
                "doc_SCIVICS002", false, true)
        );
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
        return 5;
    }
}
