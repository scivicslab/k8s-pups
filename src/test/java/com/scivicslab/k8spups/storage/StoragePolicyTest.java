package com.scivicslab.k8spups.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class StoragePolicyTest {

    static final List<String> A007 = List.of("lustre-csi", "longhorn");

    @Test
    void sizesAreWholeGiOrTi() {
        assertTrue(StoragePolicy.validSize("50Gi"));
        assertTrue(StoragePolicy.validSize("2Ti"));
        assertFalse(StoragePolicy.validSize("0Gi"));
        assertFalse(StoragePolicy.validSize("1.5Gi"));
        assertFalse(StoragePolicy.validSize("50G"));
        assertFalse(StoragePolicy.validSize("-1Gi"));
        assertFalse(StoragePolicy.validSize(null));
        assertEquals(2048, StoragePolicy.gibibytes("2Ti"));
    }

    @Test
    void onlyOfferedTypesWithAPvcCanBeAllocated() {
        assertTrue(StoragePolicy.allocatable("lustre-csi", A007));
        assertFalse(StoragePolicy.allocatable("nfs-k8s", A007));          // not offered on this cluster
        assertFalse(StoragePolicy.allocatable("ceph-rbd", List.of("ceph-rbd")));   // offered, but no PVC the controller can create
    }

    @Test
    void lustreAndNfsCanBeRestoredLonghornCannot() {
        assertTrue(StoragePolicy.restorable("lustre-csi"));
        assertTrue(StoragePolicy.restorable("nfs-k8s"));
        assertFalse(StoragePolicy.restorable("longhorn"));
    }

    @Test
    void groupNamesAndModes() {
        assertTrue(StoragePolicy.validGroupName("lab1"));
        assertFalse(StoragePolicy.validGroupName("1lab"));
        assertFalse(StoragePolicy.validGroupName("lab-1"));
        assertFalse(StoragePolicy.validGroupName("Lab"));
        assertFalse(StoragePolicy.validGroupName("l"));
        assertTrue(StoragePolicy.validMode("ro"));
        assertFalse(StoragePolicy.validMode("x"));
    }

    @Test
    void defaultAllocation() {
        assertEquals(Optional.of(new StoragePolicy.Grant("lustre-csi", "50Gi")),
            StoragePolicy.parseDefault("lustre-csi:50Gi", A007));
        assertEquals(Optional.empty(), StoragePolicy.parseDefault("", A007));
        assertThrows(IllegalArgumentException.class, () -> StoragePolicy.parseDefault("lustre:50Gi", A007));
        assertThrows(IllegalArgumentException.class, () -> StoragePolicy.parseDefault("lustre-csi:50", A007));
    }
}
