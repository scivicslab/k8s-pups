package com.scivicslab.k8spups.k8s;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The view of the files granted one by one is a directory on the NFS server of the datasets. Where the dataset root is
 * a PVC of a storage class (a007) or the NFS location is not configured, the launch form must not offer it: a launch
 * that asks for it is refused.
 */
class DatasetViewSupportedTest {

    @Test
    void supportedWhereTheDatasetsAreOnNfs() {
        assertTrue(K8sApiClient.datasetViewSupported("", "192.168.5.9", "/data/datasets"));
    }

    @Test
    void notSupportedWhereTheDatasetRootIsAPvc() {
        assertFalse(K8sApiClient.datasetViewSupported("lustre-csi-pups", "", ""));
        assertFalse(K8sApiClient.datasetViewSupported("lustre-csi-pups", "192.168.5.9", "/data/datasets"));
    }

    @Test
    void notSupportedWithoutTheNfsLocation() {
        assertFalse(K8sApiClient.datasetViewSupported("", "", "/data/datasets"));
        assertFalse(K8sApiClient.datasetViewSupported("", "192.168.5.9", ""));
    }
}
