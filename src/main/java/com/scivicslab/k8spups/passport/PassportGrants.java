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
 *
 * <p>The Visas can also be <b>supplied</b> by the caller (see {@link #supply}): the controller takes them from the
 * UserInfo response of the user's login and hands them over when the user starts a tool. A supplied result holds
 * until the earliest expiry of its Visas or until the next supply for the user. The datasets of both ways are added.
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

    private final PassportSource source; // null when the Visas are only supplied by the caller
    private final boolean suppliedVisas;
    private final VisaCheck check;
    private final String datasetPrefix;
    private final long cacheSeconds;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Entry> supplied = new ConcurrentHashMap<>();

    public PassportGrants(PassportSource source, VisaCheck check, String datasetPrefix, long cacheSeconds, Clock clock) {
        this(source, false, check, datasetPrefix, cacheSeconds, clock);
    }

    /**
     * @param source        fetches the Passport of a user from a Visa issuer; null when the Visas are only supplied
     * @param suppliedVisas whether the caller supplies the Visas of users (see {@link #supply})
     */
    public PassportGrants(PassportSource source, boolean suppliedVisas, VisaCheck check, String datasetPrefix,
            long cacheSeconds, Clock clock) {
        this.source = source;
        this.suppliedVisas = suppliedVisas;
        this.check = check;
        this.datasetPrefix = datasetPrefix;
        this.cacheSeconds = cacheSeconds;
        this.clock = clock;
    }

    /** Whether the caller is expected to supply the Visas of users (the Visas come from the UserInfo response). */
    public boolean acceptsSuppliedVisas() {
        return suppliedVisas;
    }

    /**
     * Takes the Visas of a user from the caller, replacing what was supplied before. The Visas are checked like fetched
     * ones; the result holds until the earliest expiry of the accepted Visas. A supply without an accepted Visa
     * removes the datasets of the earlier supply.
     */
    public void supply(String user, List<String> visas) {
        long now = clock.instant().getEpochSecond();
        Result result = evaluate(user, visas);
        supplied.put(user, new Entry(result.datasets(), now, result.validUntil()));
    }

    /** The names of the datasets that the Visas of the user grant now, from the issuer and from the supplied Visas. */
    public Set<String> datasets(String user) {
        Set<String> all = new LinkedHashSet<>();
        if (source != null) {
            all.addAll(fetched(user));
        }
        Entry entry = supplied.get(user);
        if (entry != null) {
            if (clock.instant().getEpochSecond() < entry.validUntil()) {
                all.addAll(entry.datasets());
            } else {
                supplied.remove(user);
            }
        }
        return all;
    }

    private record Result(Set<String> datasets, long validUntil) {
    }

    /** Checks the Visas and returns the datasets they grant with the earliest expiry among the accepted Visas. */
    private Result evaluate(String user, List<String> visas) {
        Set<String> datasets = new LinkedHashSet<>();
        long validUntil = Long.MAX_VALUE;
        for (String visa : visas) {
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
        return new Result(datasets, validUntil);
    }

    private Set<String> fetched(String user) {
        long now = clock.instant().getEpochSecond();
        Entry cached = cache.get(user);
        if (cached != null && now - cached.fetchedAt() < cacheSeconds) {
            return cached.datasets();
        }
        try {
            Result result = evaluate(user, source.visas(user));
            Set<String> datasets = result.datasets();
            long validUntil = result.validUntil();
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
