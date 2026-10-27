package com.scivicslab.k8spups.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ToolPodIdsTest {

    @Test
    void withoutConfigurationThePluginUidIsUsedForBoth() {
        assertEquals(new ToolPodIds(1000L, 1000L), ToolPodIds.resolve(1000L, null, null));
    }

    @Test
    void configuredNumbersReplaceThePluginUid() {
        assertEquals(new ToolPodIds(30420L, 30102L), ToolPodIds.resolve(1000L, 30420L, 30102L));
    }

    @Test
    void imageDefaultStaysImageDefault() {
        assertEquals(new ToolPodIds(null, null), ToolPodIds.resolve(null, 30420L, 30102L));
        assertEquals(new ToolPodIds(null, null), ToolPodIds.resolve(null, null, null));
    }

    @Test
    void onlyOneConfiguredNumberIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ToolPodIds.resolve(1000L, 30420L, null));
        assertThrows(IllegalArgumentException.class, () -> ToolPodIds.resolve(1000L, null, 30102L));
    }
}
