package com.scivicslab.k8spups.k8s;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Quantity;

/**
 * Builds the PVC of a user for the storage types that a StorageClass provisions
 * (longhorn, lustre-csi). Pure data: nothing here talks to the cluster.
 */
public final class UserPvcs {

    private UserPvcs() {}

    /**
     * @param pvcName      name of the PVC
     * @param namespace    namespace of the user Pods
     * @param userId       owner of the PVC (label {@code user})
     * @param storageType  storage type key (label {@code storage-type})
     * @param storageClass StorageClass that provisions the volume
     * @param accessMode   ReadWriteOnce or ReadWriteMany
     * @param size         requested size, e.g. "50Gi"
     */
    public static PersistentVolumeClaim build(String pvcName, String namespace, String userId,
                                              String storageType, String storageClass,
                                              String accessMode, String size) {
        return new PersistentVolumeClaimBuilder()
            .withNewMetadata()
                .withName(pvcName)
                .withNamespace(namespace)
                .addToLabels("app", "k8s-pups-user")
                .addToLabels("managed-by", "k8s-pups")
                .addToLabels("user", userId)
                .addToLabels("storage-type", storageType)
            .endMetadata()
            .withNewSpec()
                .withStorageClassName(storageClass)
                .withAccessModes(accessMode)
                .withNewResources()
                    .addToRequests("storage", new Quantity(size))
                .endResources()
            .endSpec()
            .build();
    }
}
