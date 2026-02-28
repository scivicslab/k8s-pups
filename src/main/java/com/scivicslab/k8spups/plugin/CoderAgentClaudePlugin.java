package com.scivicslab.k8spups.plugin;

import java.util.List;
import java.util.Map;

public class CoderAgentClaudePlugin implements ToolPlugin {

    @Override
    public String name() {
        return "coder-agent-claude";
    }

    @Override
    public String displayName() {
        return "Coding Agent (Claude)";
    }

    @Override
    public String description() {
        return "AI-powered coding assistant using Claude Code.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/quarkus-coder-agent-claude:1.0.2-2602281625";
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
            "CODER_AGENT_MODE", "claude",
            "HOME", "/home/user",
            "CODER_AGENT_LLM_WORKING_DIR", "/home/user",
            "CODER_AGENT_TITLE", "Coding Agent (Claude)"
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
            new UserParameter("ANTHROPIC_API_KEY", "Anthropic API Key",
                "sk-ant-... (optional if OIDC authenticated)", true, false)
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
