package com.scivicslab.k8spups.plugin;

import java.util.Map;

/**
 * Named resource profile for a tool plugin.
 * Each profile defines CPU/memory requests/limits and persistent storage size.
 */
public record ResourceProfile(
    String name,
    String displayName,
    Map<String, String> requests,
    Map<String, String> limits,
    String storageSize
) {
    /**
     * Backward-compatible constructor (defaults to 20Gi storage).
     */
    public ResourceProfile(String name, String displayName,
                           Map<String, String> requests, Map<String, String> limits) {
        this(name, displayName, requests, limits, "20Gi");
    }
}
