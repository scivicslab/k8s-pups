package com.scivicslab.k8spups.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.fabric8.kubernetes.api.model.Quantity;
import org.junit.jupiter.api.Test;

/**
 * Whether a PV that is given back gets a new capacity. Only a group shrink passes a size; an ordinary give-back never
 * resizes the PV, even when its record is smaller than the PV (an old revoked allocation may record 50Gi for a PV
 * that was later given back and enlarged to 51Gi).
 */
class RestorePvCapacityTest {

    @Test
    void anOrdinaryGiveBackNeverResizesThePv() {
        assertNull(K8sApiClient.capacityToWrite(new Quantity("51Gi"), null));
        assertNull(K8sApiClient.capacityToWrite(null, null));
    }

    @Test
    void aShrinkWritesTheNewCapacity() {
        assertEquals(new Quantity("50Gi"), K8sApiClient.capacityToWrite(new Quantity("100Gi"), "50Gi"));
        assertEquals(new Quantity("50Gi"), K8sApiClient.capacityToWrite(null, "50Gi"));
    }

    @Test
    void aShrinkToThePvsOwnCapacityWritesNothing() {
        assertNull(K8sApiClient.capacityToWrite(new Quantity("50Gi"), "50Gi"));
        assertNull(K8sApiClient.capacityToWrite(new Quantity("53687091200"), "50Gi"));
    }
}
