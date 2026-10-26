package com.scivicslab.k8spups.passport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The datasets of Visas that the caller supplies (taken from the UserInfo response of the login). No network. */
class PassportGrantsSuppliedTest {

    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    private TestIssuer issuer;
    private PassportGrantsTest.MutableClock clock;

    @BeforeEach
    void setUp() {
        issuer = new TestIssuer("kid-1");
        clock = new PassportGrantsTest.MutableClock(NOW);
    }

    private PassportGrants grants(PassportGrants.PassportSource direct) {
        VisaCheck check = new VisaCheck(Map.of(TestIssuer.ISSUER, TestIssuer.JWKS_URI), Set.of("dac"), Set.of(),
                uri -> issuer.jwks(), clock);
        return new PassportGrants(direct, true, check, TestIssuer.PREFIX, 30, clock);
    }

    private String visa(String dataset, String by, long lifetime) throws Exception {
        return issuer.visa(TestIssuer.ISSUER, TestIssuer.PREFIX + dataset, by, TestIssuer.COMMITTEE, NOW.getEpochSecond() + lifetime);
    }

    @Test
    void suppliedVisasGrantTheirDatasetsWithoutAnIssuerSource() throws Exception {
        PassportGrants grants = grants(null);
        assertTrue(grants.acceptsSuppliedVisas());
        grants.supply("u", List.of(visa("JGAD000002", "dac", 3600), visa("JGAD000004", "dac", 3600)));
        assertEquals(Set.of("JGAD000002", "JGAD000004"), grants.datasets("u"));
        assertEquals(Set.of(), grants.datasets("someone-else"));
    }

    @Test
    void aNewSupplyReplacesTheOldOneAndAnEmptySupplyRemovesIt() throws Exception {
        PassportGrants grants = grants(null);
        grants.supply("u", List.of(visa("JGAD000002", "dac", 3600)));
        grants.supply("u", List.of(visa("JGAD000004", "dac", 3600)));
        assertEquals(Set.of("JGAD000004"), grants.datasets("u"));
        grants.supply("u", List.of());
        assertEquals(Set.of(), grants.datasets("u"));
    }

    @Test
    void aSuppliedResultHoldsUntilTheEarliestExpiryOfItsVisas() throws Exception {
        PassportGrants grants = grants(null);
        grants.supply("u", List.of(visa("JGAD000002", "dac", 600), visa("JGAD000004", "dac", 3600)));
        clock.advance(599);
        assertEquals(Set.of("JGAD000002", "JGAD000004"), grants.datasets("u"));
        clock.advance(2);
        assertEquals(Set.of(), grants.datasets("u"), "the grants of a supply end when the first Visa expires");
    }

    @Test
    void aRejectedSuppliedVisaGrantsNothing() throws Exception {
        PassportGrants grants = grants(null);
        grants.supply("u", List.of(visa("JGAD000002", "self", 3600), "not.a.jwt"));
        assertEquals(Set.of(), grants.datasets("u"));
    }

    @Test
    void suppliedAndFetchedDatasetsAreAddedTogether() throws Exception {
        PassportGrants grants = grants(user -> List.of(visa("JGAD000001", "dac", 3600)));
        grants.supply("u", List.of(visa("JGAD000002", "dac", 3600)));
        assertEquals(Set.of("JGAD000001", "JGAD000002"), grants.datasets("u"));
    }

    @Test
    void withoutSuppliedVisasThePlainConstructorDoesNotAskForThem() {
        VisaCheck check = new VisaCheck(Map.of(), Set.of(), Set.of(), uri -> "", clock);
        assertFalse(new PassportGrants(user -> List.of(), check, TestIssuer.PREFIX, 30, clock).acceptsSuppliedVisas());
    }
}
