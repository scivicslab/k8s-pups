package com.scivicslab.k8spups.plugin;

import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool plugin for AI Chat: a plain chat screen for the models of an OpenAI-compatible server (the GPU broker).
 *
 * <p>The screen is a Gradio application that knows its base path: it is given the session path in
 * {@code PUPS_SESSION_PATH} and builds every URL under it, so it works under the session path that the gateway
 * serves. (Open WebUI cannot: its pages request {@code /_app/...} from the host root.) Each exchange is appended to
 * a transcript file under {@code ~/works/chat-history} in the user's own storage. The server address and the default
 * model are deployment config ({@code K8SPUPS_AI_CHAT_*}), so the public source carries no internal address.
 */
public class AiChatPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "ai-chat";
    }

    @Override
    public String displayName() {
        return "AI Chat";
    }

    @Override
    public String description() {
        return "Chat with the AI models of this system. Your conversations are saved as files in your own storage.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/ai-chat:1.0.0-2610190654";
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
        env.put("AI_CHAT_BASE_URL", cfg.getOptionalValue("k8spups.ai-chat.base-url", String.class)
            .orElse("http://gpu-broker:28005/v1"));
        cfg.getOptionalValue("k8spups.ai-chat.default-model", String.class)
            .filter(m -> !m.isBlank())
            .ifPresent(m -> env.put("AI_CHAT_DEFAULT_MODEL", m));
        return env;
    }

    @Override
    public String readinessProbePath() {
        return "/config";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 10;
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "100m", "memory", "256Mi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "1", "memory", "1Gi");
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
