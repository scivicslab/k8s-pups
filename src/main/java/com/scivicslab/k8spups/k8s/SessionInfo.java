package com.scivicslab.k8spups.k8s;

import com.scivicslab.k8spups.plugin.ToolPlugin;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * All information needed to create a user Pod.
 */
public record SessionInfo(
    String sessionId,
    String userId,
    ToolPlugin toolPlugin,
    List<String> allowedProjects,
    String labId,
    String resourceProfile,
    Map<String, String> userParams,
    String userStoragePreference,
    WorkspaceInfo workspaceInfo
) {
    /**
     * Backward-compatible constructor (no workspace info).
     */
    public SessionInfo(String sessionId, String userId, ToolPlugin toolPlugin,
                       List<String> allowedProjects, String labId, String resourceProfile,
                       Map<String, String> userParams, String userStoragePreference) {
        this(sessionId, userId, toolPlugin, allowedProjects, labId, resourceProfile,
            userParams, userStoragePreference, null);
    }

    /**
     * Backward-compatible constructor (no user parameters, no storage preference).
     */
    public SessionInfo(String sessionId, String userId, ToolPlugin toolPlugin,
                       List<String> allowedProjects, String labId, String resourceProfile) {
        this(sessionId, userId, toolPlugin, allowedProjects, labId, resourceProfile,
            Collections.emptyMap(), null, null);
    }

    public String podName() {
        return "pups-" + toolPlugin.name() + "-" + sessionId;
    }

    public String serviceName() {
        return "pups-svc-" + sessionId;
    }
}
