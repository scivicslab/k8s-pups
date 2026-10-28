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
    String userStorageType,
    List<MountSpec> additionalMounts,
    Map<String, String> toolConfigEnv
) {
    /**
     * Constructor without additional mounts (backward-compatible).
     */
    public SessionInfo(String sessionId, String userId, ToolPlugin toolPlugin,
                       List<String> allowedProjects, String labId, String resourceProfile,
                       Map<String, String> userParams, String userStoragePreference,
                       String userStorageType) {
        this(sessionId, userId, toolPlugin, allowedProjects, labId, resourceProfile,
            userParams, userStoragePreference, userStorageType,
            Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * Constructor without additional mounts, with toolConfigEnv.
     */
    public SessionInfo(String sessionId, String userId, ToolPlugin toolPlugin,
                       List<String> allowedProjects, String labId, String resourceProfile,
                       Map<String, String> userParams, String userStoragePreference,
                       String userStorageType, List<MountSpec> additionalMounts) {
        this(sessionId, userId, toolPlugin, allowedProjects, labId, resourceProfile,
            userParams, userStoragePreference, userStorageType,
            additionalMounts, Collections.emptyMap());
    }

    /**
     * Backward-compatible constructor (no storage type).
     */
    public SessionInfo(String sessionId, String userId, ToolPlugin toolPlugin,
                       List<String> allowedProjects, String labId, String resourceProfile,
                       Map<String, String> userParams, String userStoragePreference) {
        this(sessionId, userId, toolPlugin, allowedProjects, labId, resourceProfile,
            userParams, userStoragePreference, null,
            Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * Backward-compatible constructor (no user parameters, no storage preference).
     */
    public SessionInfo(String sessionId, String userId, ToolPlugin toolPlugin,
                       List<String> allowedProjects, String labId, String resourceProfile) {
        this(sessionId, userId, toolPlugin, allowedProjects, labId, resourceProfile,
            Collections.emptyMap(), null, null,
            Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * Returns a copy with the storage type resolved. Session resolves the
     * effective type once (user preference, else the configured default) and writes
     * it back here, so Pod building reads the same value instead of resolving again.
     */
    public SessionInfo withUserStorageType(String resolvedStorageType) {
        return new SessionInfo(sessionId, userId, toolPlugin, allowedProjects, labId,
            resourceProfile, userParams, userStoragePreference, resolvedStorageType,
            additionalMounts, toolConfigEnv);
    }

    public String podName() {
        return "pups-" + toolPlugin.name() + "-" + sessionId;
    }

    public String serviceName() {
        return "pups-svc-" + sessionId;
    }
}
