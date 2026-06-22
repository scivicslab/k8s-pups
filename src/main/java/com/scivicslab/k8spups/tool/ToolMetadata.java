package com.scivicslab.k8spups.tool;

import java.util.List;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ToolMetadata(
    String name,
    String image,
    boolean pulled,
    ToolDescriptor descriptor,
    List<String> configuredParameters
) {
    public static ToolMetadata unpulled(String name, String image) {
        return new ToolMetadata(name, image, false, null, List.of());
    }

    public static ToolMetadata pulled(String name, String image, ToolDescriptor descriptor) {
        return new ToolMetadata(name, image, true, descriptor, List.of());
    }

    public static ToolMetadata configured(String name, String image, ToolDescriptor descriptor, List<String> configuredParams) {
        return new ToolMetadata(name, image, true, descriptor, configuredParams);
    }

    public boolean hasAllRequiredParameters() {
        if (!pulled || descriptor == null) {
            return false;
        }
        return descriptor.getRequiredParameters().stream()
            .allMatch(p -> configuredParameters.contains(p.name()));
    }
}
