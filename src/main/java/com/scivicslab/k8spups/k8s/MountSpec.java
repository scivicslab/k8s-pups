package com.scivicslab.k8spups.k8s;

/**
 * Specifies an additional volume mount for a session Pod.
 * The primary mount (at ToolPlugin.userDataMountPath()) is handled separately;
 * this record describes the secondary mounts the user chose at launch. The controller checks each against the
 * user's allocations, dataset grants and group memberships before the Pod is created.
 *
 * @param storageType  storage type key: "posix", "longhorn" (the user's own storage),
 *                     "group" (a group's storage), "dataset-root" (one dataset), "dataset" (the files granted one by one)
 * @param mountPath    absolute path inside the container (e.g. "/data")
 * @param sharedFrom   the group name for "group"; null otherwise
 * @param subPath      directory of the volume to mount instead of the whole volume; null = the whole volume
 * @param readOnly     mount read-only (datasets always are; a group member may be read-only)
 */
public record MountSpec(
    String storageType,
    String mountPath,
    String sharedFrom,
    String subPath,
    boolean readOnly
) {
    public MountSpec(String storageType, String mountPath, String sharedFrom, String subPath) {
        this(storageType, mountPath, sharedFrom, subPath, false);
    }

    public MountSpec(String storageType, String mountPath, String sharedFrom) {
        this(storageType, mountPath, sharedFrom, null, false);
    }
}
