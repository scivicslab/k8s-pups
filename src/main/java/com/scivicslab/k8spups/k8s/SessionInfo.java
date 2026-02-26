package com.scivicslab.k8spups.k8s;

import com.scivicslab.k8spups.plugin.ToolPlugin;
import java.util.List;

/**
 * All information needed to create a user Pod.
 */
public record SessionInfo(
    String sessionId,
    String userId,
    ToolPlugin toolPlugin,
    List<String> allowedProjects,
    String labId,
    String resourceProfile
) {
    public String podName() {
        return "pups-" + toolPlugin.name() + "-" + sessionId;
    }

    public String serviceName() {
        return "pups-svc-" + sessionId;
    }
}
