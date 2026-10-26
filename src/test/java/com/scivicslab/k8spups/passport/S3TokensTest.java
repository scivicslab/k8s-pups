package com.scivicslab.k8spups.passport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The tokens an S3 server accepts as a web identity. Verified here with the JDK from the published key set. */
class S3TokensTest {

    private static final String ISSUER = "http://controller.example/s3-oidc";
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final S3Tokens tokens = S3Tokens.generate(ISSUER, "k8s-pups", 900, Clock.fixed(NOW, ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    private static PublicKey publicKeyOf(Map<String, Object> jwks) throws Exception {
        Map<String, Object> jwk = ((List<Map<String, Object>>) jwks.get("keys")).get(0);
        BigInteger x = new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("x")));
        BigInteger y = new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("y")));
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(new ECPoint(x, y), parameters.getParameterSpec(ECParameterSpec.class)));
    }

    private static Map<String, Object> part(String jwt, int index) throws Exception {
        return JSON.readValue(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]), MAP);
    }

    private static boolean signatureValid(String jwt, PublicKey key) throws Exception {
        String[] parts = jwt.split("\\.");
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initVerify(key);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    @Test
    void aTokenCarriesTheClaimsTheS3ServerReadsAndVerifiesWithThePublishedKey() throws Exception {
        String jwt = tokens.mint("e2eadmin", List.of("jga-read-JGAS000001", "jga-read-JGAS000002"));
        Map<String, Object> header = part(jwt, 0);
        Map<String, Object> claims = part(jwt, 1);
        assertEquals("ES256", header.get("alg"));
        assertEquals(tokens.keyId(), header.get("kid"));
        assertEquals(ISSUER, claims.get("iss"));
        assertEquals("e2eadmin", claims.get("sub"));
        assertEquals("k8s-pups", claims.get("aud"));
        assertEquals("jga-read-JGAS000001,jga-read-JGAS000002", claims.get("policy"));
        assertEquals(NOW.getEpochSecond() + 900, ((Number) claims.get("exp")).longValue());
        assertTrue(signatureValid(jwt, publicKeyOf(tokens.jwks())));
    }

    @Test
    void aTokenDoesNotVerifyWithAnotherKey() throws Exception {
        S3Tokens other = S3Tokens.generate(ISSUER, "k8s-pups", 900, Clock.fixed(NOW, ZoneOffset.UTC));
        String jwt = tokens.mint("u", List.of("p"));
        assertTrue(!signatureValid(jwt, publicKeyOf(other.jwks())));
    }

    @Test
    void theDiscoveryDocumentPointsToTheKeySetOfTheIssuer() {
        Map<String, Object> doc = tokens.discovery();
        assertEquals(ISSUER, doc.get("issuer"));
        assertEquals(ISSUER + "/jwks.json", doc.get("jwks_uri"));
        assertEquals(List.of("ES256"), doc.get("id_token_signing_alg_values_supported"));
    }

    @Test
    void aKeyReadFromPemFilesHasTheSameKeyIdAndSignsTokensThatVerify() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        String privatePem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String publicPem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        S3Tokens first = S3Tokens.fromPem(privatePem, publicPem, ISSUER, "k8s-pups", 900, clock);
        S3Tokens second = S3Tokens.fromPem(privatePem, publicPem, ISSUER, "k8s-pups", 900, clock);
        assertEquals(first.keyId(), second.keyId(), "the same key gives the same key id after a restart");
        assertTrue(signatureValid(first.mint("u", List.of("p")), publicKeyOf(second.jwks())));
    }
}
