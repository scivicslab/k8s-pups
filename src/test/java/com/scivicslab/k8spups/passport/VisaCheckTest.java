package com.scivicslab.k8spups.passport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Visa check of the controller: signature, issuer, expiry, type, authority and source. No network. */
class VisaCheckTest {

    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    private static final long LATER = NOW.getEpochSecond() + 3600;
    private static final String DATASET = TestIssuer.PREFIX + "JGAD000001";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private TestIssuer issuer;
    private Clock clock;

    @BeforeEach
    void setUp() {
        issuer = new TestIssuer("kid-1");
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private VisaCheck check(TestIssuer served, Clock at) {
        return new VisaCheck(Map.of(TestIssuer.ISSUER, TestIssuer.JWKS_URI), Set.of("dac"), Set.of(TestIssuer.COMMITTEE),
                uri -> served.jwks(), at);
    }

    @Test
    void acceptsAVisaOfTheTrustedIssuerAndReportsValueAndExpiry() throws Exception {
        String visa = issuer.visa(TestIssuer.ISSUER, DATASET, "dac", TestIssuer.COMMITTEE, LATER);
        VisaCheck.Result result = check(issuer, clock).check(visa);
        assertTrue(result.accepted(), result.reason());
        assertEquals(DATASET, result.value());
        assertEquals(LATER, result.expiresAt());
    }

    @Test
    void rejectsAVisaWhosePayloadWasChanged() throws Exception {
        String visa = issuer.visa(TestIssuer.ISSUER, DATASET, "dac", TestIssuer.COMMITTEE, LATER);
        String[] parts = visa.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("JGAD000001", "JGAD000002");
        String forged = parts[0] + "." + B64URL.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        VisaCheck.Result result = check(issuer, clock).check(forged);
        assertFalse(result.accepted());
        assertTrue(result.reason().contains("signature"), result.reason());
    }

    @Test
    void rejectsAVisaSignedByAnotherKeyThatUsesTheSameKid() throws Exception {
        TestIssuer impostor = new TestIssuer("kid-1");
        String visa = impostor.visa(TestIssuer.ISSUER, DATASET, "dac", TestIssuer.COMMITTEE, LATER);
        VisaCheck.Result result = check(issuer, clock).check(visa);
        assertFalse(result.accepted());
        assertTrue(result.reason().contains("signature"), result.reason());
    }

    @Test
    void rejectsAnExpiredVisa() throws Exception {
        String visa = issuer.visa(TestIssuer.ISSUER, DATASET, "dac", TestIssuer.COMMITTEE, LATER);
        VisaCheck.Result result = check(issuer, Clock.fixed(NOW.plusSeconds(3601), ZoneOffset.UTC)).check(visa);
        assertFalse(result.accepted());
        assertTrue(result.reason().contains("expired"), result.reason());
    }

    @Test
    void rejectsAnUntrustedIssuer() throws Exception {
        String visa = issuer.visa("https://stranger.example.org", DATASET, "dac", TestIssuer.COMMITTEE, LATER);
        assertFalse(check(issuer, clock).check(visa).accepted());
    }

    @Test
    void rejectsAnAuthorityTheDeploymentDoesNotAccept() throws Exception {
        String visa = issuer.visa(TestIssuer.ISSUER, DATASET, "self", TestIssuer.COMMITTEE, LATER);
        VisaCheck.Result result = check(issuer, clock).check(visa);
        assertFalse(result.accepted());
        assertTrue(result.reason().contains("authority"), result.reason());
    }

    @Test
    void rejectsAnUntrustedSource() throws Exception {
        String visa = issuer.visa(TestIssuer.ISSUER, DATASET, "dac", "https://example.org/committees/9999", LATER);
        assertFalse(check(issuer, clock).check(visa).accepted());
    }

    @Test
    void rejectsSymmetricAlgorithmsAndAlgNoneBeforeAnyKeyIsLookedUp() {
        for (String alg : new String[] {"HS256", "none"}) {
            String header = B64URL.encodeToString(("{\"alg\":\"" + alg + "\"}").getBytes(StandardCharsets.UTF_8));
            String payload = B64URL.encodeToString(("{\"iss\":\"" + TestIssuer.ISSUER + "\",\"exp\":9999999999}")
                    .getBytes(StandardCharsets.UTF_8));
            VisaCheck.Result result = new VisaCheck(Map.of(TestIssuer.ISSUER, TestIssuer.JWKS_URI), Set.of("dac"), Set.of(),
                    uri -> { throw new AssertionError("no key must be fetched for alg " + alg); }, clock)
                    .check(header + "." + payload + ".AAAA");
            assertFalse(result.accepted());
            assertTrue(result.reason().contains("alg"), result.reason());
        }
    }
}
