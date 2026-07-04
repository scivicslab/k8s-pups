package com.scivicslab.k8spups.plugin;

import java.util.Map;

public class ComplianceTracerPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "compliance-tracer";
    }

    @Override
    public String displayName() {
        return "Compliance Tracer";
    }

    @Override
    public String description() {
        return "Document compliance analysis with RAG-based search over PDF corpus and Wikipedia.";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/compliance-tracer:1.1.1-2606300135";
    }

    @Override
    public int containerPort() {
        return 27899;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public String readinessProbePath() {
        return "/";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 15;
    }

    @Override
    public String userDataMountPath() {
        return "/home/ubuntu";
    }

    @Override
    public boolean workspaceEnabled() {
        return true;
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

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "500m", "memory", "2Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "4", "memory", "8Gi");
    }
}
