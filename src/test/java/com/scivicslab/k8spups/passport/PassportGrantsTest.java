package com.scivicslab.k8spups.passport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The datasets the Visas of a Passport grant. No network, no Quarkus. */
class PassportGrantsTest {

    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    private static final long EXP = NOW.getEpochSecond() + 3600;

    private TestIssuer issuer;
    private MutableClock clock;

    /** A clock that tests move forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        issuer = new TestIssuer("kid-1");
        clock = new MutableClock(NOW);
    }

    private PassportGrants grants(PassportGrants.PassportSource source, long cacheSeconds) {
        VisaCheck check = new VisaCheck(Map.of(TestIssuer.ISSUER, TestIssuer.JWKS_URI), Set.of("dac"), Set.of(),
                uri -> issuer.jwks(), clock);
        return new PassportGrants(source, check, TestIssuer.PREFIX, cacheSeconds, clock);
    }

    private String visa(String value, String by) throws Exception {
        return issuer.visa(TestIssuer.ISSUER, value, by, TestIssuer.COMMITTEE, EXP);
    }

    @Test
    void aVisaGrantsTheDatasetNamedByItsValue() throws Exception {
        PassportGrants grants = grants(user -> List.of(visa(TestIssuer.PREFIX + "JGAD000001", "dac")), 30);
        assertEquals(Set.of("JGAD000001"), grants.datasets("e2eadmin"));
    }

    @Test
    void visasThatAreRejectedOrOutsideThePrefixGrantNothing() throws Exception {
        PassportGrants grants = grants(user -> List.of(
                visa(TestIssuer.PREFIX + "JGAD000002", "self"),
                visa("https://other.example.org/datasets/JGAD000003", "dac"),
                visa(TestIssuer.PREFIX + "JGAD000004/extra", "dac"),
                visa(TestIssuer.PREFIX, "dac")), 30);
        assertEquals(Set.of(), grants.datasets("e2eadmin"));
    }

    @Test
    void severalVisasGrantSeveralDatasets() throws Exception {
        PassportGrants grants = grants(user -> List.of(
                visa(TestIssuer.PREFIX + "JGAD000001", "dac"), visa(TestIssuer.PREFIX + "JGAD000002", "dac")), 30);
        assertEquals(Set.of("JGAD000001", "JGAD000002"), grants.datasets("e2eadmin"));
    }

    @Test
    void theResultIsCachedForTheCacheTimeAndThenFetchedAgain() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        List<String> current = new ArrayList<>(List.of(visa(TestIssuer.PREFIX + "JGAD000001", "dac")));
        PassportGrants grants = grants(user -> { fetches.incrementAndGet(); return List.copyOf(current); }, 30);
        grants.datasets("u");
        grants.datasets("u");
        assertEquals(1, fetches.get());
        current.clear();
        assertEquals(Set.of("JGAD000001"), grants.datasets("u"));
        clock.advance(31);
        assertEquals(Set.of(), grants.datasets("u"), "a grant removed at the issuer is gone after the cache time");
        assertEquals(2, fetches.get());
    }

    @Test
    void whenTheIssuerCannotBeReachedTheLastResultHoldsUntilTheVisasExpire() throws Exception {
        boolean[] down = {false};
        PassportGrants grants = grants(user -> {
            if (down[0]) throw new java.io.IOException("connection refused");
            return List.of(visa(TestIssuer.PREFIX + "JGAD000001", "dac"));
        }, 30);
        assertEquals(Set.of("JGAD000001"), grants.datasets("u"));
        down[0] = true;
        clock.advance(60);
        assertEquals(Set.of("JGAD000001"), grants.datasets("u"), "a short outage keeps the grant");
        clock.advance(3600);
        assertTrue(grants.datasets("u").isEmpty(), "after the Visas expired the grant is gone");
    }

    @Test
    void anUnreachableIssuerGrantsNothingToAUserWithoutAPreviousResult() {
        PassportGrants grants = grants(user -> { throw new java.io.IOException("connection refused"); }, 30);
        assertTrue(grants.datasets("u").isEmpty());
    }
}
