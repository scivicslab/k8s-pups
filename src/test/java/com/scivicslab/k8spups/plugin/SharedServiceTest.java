package com.scivicslab.k8spups.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A shared service is read from k8spups.shared-service.<name>.* and needs its four cluster fields. */
class SharedServiceTest {

    private static final Map<String, String> CONFIG = Map.of(
            "k8spups.shared-service.gpu-broker.display-name", "GPU Broker",
            "k8spups.shared-service.gpu-broker.description", "One per cluster.",
            "k8spups.shared-service.gpu-broker.namespace", "gpu-broker",
            "k8spups.shared-service.gpu-broker.deployment", "gpu-broker",
            "k8spups.shared-service.gpu-broker.service", "gpu-broker",
            "k8spups.shared-service.gpu-broker.port", "28005",
            "k8spups.shared-service.half.namespace", "x");

    @Test
    void readsACompleteDefinition() {
        SharedService s = SharedService.fromConfig("gpu-broker", k -> Optional.ofNullable(CONFIG.get(k))).orElseThrow();
        assertEquals("GPU Broker", s.displayName());
        assertEquals(28005, s.port());
        assertEquals("http://gpu-broker.gpu-broker.svc:28005", s.clusterUrl());
    }

    @Test
    void skipsIncompleteOrUnknownNames() {
        List<SharedService> all = SharedService.allFromConfig(" gpu-broker, half, nothing ,",
                k -> Optional.ofNullable(CONFIG.get(k)));
        assertEquals(List.of("gpu-broker"), all.stream().map(SharedService::name).toList());
        assertTrue(SharedService.fromConfig("half", k -> Optional.ofNullable(CONFIG.get(k))).isEmpty());
    }
}
