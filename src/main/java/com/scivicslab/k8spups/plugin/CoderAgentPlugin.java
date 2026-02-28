package com.scivicslab.k8spups.plugin;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class CoderAgentPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "coder-agent";
    }

    @Override
    public String displayName() {
        return "Coding Agent (Local LLM)";
    }

    @Override
    public String description() {
        return "AI-powered coding assistant using local vLLM servers.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/quarkus-coder-agent:1.0.2-2602281625";
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
            "CODER_AGENT_MODE", "local-llm",
            "CODER_AGENT_LLM_SERVERS", "http://192.168.5.15:8000,http://192.168.5.13:8000",
            "HOME", "/home/user",
            "CODER_AGENT_LLM_WORKING_DIR", "/home/user"
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
    public List<String> writablePaths() {
        return Collections.emptyList();
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
