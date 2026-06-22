package com.scivicslab.k8spups.tool;

import java.util.List;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ToolRegistry(
    List<ToolRegistryEntry> tools
) {
    public ToolRegistry {
        if (tools == null) {
            tools = List.of();
        }
    }

    public ToolRegistryEntry getTool(String name) {
        return tools.stream()
            .filter(t -> t.name().equals(name))
            .findFirst()
            .orElse(null);
    }

    public List<ToolRegistryEntry> getToolsByRole(String role) {
        return tools.stream()
            .filter(t -> t.roles().contains(role))
            .toList();
    }
}
