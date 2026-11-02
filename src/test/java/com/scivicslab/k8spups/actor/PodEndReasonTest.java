package com.scivicslab.k8spups.actor;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PodEndReasonTest {

    private static Pod pod(String phase, String deletionTimestamp) {
        return new PodBuilder()
            .withNewMetadata().withName("pod-x").withDeletionTimestamp(deletionTimestamp).endMetadata()
            .withNewStatus().withPhase(phase).endStatus()
            .build();
    }

    @Test
    void aFailedPodThatIsBeingDeletedWasDeletedByOthers() {
        assertEquals("Pod deleted externally", PodEndReason.of(pod("Failed", "2026-11-03T00:00:00Z")));
    }

    @Test
    void aFailedPodThatIsNotBeingDeletedHasFailed() {
        assertEquals("Pod failed: phase=Failed", PodEndReason.of(pod("Failed", null)));
    }

    @Test
    void anUnknownPhaseIsReportedAsItIs() {
        assertEquals("Pod failed: phase=Unknown", PodEndReason.of(pod("Unknown", null)));
    }

    @Test
    void aPodWithoutStatusIsUnknown() {
        Pod noStatus = new PodBuilder().withNewMetadata().withName("pod-x").endMetadata().build();
        assertEquals("Pod failed: phase=Unknown", PodEndReason.of(noStatus));
    }
}
