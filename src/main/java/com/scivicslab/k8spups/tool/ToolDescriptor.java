package com.scivicslab.k8spups.tool;

import java.util.List;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ToolDescriptor(
    String name,
    String description,
    String version,
    List<ParameterSpec> parameters
) {
    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        if (parameters == null) {
            parameters = List.of();
        }
    }

    public ParameterSpec getParameter(String paramName) {
        return parameters.stream()
            .filter(p -> p.name().equals(paramName))
            .findFirst()
            .orElse(null);
    }

    public List<ParameterSpec> getRequiredParameters() {
        return parameters.stream()
            .filter(ParameterSpec::required)
            .toList();
    }

    public List<ParameterSpec> getSecretParameters() {
        return parameters.stream()
            .filter(ParameterSpec::secret)
            .toList();
    }
}
