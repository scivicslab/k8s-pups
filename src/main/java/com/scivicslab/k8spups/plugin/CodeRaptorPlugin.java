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
 * <p>The Pod also runs the code editor (code-server). nginx in the Pod serves it at {@code editor/} under the session
 * path, next to code-raptor, so the links of the search results open the editor of the same session; the controller
 * relays the editor's WebSocket.
 */
public class CodeRaptorPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "code-raptor";
    }

    @Override
    public String displayName() {
        return "Program Search & Edit";
    }

    @Override
    public String description() {
        return "Search your programs (symbols, full text and Javadoc) and open the results in a code editor, with an AI chat that answers from those searches.";
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
        return Map.of("cpu", "500m", "memory", "2Gi");
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
