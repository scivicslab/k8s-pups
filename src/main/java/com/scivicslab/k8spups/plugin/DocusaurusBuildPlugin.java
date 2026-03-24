package com.scivicslab.k8spups.plugin;

import java.util.List;
import java.util.Map;

/**
 * Docusaurus build plugin.
 * Builds a Docusaurus project from the user's ~/works directory and copies
 * the output to the shared NFS docusaurus-sites directory for static serving.
 *
 * After build completes, a simple status page is served so the session
 * shows as READY on the dashboard.
 */
public class DocusaurusBuildPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "docusaurus-build";
    }

    @Override
    public String displayName() {
        return "Docusaurus Build";
    }

    @Override
    public String description() {
        return "Build a Docusaurus site and publish to the docs server.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/docusaurus-builder:0.2.0-2603131217";
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
        return Map.of("cpu", "500m", "memory", "1Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "2", "memory", "4Gi");
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(
            new ResourceProfile("standard", "Standard (2 CPU / 4 GB)",
                Map.of("cpu", "500m", "memory", "1Gi"),
                Map.of("cpu", "2", "memory", "4Gi"))
        );
    }

    @Override
    public String userDataMountPath() {
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
        return "works";
    }

    @Override
    public boolean passthroughPath() {
        return false;
    }

    @Override
    public List<UserParameter> userParameters() {
        return List.of(
            new UserParameter("DOCUSAURUS_PATH", "Project path (relative to ~/works)",
                "doc_SCIVICS003", false, true),
            new UserParameter("INDEX_MODE", "Index mode: none / update / full",
                "none", false, true)
        );
    }

    @Override
    public List<NfsVolumeSpec> nfsVolumes() {
        return List.of(
            new NfsVolumeSpec("192.168.5.20", "/Public/docusaurus-sites", "/output", false)
        );
    }

    @Override
    public String readinessProbePath() {
        return "/";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 30;
    }

    @Override
    public int readinessProbePeriod() {
        return 10;
    }

    @Override
    public boolean singleInstance() {
        return true;
    }

    @Override
    public boolean batchMode() {
        return true;
    }
}
