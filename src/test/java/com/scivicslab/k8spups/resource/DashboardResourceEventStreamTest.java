package com.scivicslab.k8spups.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The session proxy opens an event stream's response at once; this decides which responses are event streams. */
class DashboardResourceEventStreamTest {

    @Test
    void eventStreamContentTypeIsRecognizedWithOrWithoutParameters() {
        assertTrue(DashboardResource.isEventStream("text/event-stream"));
        assertTrue(DashboardResource.isEventStream("text/event-stream;charset=UTF-8"));
        assertTrue(DashboardResource.isEventStream("  Text/Event-Stream ; charset=utf-8"));
    }

    @Test
    void otherResponsesAreNotEventStreams() {
        assertFalse(DashboardResource.isEventStream("text/html"));
        assertFalse(DashboardResource.isEventStream("application/json"));
        assertFalse(DashboardResource.isEventStream(""));
        assertFalse(DashboardResource.isEventStream(null));
    }
}
