package com.scivicslab.k8spups.passport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.Config;

/**
 * Builds {@link PassportGrants} from the configuration. The Passport support is off unless
 * {@code k8spups.passport.enabled=true}; then the datasets granted by Visas are added to the datasets granted in
 * the entitlement database.
 */
public final class PassportConfig {

    private static final Logger LOG = Logger.getLogger(PassportConfig.class.getName());

    private PassportConfig() {
    }

    /** The configured grants, or empty when the Passport support is off or incompletely configured. */
    public static Optional<PassportGrants> fromConfig(Config cfg) {
        if (!cfg.getOptionalValue("k8spups.passport.enabled", Boolean.class).orElse(false)) {
            return Optional.empty();
        }
        String passportUrl = value(cfg, "k8spups.passport.passport-url");
        String issuer = value(cfg, "k8spups.passport.trusted-issuer");
        String jwksUri = value(cfg, "k8spups.passport.jwks-uri");
        String prefix = value(cfg, "k8spups.passport.dataset-prefix");
        if (passportUrl.isEmpty() || issuer.isEmpty() || jwksUri.isEmpty() || prefix.isEmpty()) {
            LOG.warning("k8spups.passport.enabled is true but passport-url, trusted-issuer, jwks-uri or dataset-prefix is "
                    + "missing; the Passport support stays off");
            return Optional.empty();
        }
        Set<String> acceptedBy = set(value(cfg, "k8spups.passport.accepted-by"), "dac");
        Set<String> trustedSources = set(value(cfg, "k8spups.passport.trusted-sources"), null);
        long cacheSeconds = cfg.getOptionalValue("k8spups.passport.cache-seconds", Long.class).orElse(30L);

        HttpPassportSource http = new HttpPassportSource(passportUrl);
        VisaCheck check = new VisaCheck(Map.of(issuer, jwksUri), acceptedBy, trustedSources,
                new CachedJwks(http, 60, Clock.systemUTC()), Clock.systemUTC());
        LOG.info("Passport support on: Visas of " + issuer + " grant datasets under " + prefix);
        return Optional.of(new PassportGrants(http, check, prefix, cacheSeconds, Clock.systemUTC()));
    }

    /**
     * The S3 access, when {@code k8spups.passport.s3.enabled=true}: the datasets that Visas grant are read from an S3
     * server with temporary credentials that the controller gets for the user (see {@link S3Access}).
     */
    public static Optional<S3Access> s3FromConfig(Config cfg) {
        if (!cfg.getOptionalValue("k8spups.passport.enabled", Boolean.class).orElse(false)
                || !cfg.getOptionalValue("k8spups.passport.s3.enabled", Boolean.class).orElse(false)) {
            return Optional.empty();
        }
        String endpoint = value(cfg, "k8spups.passport.s3.endpoint");
        String bucket = value(cfg, "k8spups.passport.s3.bucket");
        String issuer = value(cfg, "k8spups.passport.s3.issuer");
        if (endpoint.isEmpty() || bucket.isEmpty() || issuer.isEmpty()) {
            LOG.warning("k8spups.passport.s3.enabled is true but endpoint, bucket or issuer is missing; the S3 access stays off");
            return Optional.empty();
        }
        String audience = cfg.getOptionalValue("k8spups.passport.s3.audience", String.class).filter(s -> !s.isBlank()).orElse("k8s-pups");
        String policyPrefix = cfg.getOptionalValue("k8spups.passport.s3.policy-prefix", String.class).filter(s -> !s.isBlank()).orElse("jga-read-");
        int duration = cfg.getOptionalValue("k8spups.passport.s3.duration-seconds", Integer.class).orElse(900);
        String privateKeyFile = value(cfg, "k8spups.passport.s3.signing-key-file");
        String publicKeyFile = value(cfg, "k8spups.passport.s3.public-key-file");
        S3Tokens tokens;
        try {
            tokens = privateKeyFile.isEmpty() || publicKeyFile.isEmpty()
                    ? S3Tokens.generate(issuer, audience, duration, Clock.systemUTC())
                    : S3Tokens.fromPem(Files.readString(Path.of(privateKeyFile)), Files.readString(Path.of(publicKeyFile)),
                            issuer, audience, duration, Clock.systemUTC());
        } catch (Exception e) {
            LOG.warning("The signing key of the S3 tokens could not be read; the S3 access stays off: " + e);
            return Optional.empty();
        }
        LOG.info("S3 access on: datasets that Visas grant are read from bucket " + bucket + " at " + endpoint);
        return Optional.of(new S3Access(tokens, new S3Sts(endpoint, S3Sts.http()), endpoint, bucket, policyPrefix, duration));
    }

    private static String value(Config cfg, String key) {
        return cfg.getOptionalValue(key, String.class).map(String::trim).orElse("");
    }

    private static Set<String> set(String commaSeparated, String fallback) {
        String text = commaSeparated.isEmpty() && fallback != null ? fallback : commaSeparated;
        return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** Keeps a fetched JSON Web Key Set for a short time, so that one check of a Passport fetches it once. */
    static final class CachedJwks implements VisaCheck.JwksSource {

        private record Entry(String body, long fetchedAt) {
        }

        private final VisaCheck.JwksSource origin;
        private final long seconds;
        private final Clock clock;
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();

        CachedJwks(VisaCheck.JwksSource origin, long seconds, Clock clock) {
            this.origin = origin;
            this.seconds = seconds;
            this.clock = clock;
        }

        @Override
        public String fetch(String jwksUri) throws Exception {
            long now = clock.instant().getEpochSecond();
            Entry entry = entries.get(jwksUri);
            if (entry != null && now - entry.fetchedAt() < seconds) {
                return entry.body();
            }
            String body = origin.fetch(jwksUri);
            entries.put(jwksUri, new Entry(body, now));
            return body;
        }
    }
}
