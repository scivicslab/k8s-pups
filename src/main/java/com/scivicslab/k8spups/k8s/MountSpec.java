package com.scivicslab.k8spups.k8s;

/**
 * Specifies an additional volume mount for a session Pod.
 * The primary mount (at ToolPlugin.userDataMountPath()) is handled separately;
 * this record describes secondary mounts added by the user or by the controller.
 *
 * @param storageType  storage type key: "lustre-csi", "longhorn", "nfs-k8s", "nfs-k8s-shared"; "dataset" and "dataset-root"
 *                     are added by the controller only
 * @param mountPath    absolute path inside the container (e.g. "/data")
 * @param sharedFrom   source userId for shared NFS mounts; null for own PVCs
 * @param subPath      directory of the volume to mount instead of the whole volume; null = the whole volume
 */
public record MountSpec(
    String storageType,
    String mountPath,
    String sharedFrom,
    String subPath
) {
    public MountSpec(String storageType, String mountPath, String sharedFrom) {
        this(storageType, mountPath, sharedFrom, null);
    }
}
