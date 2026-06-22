package com.scivicslab.k8spups.tool;

import java.util.List;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ToolRegistryEntry(
    String name,
    String image,
    List<String> roles
) {
    public ToolRegistryEntry {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("Tool image must not be blank");
        }
        if (roles == null) {
            roles = List.of();
        }
    }

    public boolean isVisibleToRole(String role) {
        return roles.contains(role);
    }
}
