package com.scivicslab.k8spups.passport;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Checks one ControlledAccessGrants Visa the way a Passport Clearinghouse does: the signature is verified with the
 * public key of the Visa issuer, taken from the JSON Web Key Set that the trusted issuer URL maps to, and the Visa
 * must not be expired. The accepted Visa tells which dataset URL it grants and until when.
 */
public final class VisaCheck {

    /** Returns the body of the JSON Web Key Set at the given URL. */
    @FunctionalInterface
    public interface JwksSource {
        String fetch(String jwksUri) throws Exception;
    }

    /**
     * @param accepted  whether the Visa is trusted and grants access
     * @param value     the dataset URL the Visa grants (only when accepted)
     * @param expiresAt expiry of the Visa in epoch seconds (only when accepted)
     * @param reason    why the Visa was accepted or rejected
     */
    public record Result(boolean accepted, String value, long expiresAt, String reason) {
        static Result accept(String value, long expiresAt, String reason) {
            return new Result(true, value, expiresAt, reason);
        }

        static Result reject(String reason) {
            return new Result(false, null, 0, reason);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final Map<String, String> trustedIssuers;
    private final Set<String> acceptedBy;
    private final Set<String> trustedSources;
    private final JwksSource jwksSource;
    private final Clock clock;

    /**
     * @param trustedIssuers issuer URL (claim iss) to the URL of its JSON Web Key Set
     * @param acceptedBy     values of the Visa field "by" that are accepted
     * @param trustedSources Visa field "source" values that are trusted; empty means any source
     */
    public VisaCheck(Map<String, String> trustedIssuers, Set<String> acceptedBy, Set<String> trustedSources,
            JwksSource jwksSource, Clock clock) {
        this.trustedIssuers = trustedIssuers;
        this.acceptedBy = acceptedBy;
        this.trustedSources = trustedSources;
        this.jwksSource = jwksSource;
        this.clock = clock;
    }

    public Result check(String jwt) {
        try {
            String[] parts = jwt.split("\\.", -1);
            if (parts.length != 3) {
                return Result.reject("not a compact JWS");
            }
            Map<String, Object> header = decode(parts[0]);
            Map<String, Object> payload = decode(parts[1]);

            // The algorithm is fixed here; alg none and the symmetric algorithms are never accepted.
            if (!"ES256".equals(header.get("alg"))) {
                return Result.reject("unsupported alg " + header.get("alg") + " (only ES256 is accepted)");
            }
            Object typ = header.get("typ");
            if (typ != null && !"vnd.ga4gh.visa+jwt".equals(typ) && !"JWT".equals(typ)) {
                return Result.reject("unexpected typ " + typ);
            }
            String kid = (String) header.get("kid");
            if (kid == null) {
                return Result.reject("the JWS header has no kid");
            }

            String iss = (String) payload.get("iss");
            String jwksUri = iss == null ? null : trustedIssuers.get(iss);
            if (jwksUri == null) {
                return Result.reject("issuer " + iss + " is not trusted");
            }

            PublicKey publicKey = findKey(jwksSource.fetch(jwksUri), kid);
            if (publicKey == null) {
                return Result.reject("no key with kid " + kid + " in the key set of " + iss);
            }
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initVerify(publicKey);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(B64URL.decode(parts[2]))) {
                return Result.reject("the signature does not match");
            }

            Object exp = payload.get("exp");
            if (!(exp instanceof Number) || ((Number) exp).longValue() <= clock.instant().getEpochSecond()) {
                return Result.reject("the Visa has expired or has no exp");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> visa = (Map<String, Object>) payload.get("ga4gh_visa_v1");
            if (visa == null) {
                return Result.reject("no ga4gh_visa_v1 claim");
            }
            if (visa.containsKey("conditions")) {
                return Result.reject("conditions are present and are not evaluated here");
            }
            if (!"ControlledAccessGrants".equals(visa.get("type"))) {
                return Result.reject("type " + visa.get("type") + " is not ControlledAccessGrants");
            }
            Object by = visa.get("by");
            if (by == null || !acceptedBy.contains(by)) {
                return Result.reject("by " + by + " is not an accepted authority");
            }
            Object source = visa.get("source");
            if (!trustedSources.isEmpty() && !trustedSources.contains(source)) {
                return Result.reject("source " + source + " is not trusted");
            }
            if (!(visa.get("value") instanceof String value)) {
                return Result.reject("the Visa has no value");
            }
            return Result.accept(value, ((Number) exp).longValue(),
                    "signed by " + iss + " (kid " + kid + "), asserted by " + by + " at " + source);
        } catch (Exception e) {
            return Result.reject("the Visa could not be checked: " + e);
        }
    }

    private static Map<String, Object> decode(String segment) throws Exception {
        return JSON.readValue(B64URL.decode(segment), MAP);
    }

    @SuppressWarnings("unchecked")
    private static PublicKey findKey(String jwksJson, String kid) throws Exception {
        Map<String, Object> jwks = JSON.readValue(jwksJson, MAP);
        for (Object entry : (List<Object>) jwks.getOrDefault("keys", List.of())) {
            Map<String, Object> jwk = (Map<String, Object>) entry;
            if (kid.equals(jwk.get("kid")) && "EC".equals(jwk.get("kty")) && "P-256".equals(jwk.get("crv"))) {
                BigInteger x = new BigInteger(1, B64URL.decode((String) jwk.get("x")));
                BigInteger y = new BigInteger(1, B64URL.decode((String) jwk.get("y")));
                AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
                parameters.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
                return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
            }
        }
        return null;
    }
}
