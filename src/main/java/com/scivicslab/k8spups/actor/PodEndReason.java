package com.scivicslab.k8spups.actor;

import io.fabric8.kubernetes.api.model.Pod;

/**
 * Why a Pod that reports the phase Failed or Unknown has ended. When someone deletes a Pod, Kubernetes marks it with a
 * deletion timestamp and stops its containers before the Pod is gone, and the stopped containers make the phase Failed;
 * the Pod did not fail, it was deleted.
 */
final class PodEndReason {

    static final String DELETED_EXTERNALLY = "Pod deleted externally";

    private PodEndReason() {}

    static String of(Pod pod) {
        if (pod.getMetadata() != null && pod.getMetadata().getDeletionTimestamp() != null) return DELETED_EXTERNALLY;
        String phase = pod.getStatus() != null && pod.getStatus().getPhase() != null ? pod.getStatus().getPhase() : "Unknown";
        return "Pod failed: phase=" + phase;
    }
}
