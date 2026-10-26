package com.scivicslab.k8spups.passport;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Makes the ES256 JSON Web Tokens that an S3 server (MinIO) accepts as an OpenID Connect web identity, and the two
 * documents that let the S3 server trust them: the OpenID Connect discovery document and the JSON Web Key Set.
 * A token names the policies of the S3 server that apply to the holder in its claim {@code policy}; the controller
 * makes one only for a user whose Visas grant datasets.
 */
public final class S3Tokens {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final PrivateKey privateKey;
    private final String keyId;
    private final String x;
    private final String y;
    private final String issuer;
    private final String audience;
    private final long lifetimeSeconds;
    private final Clock clock;

    private S3Tokens(KeyPair pair, String issuer, String audience, long lifetimeSeconds, Clock clock) throws Exception {
        this.privateKey = pair.getPrivate();
        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
        this.x = B64URL.encodeToString(unsigned32(publicKey.getW().getAffineX()));
        this.y = B64URL.encodeToString(unsigned32(publicKey.getW().getAffineY()));
        // RFC 7638 JWK thumbprint
        String canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + x + "\",\"y\":\"" + y + "\"}";
        this.keyId = B64URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        this.issuer = issuer;
        this.audience = audience;
        this.lifetimeSeconds = lifetimeSeconds;
        this.clock = clock;
    }

    /** A new key pair; the public key changes with every start of the controller. */
    public static S3Tokens generate(String issuer, String audience, long lifetimeSeconds, Clock clock) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return new S3Tokens(generator.generateKeyPair(), issuer, audience, lifetimeSeconds, clock);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot generate the EC P-256 key of the S3 tokens", e);
        }
    }

    /** The key pair of two PEM files: a PKCS#8 private key and an X.509 (SubjectPublicKeyInfo) public key. */
    public static S3Tokens fromPem(String privatePem, String publicPem, String issuer, String audience,
            long lifetimeSeconds, Clock clock) {
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(pemBody(privatePem)));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(pemBody(publicPem)));
            return new S3Tokens(new KeyPair(publicKey, privateKey), issuer, audience, lifetimeSeconds, clock);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the EC P-256 key of the S3 tokens", e);
        }
    }

    private static byte[] pemBody(String pem) {
        return Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
    }

    public String keyId() {
        return keyId;
    }

    /** The OpenID Connect discovery document the S3 server reads at start-up. */
    public Map<String, Object> discovery() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", issuer);
        doc.put("jwks_uri", issuer + "/jwks.json");
        doc.put("authorization_endpoint", issuer + "/authorize");
        doc.put("token_endpoint", issuer + "/token");
        doc.put("response_types_supported", List.of("id_token"));
        doc.put("subject_types_supported", List.of("public"));
        doc.put("id_token_signing_alg_values_supported", List.of("ES256"));
        return doc;
    }

    /** The public key as a JSON Web Key Set. */
    public Map<String, Object> jwks() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "EC");
        jwk.put("crv", "P-256");
        jwk.put("x", x);
        jwk.put("y", y);
        jwk.put("kid", keyId);
        jwk.put("use", "sig");
        jwk.put("alg", "ES256");
        return Map.of("keys", List.of(jwk));
    }

    /** A token for {@code user} that carries the S3 policies in its claim {@code policy}, comma separated. */
    public String mint(String user, List<String> policies) {
        try {
            long now = clock.instant().getEpochSecond();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "ES256");
            header.put("typ", "JWT");
            header.put("kid", keyId);
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", issuer);
            claims.put("sub", user);
            claims.put("aud", audience);
            claims.put("iat", now);
            claims.put("exp", now + lifetimeSeconds);
            claims.put("policy", String.join(",", policies));
            String signingInput = B64URL.encodeToString(JSON.writeValueAsBytes(header)) + "."
                    + B64URL.encodeToString(JSON.writeValueAsBytes(claims));
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64URL.encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot make an S3 token", e);
        }
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }
}
