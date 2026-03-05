package com.scivicslab.k8spups.actor;

/**
 * Immutable snapshot of a session's current state, returned to REST API callers.
 */
public record SessionStatus(
    String sessionId,
    String userId,
    String toolName,
    SessionState state,
    String podName,
    String accessUrl,
    String memo,
    String storageType
) {
    /**
     * Backward-compatible constructor (no storageType).
     */
    public SessionStatus(String sessionId, String userId, String toolName,
                         SessionState state, String podName, String accessUrl, String memo) {
        this(sessionId, userId, toolName, state, podName, accessUrl, memo, null);
    }
}
