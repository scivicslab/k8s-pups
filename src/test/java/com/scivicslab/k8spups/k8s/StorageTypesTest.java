package com.scivicslab.k8spups.k8s;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StorageTypesTest {

    @Test
    void namesTheOfferedTypesInTheirOrderOnly() {
        Map<String, String> names = StorageTypes.names(List.of("lustre-csi", "longhorn"));
        assertEquals(List.of("lustre-csi", "longhorn"), List.copyOf(names.keySet()));
        assertEquals("Lustre (k8s)", names.get("lustre-csi"));
        assertEquals("Longhorn", names.get("longhorn"));
        assertFalse(names.containsKey("nfs-home"));   // not offered, so not shown
    }

    @Test
    void anUnknownKeyIsShownAsItIs() {
        assertEquals("ceph-rbd", StorageTypes.name("ceph-rbd"));
        assertEquals("NFS (k8s)", StorageTypes.name("nfs-k8s"));
    }
}
