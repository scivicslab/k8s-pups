package com.scivicslab.k8spups.k8s;

/**
 * Specifies an additional volume mount for a session Pod.
 * The primary mount (at ToolPlugin.userDataMountPath()) is handled separately;
 * this record describes secondary mounts added by the user.
 *
 * @param storageType  storage type key: "longhorn", "nfs-k8s", "nfs-k8s-shared"
 * @param mountPath    absolute path inside the container (e.g. "/data")
 * @param sharedFrom   source userId for shared NFS mounts; null for own PVCs
 */
public record MountSpec(
    String storageType,
    String mountPath,
    String sharedFrom
) {}
