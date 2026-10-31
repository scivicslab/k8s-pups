package com.scivicslab.k8spups.passport;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The last decision about each Visa of a user, with the reason, for the administrator screen. No network. */
class PassportGrantsDiagnosisTest {

    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    private TestIssuer issuer;
    private PassportGrantsTest.MutableClock clock;

    @BeforeEach
    void setUp() {
        issuer = new TestIssuer("kid-1");
        clock = new PassportGrantsTest.MutableClock(NOW);
    }

    private PassportGrants grants() {
        VisaCheck check = new VisaCheck(Map.of(TestIssuer.ISSUER, TestIssuer.JWKS_URI), Set.of("dac"), Set.of(),
                uri -> issuer.jwks(), clock);
        return new PassportGrants(null, true, check, TestIssuer.PREFIX, 30, clock);
    }

    private String visa(String value, String by) throws Exception {
        return issuer.visa(TestIssuer.ISSUER, value, by, TestIssuer.COMMITTEE, NOW.getEpochSecond() + 3600);
    }

    @Test
    void everyVisaHasADecisionWithItsReason() throws Exception {
        PassportGrants grants = grants();
        grants.supply("u", List.of(visa(TestIssuer.PREFIX + "JGAD000002", "dac"), visa(TestIssuer.PREFIX + "JGAD000004", "self"),
                visa("https://other.example.org/datasets/JGAD000003", "dac"), "not.a.jwt"));
        var d = grants.diagnosis("u").orElseThrow();
        assertEquals("the Visas of the login (UserInfo)", d.how());
        assertEquals(NOW.getEpochSecond(), d.at());
        assertEquals(Set.of("JGAD000002"), d.datasets());
        assertEquals(4, d.decisions().size());
        assertTrue(d.decisions().get(0).accepted());
        assertTrue(d.decisions().get(0).visa().contains("JGAD000002") && d.decisions().get(0).visa().contains("by dac"));
        assertFalse(d.decisions().get(1).accepted());
        assertTrue(d.decisions().get(1).reason().contains("by self is not an accepted authority"), d.decisions().get(1).reason());
        assertFalse(d.decisions().get(2).accepted());
        assertTrue(d.decisions().get(2).reason().contains("is not under " + TestIssuer.PREFIX), d.decisions().get(2).reason());
        assertFalse(d.decisions().get(3).accepted());
        assertTrue(d.decisions().get(3).reason().startsWith("the Visa could not be checked"), d.decisions().get(3).reason());
    }

    @Test
    void aNewSupplyReplacesTheDiagnosisAndAnEmptySupplyShowsNoVisa() throws Exception {
        PassportGrants grants = grants();
        assertTrue(grants.diagnosis("u").isEmpty(), "nothing was checked yet");
        grants.supply("u", List.of(visa(TestIssuer.PREFIX + "JGAD000002", "dac")));
        grants.supply("u", List.of());
        var d = grants.diagnosis("u").orElseThrow();
        assertTrue(d.decisions().isEmpty());
        assertTrue(d.datasets().isEmpty());
        assertTrue(grants.diagnosis("someone-else").isEmpty());
    }

    @Test
    void aVisaCanBeSummarizedWithoutCheckingIt() throws Exception {
        VisaCheck.Summary s = VisaCheck.summarize(visa(TestIssuer.PREFIX + "JGAD000002", "dac"));
        assertEquals("ES256", s.alg());
        assertEquals(TestIssuer.ISSUER, s.issuer());
        assertEquals(TestIssuer.PREFIX + "JGAD000002", s.value());
        assertEquals("dac", s.by());
        VisaCheck.Summary none = VisaCheck.summarize("not.a.jwt");
        assertNull(none.value());
    }
}
