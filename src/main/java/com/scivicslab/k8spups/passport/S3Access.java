package com.scivicslab.k8spups.passport;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** The bucket the datasets are read from, each under its name. */
    public String bucket() {
        return bucket;
    }

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

    /** Where the Pod's containers find the credentials Secret of the session. */
    public static final String CREDENTIALS_DIR = "/etc/k8s-pups-s3";
    /** The key of the Secret holding the AWS config, which reads the credentials with credential_process. */
    public static final String CONFIG_KEY = "config";
    /** The key of the Secret holding the credentials in the JSON form of credential_process. */
    public static final String CREDENTIALS_KEY = "credentials.json";

    /**
     * What a Pod of the user gets for the datasets of the S3 server: the environment (endpoint, bucket, datasets, the
     * AWS config and the rclone remote, nothing secret) and the credentials, which go into a Secret the controller
     * renews before {@code expiration}. The AWS CLI, boto3 and rclone read them through credential_process, again
     * whenever they come near their expiration.
     */
    public record Grant(Map<String, String> env, String credentialsJson, Instant expiration) {
    }

    /**
     * The grant for a Pod of {@code user}; empty when no dataset is granted or the credentials cannot be made. When
     * the S3 server refuses the token because it has no policy for one of the datasets, the datasets are tried one by
     * one and the credentials are made for those the S3 server knows, so one missing dataset does not take the others
     * away.
     */
    public Optional<Grant> grant(String user, Set<String> datasets) {
        if (datasets.isEmpty()) {
            return Optional.empty();
        }
        Set<String> sorted = new TreeSet<>(datasets);
        try {
            return Optional.of(build(user, sorted));
        } catch (Exception e) {
            if (sorted.size() == 1) {
                LOG.warning("S3 credentials for " + user + " could not be made: " + e);
                return Optional.empty();
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
                return Optional.empty();
            }
            try {
                return Optional.of(build(user, usable));
            } catch (Exception again) {
                LOG.warning("S3 credentials for " + user + " could not be made: " + again);
                return Optional.empty();
            }
        }
    }

    /** The AWS config of the Pod: the credentials come from the Secret, read again near their expiration. */
    public static String awsConfig() {
        return "[default]\ncredential_process = cat " + CREDENTIALS_DIR + "/" + CREDENTIALS_KEY + "\nregion = us-east-1\n";
    }

    private Grant build(String user, Set<String> sorted) throws Exception {
        List<String> policies = sorted.stream().map(d -> policyPrefix + d).toList();
        S3Sts.Credentials credentials = sts.assumeRoleWithWebIdentity(tokens.mint(user, policies), durationSeconds);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AWS_CONFIG_FILE", CREDENTIALS_DIR + "/" + CONFIG_KEY);
        env.put("AWS_ENDPOINT_URL", endpoint);
        env.put("AWS_DEFAULT_REGION", "us-east-1");
        env.put("K8SPUPS_S3_BUCKET", bucket);
        env.put(DATASETS_ENV, String.join(",", sorted));
        // An rclone remote named datasets, on the same server and credentials: rclone ls datasets:<bucket>/<dataset>
        env.put("RCLONE_CONFIG_DATASETS_TYPE", "s3");
        env.put("RCLONE_CONFIG_DATASETS_PROVIDER", "Other");
        env.put("RCLONE_CONFIG_DATASETS_ENV_AUTH", "true");
        env.put("RCLONE_CONFIG_DATASETS_ENDPOINT", endpoint);
        Instant expiration = Instant.parse(credentials.expiration());
        String json = "{\"Version\":1,\"AccessKeyId\":" + quote(credentials.accessKey())
            + ",\"SecretAccessKey\":" + quote(credentials.secretKey())
            + ",\"SessionToken\":" + quote(credentials.sessionToken())
            + ",\"Expiration\":" + quote(DateTimeFormatter.ISO_INSTANT.format(expiration)) + "}";
        return new Grant(env, json, expiration);
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        return sb.append('"').toString();
    }
}
