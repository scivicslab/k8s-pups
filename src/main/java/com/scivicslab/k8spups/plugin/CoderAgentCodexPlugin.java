package com.scivicslab.k8spups.plugin;

import java.util.List;
import java.util.Map;

public class CoderAgentCodexPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "coder-agent-codex";
    }

    @Override
    public String displayName() {
        return "Coding Agent (Codex)";
    }

    @Override
    public String description() {
        return "AI-powered coding assistant using OpenAI Codex.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/quarkus-coder-agent-codex:1.0.1-2603041512";
    }

    @Override
    public int containerPort() {
        return 8090;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public Map<String, String> environmentVariables() {
        return Map.of(
            "CODER_AGENT_MODE", "codex",
            "HOME", "/home/user",
            "CODER_AGENT_LLM_WORKING_DIR", "/home/user",
            "CODER_AGENT_TITLE", "Coding Agent (Codex)"
        );
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "250m", "memory", "512Mi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "2", "memory", "8Gi");
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(
            new ResourceProfile("standard", "Standard (2 CPU / 8 GB)",
                Map.of("cpu", "250m", "memory", "512Mi"),
                Map.of("cpu", "2", "memory", "8Gi"))
        );
    }

    @Override
    public List<UserParameter> userParameters() {
        return List.of(
            new UserParameter("OPENAI_API_KEY", "OpenAI API Key",
                "sk-... (required)", true, false)
        );
    }

    @Override
    public String userDataMountPath() {
        return "/home/user";
    }

    @Override
    public boolean workspaceEnabled() {
        return true;
    }

    @Override
    public String readinessProbePath() {
        return null;
    }
}
