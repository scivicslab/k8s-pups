package com.scivicslab.k8spups.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ParameterSpec(
    String name,
    String description,
    @JsonProperty("default") String defaultValue,
    boolean required,
    boolean secret
) {
    public ParameterSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Parameter name must not be blank");
        }
    }

    public static ParameterSpec of(String name, String description, boolean required, boolean secret) {
        return new ParameterSpec(name, description, null, required, secret);
    }

    public static ParameterSpec of(String name, String description, String defaultValue, boolean required, boolean secret) {
        return new ParameterSpec(name, description, defaultValue, required, secret);
    }
}
