package com.scivicslab.k8spups.passport;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The datasets a user may read according to the Visas of the user's Passport. The Passport is fetched from the
 * Visa issuer, every Visa is checked with {@link VisaCheck}, and a Visa whose {@code value} is the dataset URL prefix
 * followed by a dataset name grants that dataset. The result is cached for a short time.
 *
 * <p>When the Visa issuer cannot be reached, the last result stays in force until the earliest expiry of the Visas
 * it was made from, and then it is empty. A short outage does not stop the sessions, a long one does.
 */
public final class PassportGrants {

    /** Returns the Visa JSON Web Tokens of the Passport of a user. */
    @FunctionalInterface
    public interface PassportSource {
        List<String> visas(String user) throws Exception;
    }

    private record Entry(Set<String> datasets, long fetchedAt, long validUntil) {
    }

    private static final Logger LOG = Logger.getLogger(PassportGrants.class.getName());

    private final PassportSource source;
    private final VisaCheck check;
    private final String datasetPrefix;
    private final long cacheSeconds;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public PassportGrants(PassportSource source, VisaCheck check, String datasetPrefix, long cacheSeconds, Clock clock) {
        this.source = source;
        this.check = check;
        this.datasetPrefix = datasetPrefix;
        this.cacheSeconds = cacheSeconds;
        this.clock = clock;
    }

    /** The names of the datasets that the Visas of the user grant now. */
    public Set<String> datasets(String user) {
        long now = clock.instant().getEpochSecond();
        Entry cached = cache.get(user);
        if (cached != null && now - cached.fetchedAt() < cacheSeconds) {
            return cached.datasets();
        }
        try {
            Set<String> datasets = new LinkedHashSet<>();
            long validUntil = Long.MAX_VALUE;
            for (String visa : source.visas(user)) {
                VisaCheck.Result result = check.check(visa);
                if (!result.accepted()) {
                    LOG.info("Visa of " + user + " rejected: " + result.reason());
                    continue;
                }
                String dataset = datasetOf(result.value());
                if (dataset == null) {
                    LOG.info("Visa of " + user + " grants " + result.value() + ", which is not under " + datasetPrefix);
                    continue;
                }
                datasets.add(dataset);
                validUntil = Math.min(validUntil, result.expiresAt());
            }
            cache.put(user, new Entry(datasets, now, validUntil));
            return datasets;
        } catch (Exception e) {
            LOG.warning("Passport of " + user + " could not be fetched: " + e);
            if (cached != null && now < cached.validUntil()) {
                return cached.datasets();
            }
            cache.remove(user);
            return Set.of();
        }
    }

    /** The dataset name in a dataset URL, or null when the URL is not a dataset under the prefix. */
    String datasetOf(String value) {
        if (!value.startsWith(datasetPrefix)) {
            return null;
        }
        String name = value.substring(datasetPrefix.length());
        return name.isEmpty() || name.contains("/") ? null : name;
    }
}
