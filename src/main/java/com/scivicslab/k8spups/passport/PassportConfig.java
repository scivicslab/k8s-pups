package com.scivicslab.k8spups.passport;

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
