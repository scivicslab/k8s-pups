package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.k8s.SessionInfo;
import com.scivicslab.k8spups.plugin.TerminalPlugin;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.Watcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@code Session} actor does when somebody other than the controller deletes its Pod (for example an
 * administrator with {@code kubectl delete pod}). The Service of the Pod must go with it, and the session must tell its
 * {@code SessionManager} actor that its Pod is gone, so that the manager can remove it.
 */
class SessionPodDeletedExternallyTest {

    /** A session that records the deletion of its Service instead of asking Kubernetes. */
    private static final class RecordingSession extends Session {
        int servicesDeleted;

        RecordingSession() {
            super(new SessionInfo("abc12345-678", "alice", new TerminalPlugin(), List.of(), "lab", null), null);
        }

        @Override
        void deleteServiceOfGonePod() {
            servicesDeleted++;
        }
    }

    private static Pod pod(String phase, String deletionTimestamp) {
        return new PodBuilder()
            .withNewMetadata().withName("pod-x").withDeletionTimestamp(deletionTimestamp).endMetadata()
            .withNewStatus().withPhase(phase).endStatus()
            .build();
    }

    @Test
    void whenKubernetesReportsThePodDeletedTheServiceIsDeleted() {
        RecordingSession session = new RecordingSession();
        session.onPodEvent(Watcher.Action.DELETED, pod("Running", null));
        assertEquals(1, session.servicesDeleted);
        assertEquals(SessionState.STOPPED, session.getState());
        assertTrue(session.podWasDeletedExternally());
    }

    @Test
    void whenAFailedPodIsBeingDeletedTheServiceIsDeleted() {
        RecordingSession session = new RecordingSession();
        session.onPodEvent(Watcher.Action.MODIFIED, pod("Failed", "2026-11-03T00:00:00Z"));
        assertEquals(1, session.servicesDeleted);
        assertEquals(SessionState.STOPPED, session.getState());
        assertTrue(session.podWasDeletedExternally());
    }

    @Test
    void theServiceIsDeletedOnlyOnceWhenBothEventsArrive() {
        RecordingSession session = new RecordingSession();
        session.onPodEvent(Watcher.Action.MODIFIED, pod("Failed", "2026-11-03T00:00:00Z"));
        session.onPodEvent(Watcher.Action.DELETED, pod("Failed", "2026-11-03T00:00:00Z"));
        assertEquals(1, session.servicesDeleted);
    }

    @Test
    void aPodThatFailedByItselfKeepsItsServiceUntilTheUserStopsTheSession() {
        RecordingSession session = new RecordingSession();
        session.onPodEvent(Watcher.Action.MODIFIED, pod("Failed", null));
        assertEquals(0, session.servicesDeleted);
        assertEquals(SessionState.FAILED, session.getState());
        assertFalse(session.podWasDeletedExternally());
    }
}
