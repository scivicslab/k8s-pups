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
        assertEquals("Shared file storage", names.get("lustre-csi"));
        assertEquals("Block storage", names.get("longhorn"));
        assertFalse(names.containsKey("nfs-home"));   // not offered, so not shown
    }

    @Test
    void anUnknownKeyIsShownAsItIs() {
        assertEquals("ceph-rbd", StorageTypes.name("ceph-rbd"));
        assertEquals("Shared file storage", StorageTypes.name("nfs-k8s"));
        assertEquals("", StorageTypes.description("ceph-rbd"));
    }

    @Test
    void descriptionsFollowTheOfferedTypes() {
        Map<String, String> d = StorageTypes.descriptions(List.of("longhorn"));
        assertEquals(List.of("longhorn"), List.copyOf(d.keySet()));
        assertFalse(d.get("longhorn").isEmpty());
    }
}
