package com.scivicslab.k8spups.plugin;

import java.util.List;
import java.util.Map;

/**
 * Defines a sidecar container configuration for workspace mode.
 *
 * When workspace is active, the plugin splits the Pod into two containers:
 * - "tool": runs the service exposed on containerPort (e.g., Guacamole gateway).
 *   Uses the plugin's default UID and lightweight resources.
 * - "desktop": runs the user's workspace (e.g., VNC + MATE desktop).
 *   Runs as the LDAP UID with NFS mounted and gets the user-selected profile resources.
 *
 * Both containers share the same image and localhost network namespace.
 */
public record SidecarSpec(
    /** Command override for the "tool" (service) container. */
    List<String> toolCommand,

    /** Resource requests for the "tool" container (lightweight gateway). */
    Map<String, String> toolResourceRequests,

    /** Resource limits for the "tool" container (lightweight gateway). */
    Map<String, String> toolResourceLimits,

    /** Command for the "desktop" (workspace) container. */
    List<String> desktopCommand,

    /** Environment variables for the "desktop" container. */
    Map<String, String> desktopEnv
) {}
