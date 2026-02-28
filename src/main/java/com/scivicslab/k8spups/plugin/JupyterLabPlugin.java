package com.scivicslab.k8spups.plugin;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tool plugin for JupyterLab interactive computing environment.
 *
 * Uses the Jupyter scipy-notebook image which includes:
 *   - JupyterLab
 *   - Python scientific stack (NumPy, Pandas, Matplotlib, SciPy, scikit-learn)
 *   - Runs as user jovyan (UID 1000)
 *
 * Token authentication is disabled; access is controlled by the
 * Envoy Gateway SecurityPolicy (Keycloak OIDC + JWT claim check).
 *
 * JupyterLab listens on port 8888.
 */
public class JupyterLabPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "jupyter-lab";
    }

    @Override
    public String displayName() {
        return "Jupyter Lab";
    }

    @Override
    public String description() {
        return "Interactive notebooks for data analysis, visualization, and scientific computing with Python (NumPy, Pandas, Matplotlib and more).";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/jupyter-lab:4.5.5-2602281600";
    }

    @Override
    public int containerPort() {
        return 8888;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public Map<String, String> environmentVariables() {
        return Map.of(
            // Disable Jupyter's built-in token/password auth.
            // Access is protected by Envoy Gateway SecurityPolicy (OIDC).
            "JUPYTER_TOKEN", "",
            "JUPYTER_ENABLE_LAB", "yes",
            "HOME", "/home/jovyan",
            // Runtime files go to /tmp (emptyDir) since root filesystem is read-only.
            "JUPYTER_RUNTIME_DIR", "/tmp/jupyter-runtime"
        );
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "500m", "memory", "2Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "4", "memory", "8Gi");
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(
            new ResourceProfile("light", "Light (4 CPU / 4 GB)",
                Map.of("cpu", "250m", "memory", "512Mi"),
                Map.of("cpu", "4", "memory", "4Gi")),
            new ResourceProfile("standard", "Standard (8 CPU / 8 GB)",
                Map.of("cpu", "500m", "memory", "1Gi"),
                Map.of("cpu", "8", "memory", "8Gi"))
        );
    }

    @Override
    public List<String> writablePaths() {
        // /home/jovyan is mounted as a PVC via userDataMountPath(); no additional emptyDirs needed.
        return Collections.emptyList();
    }

    @Override
    public String userDataMountPath() {
        // Mount the per-user PVC at /home/jovyan so notebooks and settings persist.
        return "/home/jovyan";
    }

    @Override
    public boolean workspaceEnabled() {
        // Mount user's NFS $HOME at /home/jovyan when POSIX account exists.
        return true;
    }

    @Override
    public boolean passthroughPath() {
        // JupyterLab generates absolute URLs based on its base_url.
        // Pass /session/{id}/* through without rewriting so assets resolve correctly.
        // entrypoint.sh reads PUPS_SESSION_PATH to set --ServerApp.base_url.
        return true;
    }
}
