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
        return "LLM Coding Agent";
    }

    @Override
    public String icon() {
        return "🤖";
    }

    @Override
    public String description() {
        return "AI-powered coding assistant. Write, review, and debug code using natural language with Claude.";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/quarkus-coder-agent:1.0.1-2602260144";
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
            "CODER_AGENT_LLM_SERVERS", "http://192.168.5.15:8000,http://192.168.5.13:11434",
            "HOME", "/home/user",
            "CODER_AGENT_LLM_WORKING_DIR", "/home/user"
        );
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "1", "memory", "2Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "2", "memory", "8Gi");
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(
            new ResourceProfile("standard", "Standard (2 CPU / 8 GB / 100 GB)",
                Map.of("cpu", "1", "memory", "2Gi"),
                Map.of("cpu", "2", "memory", "8Gi"),
                "100Gi"),
            new ResourceProfile("large", "Large (2 CPU / 8 GB / 1 TB)",
                Map.of("cpu", "1", "memory", "2Gi"),
                Map.of("cpu", "2", "memory", "8Gi"),
                "1Ti")
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
    public String readinessProbePath() {
        return null;
    }
}
