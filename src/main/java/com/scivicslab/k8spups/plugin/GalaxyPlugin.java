package com.scivicslab.k8spups.plugin;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tool plugin for Galaxy, the web workbench for running bioinformatics tools and workflows.
 *
 * <p>The image carries a curated set of tools and their conda environments, installed at build time,
 * because the tool Pods have no internet access. Galaxy runs as a single user: the user who started the
 * session (see {@code entrypoint.sh}), and that user is the administrator of their own Galaxy. Their
 * histories, datasets and database live on the user's storage, mounted at
 * {@code /galaxy/server/database}.
 *
 * <p>Like {@link JupyterLabPlugin}, the Pod serves under its session path.
 */
public class GalaxyPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "galaxy";
    }

    @Override
    public String displayName() {
        return "Galaxy";
    }

    @Override
    public String description() {
        return "Web workbench for bioinformatics: run quality control, mapping and variant calling tools and chain them into workflows.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/galaxy-bio:26.1.2-2610300545";
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
            // Gravity keeps its state under HOME; the root filesystem is writable but HOME must exist.
            "HOME", "/tmp"
        );
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "1", "memory", "4Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "8", "memory", "16Gi");
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(
            new ResourceProfile("standard", "Standard (8 CPU / 16 GB)", resourceRequests(), resourceLimits()),
            new ResourceProfile("large", "Large (16 CPU / 64 GB)",
                Map.of("cpu", "2", "memory", "8Gi"),
                Map.of("cpu", "16", "memory", "64Gi"))
        );
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        // Galaxy writes its generated configuration and temporary files next to the installed config.
        return false;
    }

    @Override
    public List<String> writablePaths() {
        return Collections.emptyList();
    }

    @Override
    public String userDataMountPath() {
        return "/galaxy/server/database";
    }

    @Override
    public String userDataSubPath() {
        // The database, jobs and datasets of Galaxy stay in a directory of their own, out of the home of the other tools.
        return "galaxy";
    }

    @Override
    public boolean passthroughPath() {
        // Galaxy builds absolute URLs from its URL prefix; entrypoint.sh sets it from PUPS_SESSION_PATH.
        return true;
    }

    @Override
    public String readinessProbePath() {
        // Galaxy needs about two minutes to start. The session is ready, and the dashboard shows "Open",
        // only when the API answers under the session path.
        return SESSION_PATH_PLACEHOLDER + "/api/version";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 45;
    }

    @Override
    public int readinessProbePeriod() {
        return 5;
    }

    @Override
    public boolean singleInstance() {
        return true;
    }
}
