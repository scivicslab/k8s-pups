package com.scivicslab.k8spups.settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the shared settings onto the environment variables of each tool that talks to the GPU broker.
 * Pure logic: an empty setting yields no entry, so the tool keeps the deployment's own value.
 */
public final class SettingsEnv {

    private SettingsEnv() {}

    /** Environment variables that override the plugin's own for {@code toolName}. */
    public static Map<String, String> forTool(String toolName, SystemSettings s) {
        Map<String, String> env = new LinkedHashMap<>();
        String url = s.gpuBrokerUrl();
        String model = s.defaultModel();
        switch (toolName) {
            case "ai-chat" -> {
                put(env, "AI_CHAT_BASE_URL", url.isEmpty() ? "" : url + "/v1");
                put(env, "AI_CHAT_DEFAULT_MODEL", model);
            }
            case "jupyter-ai" -> {
                put(env, "JUPYTER_AI_BASE_URL", url.isEmpty() ? "" : url + "/v1");
                put(env, "JUPYTER_AI_MODEL", model);
            }
            case "chat-ui-with-audit-trail-local-llm" -> {
                put(env, "CHAT_UI_SERVERS", url);
                put(env, "CHAT_UI_DEFAULT_MODEL", model);
            }
            case "code-raptor" -> {
                put(env, "CODE_RAPTOR_BROKER_URL", url);
                put(env, "CODE_RAPTOR_MODEL", model);
            }
            default -> { }
        }
        return env;
    }

    private static void put(Map<String, String> env, String key, String value) {
        if (value != null && !value.isEmpty()) env.put(key, value);
    }
}
