package com.scivicslab.k8spups.passport;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/** A Visa issuer for the tests: signs Visas with ES256 using only the JDK and serves its own key set. */
final class TestIssuer {

    static final String ISSUER = "https://issuer.example.org";
    static final String JWKS_URI = ISSUER + "/.well-known/jwks.json";
    static final String PREFIX = "https://example.org/datasets/";
    static final String COMMITTEE = "https://example.org/committees/0001";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    final String kid;
    private final KeyPair keyPair;

    TestIssuer(String kid) {
        this.kid = kid;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            this.keyPair = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The key set body this issuer serves. */
    String jwks() throws Exception {
        ECPublicKey key = (ECPublicKey) keyPair.getPublic();
        return JSON.writeValueAsString(Map.of("keys", List.of(Map.of(
                "kty", "EC", "crv", "P-256", "kid", kid,
                "x", B64URL.encodeToString(unsigned32(key.getW().getAffineX())),
                "y", B64URL.encodeToString(unsigned32(key.getW().getAffineY()))))));
    }

    /** A signed ControlledAccessGrants Visa. */
    String visa(String iss, String dataset, String by, String source, long exp) throws Exception {
        return sign(Map.of("alg", "ES256", "typ", "vnd.ga4gh.visa+jwt", "kid", kid),
                Map.of("iss", iss, "sub", "researcher", "iat", exp - 3600, "exp", exp,
                        "ga4gh_visa_v1", Map.of("type", "ControlledAccessGrants", "asserted", 1L,
                                "value", dataset, "source", source, "by", by)));
    }

    String sign(Map<String, Object> header, Map<String, Object> payload) throws Exception {
        String signingInput = B64URL.encodeToString(JSON.writeValueAsBytes(header)) + "."
                + B64URL.encodeToString(JSON.writeValueAsBytes(payload));
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + B64URL.encodeToString(signature.sign());
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }
}
