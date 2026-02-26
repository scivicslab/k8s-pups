package com.scivicslab.k8spups.plugin;

import java.util.Map;

/**
 * Named resource profile for a tool plugin.
 * Each profile defines CPU/memory requests and limits.
 */
public record ResourceProfile(
    String name,
    String displayName,
    Map<String, String> requests,
    Map<String, String> limits
) {}
