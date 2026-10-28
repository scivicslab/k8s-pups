package com.scivicslab.k8spups.plugin;

import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool plugin for chat-ui-with-audit-trail in its "Local LLM only" start-up configuration
 * (ThreeStartupConfigurations_260912_oo01): the body with the web-tools and shell plugin jars, talking to the
 * GPU broker. It is the tile {@code chat-ui-with-audit-trail-local-llm} of AI Workspace, started as a tool of
 * its own: one Pod per user, every conversation recorded in the user's own storage.
 *
 * <p>The user's {@code ~/works} is the Pod's {@code /home/devteam/works}: the conversation reads and writes
 * there, and the H2 files of its records are created in the working directory, which is the same place. The
 * broker address is deployment config ({@code K8SPUPS_CHAT_UI_AUDIT_TRAIL_*}), so the public source carries no
 * internal address. The path prefix of the session is removed by the gateway, as for any tool that is not
 * a pass-through.
 */
public class ChatUiAuditTrailLocalLlmPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "chat-ui-with-audit-trail-local-llm";
    }

    @Override
    public String displayName() {
        return "Chat UI with Audit Trail (Local LLM)";
    }

    @Override
    public String description() {
        return "Conversations with the local LLM that are recorded turn by turn, with web search and shell tools, in your own storage.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/chat-ui-with-audit-trail:2.1.0-2610190609";
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
        env.put("CHAT_UI_SERVERS", cfg.getOptionalValue("k8spups.chat-ui-audit-trail.servers", String.class)
            .orElse("http://gpu-broker:28005"));
        cfg.getOptionalValue("k8spups.chat-ui-audit-trail.default-model", String.class)
            .filter(m -> !m.isBlank())
            .ifPresent(m -> env.put("CHAT_UI_DEFAULT_MODEL", m));
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
