package com.scivicslab.k8spups.plugin;

import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool plugin for JupyterLab with the Jupyter AI assistant (chat panel and magics).
 *
 * <p>The image is the light notebook environment (Python scientific stack and jupyter-ai, no R, Julia or
 * Java kernels). The assistant talks to an OpenAI-compatible server, normally the GPU broker; its address
 * and the default model are deployment config ({@code K8SPUPS_JUPYTER_AI_*}), so the public source carries
 * no internal address.
 *
 * <p>Like {@link JupyterLabPlugin}, the Pod serves under its session path and mounts the user's home.
 */
public class JupyterAiPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "jupyter-ai";
    }

    @Override
    public String displayName() {
        return "Jupyter AI";
    }

    @Override
    public String description() {
        return "Interactive notebooks with an AI assistant: chat about your code and data, and use AI magics in cells.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/jupyter-ai:1.0.0-2610190508";
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
        var cfg = ConfigProvider.getConfig();
        var env = new LinkedHashMap<String, String>(Map.of(
            // Access is protected by the Envoy Gateway SecurityPolicy (OIDC), not by a Jupyter token.
            "JUPYTER_TOKEN", "",
            "JUPYTER_ENABLE_LAB", "yes",
            "HOME", "/home/jovyan",
            "JUPYTER_RUNTIME_DIR", "/tmp/jupyter-runtime",
            "JUPYTER_AI_BASE_URL",
                cfg.getOptionalValue("k8spups.jupyter-ai.base-url", String.class)
                    .orElse("http://gpu-broker:28005/v1")
        ));
        // The default model of the assistant. Left unset, the user picks a model in the chat settings.
        cfg.getOptionalValue("k8spups.jupyter-ai.model", String.class)
            .filter(m -> !m.isBlank())
            .ifPresent(m -> env.put("JUPYTER_AI_MODEL", m));
        return env;
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "500m", "memory", "1Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "4", "memory", "8Gi");
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(
            new ResourceProfile("light", "Light (4 CPU / 8 GB)", resourceRequests(), resourceLimits()),
            new ResourceProfile("standard", "Standard (8 CPU / 32 GB)",
                Map.of("cpu", "500m", "memory", "1Gi"),
                Map.of("cpu", "8", "memory", "32Gi"))
        );
    }

    @Override
    public List<String> writablePaths() {
        // /home/jovyan is mounted from the user's storage; no additional emptyDirs are needed.
        return Collections.emptyList();
    }

    @Override
    public String userDataMountPath() {
        return "/home/jovyan";
    }

    @Override
    public String extraMountsUnder() {
        // The screen of this tool shows the home only: the extra mounts are found there.
        return userDataMountPath();
    }

    @Override
    public String readinessProbePath() {
        // JupyterLab serves under its session path (base_url); the session is ready, and the dashboard shows "Open",
        // only when its API answers there.
        return SESSION_PATH_PLACEHOLDER + "/api";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 3;
    }

    @Override
    public boolean passthroughPath() {
        // JupyterLab builds absolute URLs from its base_url; entrypoint.sh sets it from PUPS_SESSION_PATH.
        return true;
    }
}
