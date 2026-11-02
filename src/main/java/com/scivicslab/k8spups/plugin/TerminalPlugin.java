package com.scivicslab.k8spups.plugin;

import org.eclipse.microprofile.config.ConfigProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool plugin for a terminal in the browser: an Ubuntu 24.04 shell (ttyd) on the user's {@code ~/works}, with the command
 * line AI agent Hermes Agent next to it. The user types {@code hermes}; the image points it at the local LLM of the
 * deployment (the GPU broker) through {@code HERMES_BASE_URL} and {@code HERMES_MODEL}, which are deployment config
 * ({@code K8SPUPS_TERMINAL_*}) or the shared settings ({@code SettingsEnv}), so the public source carries no internal address.
 *
 * <p>Access is decided by the controller (login and ownership of the session), so the terminal has no password of its own.
 * The gateway removes the session path prefix, and the controller relays the WebSocket of the terminal
 * ({@code SessionWebSocketProxy}).
 */
public class TerminalPlugin implements ToolPlugin {

    /** The directory the shell starts in, and where the user's storage is mounted. */
    static final String WORKSPACE = "/home/devteam/works";

    @Override
    public String name() {
        return "terminal";
    }

    @Override
    public String displayName() {
        return "Terminal";
    }

    @Override
    public String description() {
        return "An Ubuntu 24.04 terminal in the browser on your own storage, with the AI agent Hermes Agent (type hermes) on the local LLM.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/terminal:24.04-2611021956";
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
        cfg.getOptionalValue("k8spups.terminal.broker-url", String.class)
            .filter(v -> !v.isBlank()).ifPresent(v -> env.put("HERMES_BASE_URL", v.replaceAll("/+$", "") + "/v1"));
        cfg.getOptionalValue("k8spups.terminal.model", String.class)
            .filter(v -> !v.isBlank()).ifPresent(v -> env.put("HERMES_MODEL", v));
        return env;
    }

    @Override
    public String readinessProbePath() {
        return "/";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 3;
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
    public String userDataMountPath() {
        return WORKSPACE;
    }

    @Override
    public String extraMountsUnder() {
        // The shell starts in this directory: the datasets and the group storage are found under it.
        return WORKSPACE;
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
