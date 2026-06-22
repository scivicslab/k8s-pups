package com.scivicslab.k8spups.k8s;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the env var list for a session Pod.
 * Pure logic — no CDI, no k8s API dependency.
 */
public class SessionEnvBuilder {

    private final String basePath;
    private final String controllerUrl;

    public SessionEnvBuilder(String basePath, String controllerUrl) {
        this.basePath = basePath;
        this.controllerUrl = controllerUrl;
    }

    /**
     * Build the env var list for a session Pod.
     *
     * Priority (highest to lowest):
     *   user-provided params (from session start form)
     *   tool registry config env (toolConfigEnv)
     *   plugin-defined env (plugin.environmentVariables())
     *   system vars (PUPS_SESSION_PATH, PUPS_SESSION_ID, etc.)
     *
     * Note: toolConfigEnv overrides plugin env via removeIf + re-add.
     */
    public List<EnvVar> build(SessionInfo info) {
        List<EnvVar> envVars = new ArrayList<>(
            info.toolPlugin().environmentVariables().entrySet().stream()
                .map(e -> new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build())
                .toList());

        envVars.add(new EnvVarBuilder()
            .withName("PUPS_SESSION_PATH")
            .withValue("/session/" + info.sessionId() + "/")
            .build());
        envVars.add(new EnvVarBuilder()
            .withName("PUPS_API_BASE")
            .withValue(basePath)
            .build());
        envVars.add(new EnvVarBuilder()
            .withName("PUPS_SESSION_ID")
            .withValue(info.sessionId())
            .build());
        envVars.add(new EnvVarBuilder()
            .withName("PUPS_CONTROLLER_URL")
            .withValue(controllerUrl)
            .build());

        // tool registry config overrides plugin-defined env
        if (info.toolConfigEnv() != null) {
            for (Map.Entry<String, String> entry : info.toolConfigEnv().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    envVars.removeIf(e -> e.getName().equals(entry.getKey()));
                    envVars.add(new EnvVarBuilder()
                        .withName(entry.getKey())
                        .withValue(entry.getValue())
                        .build());
                }
            }
        }

        // user-provided parameters (from session start form)
        if (info.userParams() != null) {
            for (Map.Entry<String, String> entry : info.userParams().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    envVars.add(new EnvVarBuilder()
                        .withName(entry.getKey())
                        .withValue(entry.getValue())
                        .build());
                }
            }
        }

        return envVars;
    }
}
