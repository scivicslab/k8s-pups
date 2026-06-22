package com.scivicslab.k8spups.k8s;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MountSpec record.
 */
class MountSpecTest {

    @Test
    @DisplayName("MountSpec stores all fields correctly")
    void storesAllFields() {
        MountSpec spec = new MountSpec("longhorn", "/data", null);
        assertEquals("longhorn", spec.storageType());
        assertEquals("/data", spec.mountPath());
        assertNull(spec.sharedFrom());
    }

    @Test
    @DisplayName("MountSpec with sharedFrom stores source userId")
    void storesSharedFrom() {
        MountSpec spec = new MountSpec("nfs-k8s-shared", "/shared-data", "alice");
        assertEquals("nfs-k8s-shared", spec.storageType());
        assertEquals("/shared-data", spec.mountPath());
        assertEquals("alice", spec.sharedFrom());
    }

    @Test
    @DisplayName("Two MountSpec records with same values are equal")
    void recordEqualityHoldsForSameValues() {
        MountSpec a = new MountSpec("nfs-k8s", "/workspace", null);
        MountSpec b = new MountSpec("nfs-k8s", "/workspace", null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
