package com.scivicslab.k8spups.k8s;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatasetRootClaimNameTest {

    @Test
    void nfsMountIsOneClaimPerUser() {
        assertEquals("pups-dataset-root-alice", K8sApiClient.datasetRootClaimName("", "alice"));
        assertEquals("pups-dataset-root-alice", K8sApiClient.datasetRootClaimName(null, "alice"));
    }

    @Test
    void storageClassGivesOneSharedClaimForAllUsers() {
        assertEquals("pups-dataset-root", K8sApiClient.datasetRootClaimName("lustre-csi-pups", "alice"));
        assertEquals("pups-dataset-root", K8sApiClient.datasetRootClaimName("lustre-csi-pups", "bob"));
    }
}
