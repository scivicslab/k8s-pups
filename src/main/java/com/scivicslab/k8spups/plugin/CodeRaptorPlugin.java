package com.scivicslab.k8spups.plugin;

import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool plugin for code-raptor: search over the user's {@code ~/works} (symbols, full text, Javadoc), with an AI
 * Chat tab that answers from those searches. The Pod gets its own copy of the index, written under the user's
 * storage. The model server of the AI Chat tab is deployment config ({@code K8SPUPS_CODE_RAPTOR_*}), so the
 * public source carries no internal address. The gateway removes the session path prefix; the portal uses
 * relative URLs, so it works under that prefix.
 *
 * <p>The links to the code editor (code-server on port 8080 of the host) point at a host that is not part of
 * the Pod, so they do not open here.
 */
public class CodeRaptorPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "code-raptor";
    }

    @Override
    public String displayName() {
        return "Code Raptor";
    }

    @Override
    public String description() {
        return "Search your projects: symbols, full text and Javadoc, with an AI chat that answers from those searches.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/code-raptor:1.2.0-2610190616";
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
        var cfg = ConfigProvider.getConfig();
        var env = new LinkedHashMap<String, String>();
        env.put("HOME", "/home/devteam");
        cfg.getOptionalValue("k8spups.code-raptor.broker-url", String.class)
            .filter(v -> !v.isBlank()).ifPresent(v -> env.put("CODE_RAPTOR_BROKER_URL", v));
        cfg.getOptionalValue("k8spups.code-raptor.model", String.class)
            .filter(v -> !v.isBlank()).ifPresent(v -> env.put("CODE_RAPTOR_MODEL", v));
        return env;
    }

    @Override
    public String readinessProbePath() {
        return "/q/health/ready";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 15;
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "250m", "memory", "1Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "2", "memory", "4Gi");
    }

    @Override
    public List<String> writablePaths() {
        return Collections.emptyList();
    }

    @Override
    public String userDataMountPath() {
        return "/home/devteam/works";
    }

    @Override
    public boolean workspaceEnabled() {
        return true;
    }

    @Override
    public String workspaceMountPath() {
        return "/home/devteam/works";
    }

    @Override
    public String workspaceSubPath() {
        return "works";
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
}
