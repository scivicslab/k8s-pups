package com.scivicslab.k8spups.plugin;

import java.util.Map;

public class Exdb2Plugin implements ToolPlugin {

    @Override
    public String name() {
        return "quarkus-exdb2";
    }

    @Override
    public String displayName() {
        return "ExDB2";
    }

    @Override
    public String description() {
        return "Research document database with PDF ingestion, OCR, full-text search, and LLM-assisted expression management.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/quarkus-exdb2:1.6.1-2606300122";
    }

    @Override
    public int containerPort() {
        return 27999;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
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
    public String userDataMountPath() {
        return "/home/ubuntu";
    }

    @Override
    public boolean workspaceEnabled() {
        return true;
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        return false;
    }

    @Override
    public Long runAsUser() {
        return 1000L;
    }

    @Override
    public boolean runAsNonRoot() {
        return true;
    }

    @Override
    public Map<String, String> environmentVariables() {
        // Internal service endpoints are deployment config, not hardcoded here: the real URLs are
        // injected per deployment (K8SPUPS_EXDB2_* env, in the private overlays repo). Defaults are
        // neutral placeholders so the public source carries no internal addresses.
        var cfg = org.eclipse.microprofile.config.ConfigProvider.getConfig();
        return Map.of(
            "EXDB2_YOMITOKU_URL",
                cfg.getOptionalValue("k8spups.exdb2.yomitoku-url", String.class).orElse("http://yomitoku:8013"),
            "EXDB2_EMBEDDING_URL",
                cfg.getOptionalValue("k8spups.exdb2.embedding-url", String.class).orElse("http://embedding:8012")
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
}
