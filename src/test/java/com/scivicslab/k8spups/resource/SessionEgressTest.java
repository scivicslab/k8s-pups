package com.scivicslab.k8spups.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decisions of {@link SessionEgress}, which need no running server. */
class SessionEgressTest {

    private static final long MB = 1024 * 1024;

    private static String judge(String disposition, long length, boolean eventStream, long sentSoFar) {
        return SessionEgress.refusal(true, 10 * MB, 200 * MB, sentSoFar, disposition, length, eventStream);
    }

    @Test
    void anAttachmentIsRefusedWhateverItsSize() {
        assertNotNull(judge("attachment; filename=\"a.bam\"", 10, false, 0));
        assertNotNull(judge("  Attachment", -1, false, 0));
        assertNull(judge("inline", 10, false, 0));
        assertNull(judge("inline; filename=\"a.png\"", 10, false, 0));
        assertNull(judge(null, 10, false, 0));
    }

    @Test
    void theAttachmentRuleCanBeTurnedOff() {
        assertNull(SessionEgress.refusal(false, 10 * MB, 200 * MB, 0, "attachment", 10, false));
    }

    @Test
    void aResponseOverTheLimitIsRefusedAndOneAtTheLimitPasses() {
        assertNull(judge(null, 10 * MB, false, 0));
        assertNotNull(judge(null, 10 * MB + 1, false, 0));
        assertNull(judge(null, -1, false, 0), "an unknown length is judged while the body is sent");
    }

    @Test
    void anEventStreamIsNotCutByTheSizeOfOneResponse() {
        assertNull(judge(null, 50 * MB, true, 0));
        assertNull(judge(null, -1, true, 0));
    }

    @Test
    void aSessionThatHasSentItsLimitSendsNoMore() {
        assertNull(judge(null, 1, false, 200 * MB));
        assertNotNull(judge(null, 1, false, 200 * MB + 1));
        assertNotNull(judge(null, 1, true, 200 * MB + 1));
    }

    @Test
    void aLimitOfZeroIsOff() {
        assertNull(SessionEgress.refusal(true, 0, 0, Long.MAX_VALUE / 2, null, Long.MAX_VALUE / 2, false));
        assertFalse(SessionEgress.exceeds(0, 1));
        assertTrue(SessionEgress.exceeds(1, 2));
        assertFalse(SessionEgress.exceeds(5, -1));
    }

    @Test
    void theReasonSaysWhichLimitWasReached() {
        assertTrue(judge("attachment", 1, false, 0).contains("attachment"));
        assertTrue(judge(null, 11 * MB, false, 0).contains("limit of " + 10 * MB));
        assertTrue(judge(null, 1, false, 201 * MB).contains("limit is " + 200 * MB));
        assertEquals(null, judge("inline", 1, false, 0));
    }
}
