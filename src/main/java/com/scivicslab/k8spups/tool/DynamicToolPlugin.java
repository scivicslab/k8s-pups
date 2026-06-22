package com.scivicslab.k8spups.tool;

import com.scivicslab.k8spups.plugin.ConnectionType;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ToolPlugin adapter wrapping a tool-registry entry.
 * Used when a dynamic tool (registered via YAML, not compiled in) is selected by a user.
 * Tool config env vars are injected separately via SessionInfo.toolConfigEnv().
 */
public class DynamicToolPlugin implements ToolPlugin {

    private final String name;
    private final String image;

    public DynamicToolPlugin(String name, String image) {
        this.name = name;
        this.image = image;
    }

    public static DynamicToolPlugin from(ToolRegistryEntry entry) {
        return new DynamicToolPlugin(entry.name(), entry.image());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String displayName() {
        String[] parts = name.replace('-', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    @Override
    public String containerImage() {
        return image;
    }

    @Override
    public int containerPort() {
        return 8080;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public Map<String, String> environmentVariables() {
        return Collections.emptyMap();
    }

    @Override
    public String userDataMountPath() {
        return "/data";
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        return false;
    }
}
