package com.scivicslab.k8spups.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CpuQuantityTest {

    @Test
    @DisplayName("A quantity in millicores is returned as it is")
    void millicores() {
        assertEquals(250, CpuQuantity.toMillicores("250m"));
        assertEquals(50, CpuQuantity.toMillicores("50m"));
    }

    @Test
    @DisplayName("A quantity in cores is multiplied by 1000")
    void cores() {
        assertEquals(4000, CpuQuantity.toMillicores("4"));
        assertEquals(8000, CpuQuantity.toMillicores("8"));
        assertEquals(1500, CpuQuantity.toMillicores("1.5"));
    }

    @Test
    @DisplayName("A missing quantity is null, so a profile without a CPU limit is recorded as unknown")
    void missing() {
        assertNull(CpuQuantity.toMillicores(null));
        assertNull(CpuQuantity.toMillicores("  "));
    }

    @Test
    @DisplayName("A fraction of a millicore is rounded to the nearest millicore")
    void fractionOfMillicore() {
        assertEquals(1, CpuQuantity.toMillicores("0.5m"));
        assertEquals(0, CpuQuantity.toMillicores("0.4m"));
    }

    @Test
    @DisplayName("Text that is not a CPU quantity, and a negative quantity, are refused")
    void invalid() {
        assertThrows(IllegalArgumentException.class, () -> CpuQuantity.toMillicores("four"));
        assertThrows(IllegalArgumentException.class, () -> CpuQuantity.toMillicores("2Gi"));
        assertThrows(IllegalArgumentException.class, () -> CpuQuantity.toMillicores("-1"));
    }
}
