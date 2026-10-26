package com.scivicslab.k8spups.passport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Gives a tool Pod read access to the datasets that its user's Visas grant, when the data is in an S3 server. The
 * S3 server has one read-only policy per dataset ({@code <policyPrefix><dataset>}). The controller makes a token
 * that names the policies of the granted datasets, exchanges it at the STS endpoint of the S3 server for temporary
 * credentials, and puts them in the environment of the Pod.
 */
public final class S3Access {

    /** Environment variable of the Pod that lists the datasets the credentials were made for. */
    public static final String DATASETS_ENV = "K8SPUPS_S3_DATASETS";

    private static final Logger LOG = Logger.getLogger(S3Access.class.getName());

    private final S3Tokens tokens;
    private final S3Sts sts;
    private final String endpoint;
    private final String bucket;
    private final String policyPrefix;
    private final int durationSeconds;

    public S3Access(S3Tokens tokens, S3Sts sts, String endpoint, String bucket, String policyPrefix, int durationSeconds) {
        this.tokens = tokens;
        this.sts = sts;
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.policyPrefix = policyPrefix;
        this.durationSeconds = durationSeconds;
    }

    public S3Tokens tokens() {
        return tokens;
    }

    /**
     * The environment for a Pod of {@code user}; empty when no dataset is granted or the credentials cannot be made.
     * When the S3 server refuses the token because it has no policy for one of the datasets, the datasets are tried
     * one by one and the credentials are made for those the S3 server knows, so one missing dataset does not take
     * the others away.
     */
    public Map<String, String> env(String user, Set<String> datasets) {
        if (datasets.isEmpty()) {
            return Map.of();
        }
        Set<String> sorted = new TreeSet<>(datasets);
        try {
            return build(user, sorted);
        } catch (Exception e) {
            if (sorted.size() == 1) {
                LOG.warning("S3 credentials for " + user + " could not be made: " + e);
                return Map.of();
            }
            Set<String> usable = new TreeSet<>();
            for (String dataset : sorted) {
                try {
                    sts.assumeRoleWithWebIdentity(tokens.mint(user, List.of(policyPrefix + dataset)), durationSeconds);
                    usable.add(dataset);
                } catch (Exception single) {
                    LOG.warning("The S3 server gave no access to dataset " + dataset + " for " + user + ": " + single);
                }
            }
            if (usable.isEmpty()) {
                return Map.of();
            }
            try {
                return build(user, usable);
            } catch (Exception again) {
                LOG.warning("S3 credentials for " + user + " could not be made: " + again);
                return Map.of();
            }
        }
    }

    private Map<String, String> build(String user, Set<String> sorted) throws Exception {
        List<String> policies = sorted.stream().map(d -> policyPrefix + d).toList();
        S3Sts.Credentials credentials = sts.assumeRoleWithWebIdentity(tokens.mint(user, policies), durationSeconds);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AWS_ACCESS_KEY_ID", credentials.accessKey());
        env.put("AWS_SECRET_ACCESS_KEY", credentials.secretKey());
        env.put("AWS_SESSION_TOKEN", credentials.sessionToken());
        env.put("AWS_ENDPOINT_URL", endpoint);
        env.put("AWS_DEFAULT_REGION", "us-east-1");
        env.put("K8SPUPS_S3_BUCKET", bucket);
        env.put(DATASETS_ENV, String.join(",", sorted));
        return env;
    }
}
