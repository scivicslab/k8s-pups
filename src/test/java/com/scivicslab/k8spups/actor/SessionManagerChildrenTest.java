package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.k8s.SessionInfo;
import com.scivicslab.k8spups.plugin.ConnectionType;
import com.scivicslab.k8spups.plugin.ToolPlugin;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sessions of a SessionManager are its child actors; the manager keeps no list of its own. These tests run the
 * manager inside a real actor system with sessions that never touch Kubernetes.
 */
class SessionManagerChildrenTest {

    private ActorSystem system;
    private ActorRef<SessionManager> self;

    @BeforeEach
    void start() {
        system = new ActorSystem("session-manager-children-test");
        self = system.actorOf("session-manager",
            new SessionManager(null, Map.of(), "ns", 10, 2, 60, 600, Set.of()));
    }

    @AfterEach
    void stop() {
        system.terminate();
    }

    private static ToolPlugin plugin() {
        return new ToolPlugin() {
            @Override public String name() { return "file-browser"; }
            @Override public String displayName() { return "File Browser"; }
            @Override public String containerImage() { return "test:latest"; }
            @Override public int containerPort() { return 8080; }
            @Override public ConnectionType connectionType() { return ConnectionType.HTTP; }
        };
    }

    private void addSession(String sessionId, String userId) {
        SessionInfo info = new SessionInfo(sessionId, userId, plugin(), List.of(), null, null,
            Collections.emptyMap(), null, null);
        self.createChild("session-" + sessionId, new Session(info, null));
    }

    @Test
    void sessionIdsAreTheChildren() throws Exception {
        addSession("s1", "alice");
        addSession("s2", "bob");
        assertEquals(Set.of("s1", "s2"), self.ask(m -> m.getSessionIds(self)).get());
    }

    @Test
    void aUsersSessionsAreFoundByAskingEachSessionForItsOwner() throws Exception {
        addSession("s1", "alice");
        addSession("s2", "bob");
        addSession("s3", "alice");
        var alice = self.ask(m -> m.getUserSessions(self, "alice")).get();
        assertEquals(Set.of("s1", "s3"), Set.of(alice.get(0).sessionId(), alice.get(1).sessionId()));
        assertEquals(1, self.ask(m -> m.getUserSessions(self, "bob")).get().size());
        assertTrue(self.ask(m -> m.getUserSessions(self, "carol")).get().isEmpty());
        assertTrue(self.ask(m -> m.hasSession(self, "alice")).get());
        assertFalse(self.ask(m -> m.hasSession(self, "carol")).get());
    }

    @Test
    void destroyingASessionRemovesItFromTheChildren() throws Exception {
        addSession("s1", "alice");
        self.ask(m -> { m.destroySession(self, "s1"); return null; }).get();
        assertTrue(self.getNamesOfChildren().isEmpty());
        assertFalse(system.hasActor("session-s1"));
        assertTrue(self.ask(m -> m.getSessionIds(self)).get().isEmpty());
    }

    @Test
    void summaryCountsTheChildren() throws Exception {
        addSession("s1", "alice");
        addSession("s2", "bob");
        assertEquals(2, self.ask(m -> m.getSessionSummary(self)).get().total());
    }
}
