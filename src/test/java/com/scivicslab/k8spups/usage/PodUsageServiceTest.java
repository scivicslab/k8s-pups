package com.scivicslab.k8spups.usage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PodUsageServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-03T00:00:00Z");

    /** A repository that records the writes that succeeded and fails while {@code down} is true. */
    private static final class FakeRepository extends PodUsageRepository {
        final List<String> written = new ArrayList<>();
        boolean down;

        private void write(String what) {
            if (down) throw new IllegalStateException("database is down");
            written.add(what);
        }

        @Override public void open(Start s) { write("open " + s.sessionId()); }
        @Override public void ready(String sessionId, Instant at) { write("ready " + sessionId); }
        @Override public void end(String sessionId, Instant at, String reason) { write("end " + sessionId); }
        @Override public void seen(Collection<String> sessionIds) { write("seen " + sessionIds.size()); }
        @Override public int endOpenExcept(Collection<String> live, String reason) { write("endOpenExcept"); return 0; }
    }

    private static PodUsageRepository.Start start(String sessionId) {
        return new PodUsageRepository.Start(sessionId, "alice", "terminal", "pod-" + sessionId, PodCores.UNKNOWN, T0);
    }

    @Test
    void aWriteIsDoneAtOnceWhenTheDatabaseIsUp() {
        FakeRepository db = new FakeRepository();
        PodUsageService service = new PodUsageService(db);
        service.created(start("s1"));
        service.ready("s1", T0);
        service.ended("s1", T0, "stopped");
        assertEquals(List.of("open s1", "ready s1", "end s1"), db.written);
    }

    @Test
    void aFailedWriteDoesNotReachTheSession() {
        FakeRepository db = new FakeRepository();
        db.down = true;
        PodUsageService service = new PodUsageService(db);
        assertDoesNotThrow(() -> service.created(start("s1")));
        assertDoesNotThrow(() -> service.ended("s1", T0, "stopped"));
    }

    @Test
    void writesThatFailedAreDoneInTheirOriginalOrderWhenTheDatabaseIsBack() {
        FakeRepository db = new FakeRepository();
        PodUsageService service = new PodUsageService(db);
        db.down = true;
        service.created(start("s1"));
        service.ready("s1", T0);
        db.down = false;
        service.ended("s1", T0, "stopped");
        assertEquals(List.of("open s1", "ready s1", "end s1"), db.written);
    }

    @Test
    void theMinuteTickRetriesTheWritesThatFailedEvenWhenNothingNewHappened() {
        FakeRepository db = new FakeRepository();
        PodUsageService service = new PodUsageService(db);
        db.down = true;
        service.created(start("s1"));
        db.down = false;
        service.seen(Set.of("s1"));
        assertEquals(List.of("open s1", "seen 1"), db.written);
    }

    @Test
    void aWriteIsNotDoneTwice() {
        FakeRepository db = new FakeRepository();
        PodUsageService service = new PodUsageService(db);
        service.created(start("s1"));
        service.seen(Set.of("s1"));
        service.seen(Set.of("s1"));
        assertEquals(List.of("open s1", "seen 1", "seen 1"), db.written);
    }

    @Test
    void whenTooManyWritesWaitTheOldestAreGivenUp() {
        FakeRepository db = new FakeRepository();
        PodUsageService service = new PodUsageService(db, 2);
        db.down = true;
        service.created(start("s1"));
        service.created(start("s2"));
        service.created(start("s3"));
        db.down = false;
        service.seen(Set.of());
        assertEquals(List.of("open s2", "open s3"), db.written.subList(0, 2));
    }
}
