package com.scivicslab.k8spups.plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool plugin for the AI workspace (quarkus-service-portal).
 *
 * One Pod = one AI workspace. The Pod bundles all tool JARs
 * (mcp-gateway, chat-ui, html-saurus, turing-workflow-editor)
 * and manages them as child processes via the portal itself.
 *
 * Multi-user / multi-workspace management is k8s-pups' responsibility.
 * The portal itself is single-user.
 */
public class ServicePortalPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "service-portal";
    }

    @Override
    public String displayName() {
        return "AI Workspace";
    }

    @Override
    public String description() {
        return "Integrated AI workspace: chat UI, MCP gateway, document viewer, and workflow editor.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/quarkus-service-portal:1.3.0-2604241525";
    }

    @Override
    public int containerPort() {
        return 28000;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        // Sub-process management (ProcessBuilder) writes to /tmp, /proc, etc.
        return false;
    }

    @Override
    public String userDataMountPath() {
        return "/home/devteam/works";
    }

    @Override
    public boolean workspaceEnabled() {
        // Mount user's NFS ~/works into the container.
        return true;
    }

    @Override
    public String workspaceMountPath() {
        return "/home/devteam/works";
    }

    @Override
    public String workspaceSubPath() {
        // Mount only the "works" sub-directory of the user's NFS home.
        return "works";
    }

    @Override
    public String readinessProbePath() {
        return "/api/status";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 15;
    }

    @Override
    public int readinessProbePeriod() {
        return 3;
    }

    @Override
    public boolean singleInstance() {
        // One workspace per user.
        return true;
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "250m", "memory", "1Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "4", "memory", "8Gi");
    }

    @Override
    public Map<String, String> environmentVariables() {
        Map<String, String> env = new HashMap<>();
        // Inject DEFAULT_PROVIDER so the chat-ui defaults to the correct LLM provider.
        // Falls back to "claude" if K8SPUPS_SERVICE_PORTAL_DEFAULT_PROVIDER is not set.
        String defaultProvider = System.getenv().getOrDefault("K8SPUPS_SERVICE_PORTAL_DEFAULT_PROVIDER", "claude");
        env.put("DEFAULT_PROVIDER", defaultProvider);
        // Inject VLLM_ENDPOINT when a local LLM endpoint is configured for this deployment.
        String vllmEndpoint = System.getenv("K8SPUPS_SERVICE_PORTAL_VLLM_ENDPOINT");
        if (vllmEndpoint != null && !vllmEndpoint.isBlank()) {
            env.put("VLLM_ENDPOINT", vllmEndpoint);
        }
        return env;
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(
            new ResourceProfile("light", "Light (4 CPU / 8 GB)",
                Map.of("cpu", "250m", "memory", "1Gi"),
                Map.of("cpu", "4",    "memory", "8Gi")),
            new ResourceProfile("standard", "Standard (8 CPU / 16 GB)",
                Map.of("cpu", "500m", "memory", "2Gi"),
                Map.of("cpu", "8",    "memory", "16Gi"))
        );
    }
}
