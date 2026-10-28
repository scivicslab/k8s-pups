package com.scivicslab.k8spups.k8s;

/**
 * The user ID and group ID that a tool Pod runs as.
 *
 * <p>Each tool plugin names the user ID its container image was built for ({@code ToolPlugin.runAsUser()},
 * usually 1000). A cluster can replace that number with a user ID and group ID of its own
 * ({@code k8spups.tool-pod.uid} and {@code k8spups.tool-pod.gid}). This is needed where the storage of the
 * home PVCs records owners by number and those numbers are also accounts of the physical computers: on the
 * k0s cluster of the personal genome section, 1000 is an existing account of the physical computers, so the
 * tool Pods run as numbers issued for k8s-pups instead. k8s-pups does not look up those accounts; the numbers
 * come only from the configuration. The StorageClass of the home PVCs must
 * create the PV directory owned by the same numbers.
 *
 * <p>A plugin that returns {@code null} (run as the image default, for tools that manage services as
 * root) keeps {@code null}: the replacement applies only to tools that run as a fixed ordinary user ID.
 *
 * @param uid the user ID, or {@code null} for the image default
 * @param gid the group ID, or {@code null} for the image default
 */
public record ToolPodIds(Long uid, Long gid) {

    /**
     * @param pluginUid   the user ID the plugin names, or {@code null}
     * @param overrideUid the user ID configured for the cluster, or {@code null} when not configured
     * @param overrideGid the group ID configured for the cluster, or {@code null} when not configured
     * @throws IllegalArgumentException when only one of the two configured numbers is given
     */
    public static ToolPodIds resolve(Long pluginUid, Long overrideUid, Long overrideGid) {
        if ((overrideUid == null) != (overrideGid == null)) {
            throw new IllegalArgumentException(
                "k8spups.tool-pod.uid and k8spups.tool-pod.gid must be set together (uid=" + overrideUid
                    + ", gid=" + overrideGid + ")");
        }
        if (pluginUid == null) {
            return new ToolPodIds(null, null);
        }
        if (overrideUid == null) {
            return new ToolPodIds(pluginUid, pluginUid);
        }
        return new ToolPodIds(overrideUid, overrideGid);
    }
}
