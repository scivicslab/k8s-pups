package com.scivicslab.k8spups.k8s;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StorageTypesTest {

    @Test
    void namesTheOfferedTypesInTheirOrderOnly() {
        Map<String, String> names = StorageTypes.names(List.of("posix", "longhorn"));
        assertEquals(List.of("posix", "longhorn"), List.copyOf(names.keySet()));
        assertEquals("Shared file storage", names.get("posix"));
        assertEquals("Block storage", names.get("longhorn"));
        assertFalse(names.containsKey("nfs-k8s"));   // not a key any more, so not shown
    }

    @Test
    void anUnknownKeyIsShownAsItIs() {
        assertEquals("ceph-rbd", StorageTypes.name("ceph-rbd"));
        assertEquals("Shared file storage", StorageTypes.name("posix"));
        assertEquals("", StorageTypes.description("ceph-rbd"));
    }

    @Test
    void descriptionsFollowTheOfferedTypes() {
        Map<String, String> d = StorageTypes.descriptions(List.of("longhorn"));
        assertEquals(List.of("longhorn"), List.copyOf(d.keySet()));
        assertFalse(d.get("longhorn").isEmpty());
    }
}
