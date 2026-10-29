package com.scivicslab.k8spups.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import org.junit.jupiter.api.Test;

class UserPvcsTest {

    @Test
    void posixPvcIsSharedAndUsesTheConfiguredClass() {
        PersistentVolumeClaim pvc = UserPvcs.build("pups-alice-posix", "user-pods-with-audit", "alice",
            "posix", "lustre-csi-pups", "ReadWriteMany", "50Gi");
        assertEquals("pups-alice-posix", pvc.getMetadata().getName());
        assertEquals("user-pods-with-audit", pvc.getMetadata().getNamespace());
        assertEquals("lustre-csi-pups", pvc.getSpec().getStorageClassName());
        assertEquals("ReadWriteMany", pvc.getSpec().getAccessModes().get(0));
        assertEquals("50Gi", pvc.getSpec().getResources().getRequests().get("storage").toString());
        assertEquals("posix", pvc.getMetadata().getLabels().get("storage-type"));
        assertEquals("alice", pvc.getMetadata().getLabels().get("user"));
    }

    @Test
    void longhornPvcIsSingleWriterAndTakesTheClassFromTheCaller() {
        PersistentVolumeClaim pvc = UserPvcs.build("pups-bob-longhorn", "user-pods", "bob",
            "longhorn", "longhorn", "ReadWriteOnce", "100Gi");
        assertEquals("longhorn", pvc.getSpec().getStorageClassName());
        assertEquals("ReadWriteOnce", pvc.getSpec().getAccessModes().get(0));
        assertEquals("longhorn", pvc.getMetadata().getLabels().get("storage-type"));
    }
}
