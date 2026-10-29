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

    @Test
    void aPodNameTokenIsADnsLabelFragment() {
        assertEquals("alice", K8sApiClient.podNameToken("alice"));
        assertEquals("g-group-lab1", K8sApiClient.podNameToken("_group-lab1"));
        assertEquals("u002d-x", K8sApiClient.podNameToken("U002d_x").replace("_", "-"));
    }
}
