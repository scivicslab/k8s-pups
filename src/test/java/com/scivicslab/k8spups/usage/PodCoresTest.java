package com.scivicslab.k8spups.usage;

import com.scivicslab.k8spups.plugin.ResourceProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PodCoresTest {

    private static final ResourceProfile LIGHT = new ResourceProfile("light", "Light",
        Map.of("cpu", "250m", "memory", "512Mi"), Map.of("cpu", "4", "memory", "16Gi"));
    private static final ResourceProfile STANDARD = new ResourceProfile("standard", "Standard",
        Map.of("cpu", "250m", "memory", "512Mi"), Map.of("cpu", "8", "memory", "32Gi"));

    @Test
    @DisplayName("The profile the user chose gives the CPU request and the CPU limit that are recorded")
    void chosenProfile() {
        PodCores cores = PodCores.of(List.of(LIGHT, STANDARD), "standard");
        assertEquals("standard", cores.profile());
        assertEquals(250, cores.requestsMillis());
        assertEquals(8000, cores.limitsMillis());
    }

    @Test
    @DisplayName("Without a chosen profile, the first profile of the tool is used, as the Pod is built from it")
    void noChoice() {
        PodCores cores = PodCores.of(List.of(LIGHT, STANDARD), null);
        assertEquals("light", cores.profile());
        assertEquals(4000, cores.limitsMillis());
    }

    @Test
    @DisplayName("A chosen name that no profile has falls back to the first profile")
    void unknownChoice() {
        assertEquals("light", PodCores.of(List.of(LIGHT, STANDARD), "huge").profile());
    }

    @Test
    @DisplayName("A tool without profiles, or a profile without a CPU limit, records the CPU as unknown")
    void unknown() {
        assertEquals(PodCores.UNKNOWN, PodCores.of(List.of(), "standard"));
        assertEquals(PodCores.UNKNOWN, PodCores.of(null, null));
        ResourceProfile noCpu = new ResourceProfile("p", "P", Map.of("memory", "1Gi"), Map.of("memory", "2Gi"));
        PodCores cores = PodCores.of(List.of(noCpu), "p");
        assertEquals("p", cores.profile());
        assertNull(cores.requestsMillis());
        assertNull(cores.limitsMillis());
    }
}
