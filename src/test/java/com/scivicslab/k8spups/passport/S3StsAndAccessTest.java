package com.scivicslab.k8spups.passport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The STS call and the environment of a Pod. No network. */
class S3StsAndAccessTest {

    private static final String RESPONSE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <AssumeRoleWithWebIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
              <AssumeRoleWithWebIdentityResult>
                <Credentials>
                  <AccessKeyId>AKIA-TEST</AccessKeyId>
                  <SecretAccessKey>secret-test</SecretAccessKey>
                  <SessionToken>session-test</SessionToken>
                  <Expiration>2026-10-26T11:26:34Z</Expiration>
                </Credentials>
              </AssumeRoleWithWebIdentityResult>
            </AssumeRoleWithWebIdentityResponse>
            """;

    private final S3Tokens tokens = S3Tokens.generate("http://c/s3-oidc", "k8s-pups", 900, Clock.systemUTC());

    @Test
    void theStsResponseIsParsed() throws Exception {
        S3Sts.Credentials c = S3Sts.parse(RESPONSE);
        assertEquals("AKIA-TEST", c.accessKey());
        assertEquals("secret-test", c.secretKey());
        assertEquals("session-test", c.sessionToken());
        assertEquals("2026-10-26T11:26:34Z", c.expiration());
    }

    @Test
    void aResponseWithADoctypeIsRefused() {
        String evil = "<!DOCTYPE x [<!ENTITY a \"b\">]>" + RESPONSE;
        try {
            S3Sts.parse(evil);
            throw new AssertionError("a document with a DOCTYPE must be refused");
        } catch (Exception expected) {
            // refused
        }
    }

    @Test
    void theTokenGoesInTheBodyOfThePostNotInTheUrl() throws Exception {
        List<String[]> calls = new ArrayList<>();
        S3Sts sts = new S3Sts("http://minio:9000/", (url, form) -> { calls.add(new String[] {url, form}); return RESPONSE; });
        sts.assumeRoleWithWebIdentity("a.b.c", 900);
        assertEquals("http://minio:9000/", calls.get(0)[0]);
        String form = calls.get(0)[1];
        assertTrue(form.contains("Action=AssumeRoleWithWebIdentity"));
        assertTrue(form.contains("DurationSeconds=900"));
        assertEquals("a.b.c", URLDecoder.decode(form.replaceAll(".*WebIdentityToken=", ""), StandardCharsets.UTF_8));
    }

    @Test
    void thePodGetsCredentialsForExactlyTheGrantedDatasets() {
        List<String> forms = new ArrayList<>();
        S3Sts sts = new S3Sts("http://minio:9000/", (url, form) -> { forms.add(form); return RESPONSE; });
        S3Access access = new S3Access(tokens, sts, "http://minio:9000", "jga", "jga-read-", 900);
        Map<String, String> env = access.env("e2eadmin", Set.of("JGAS000002", "JGAS000001"));
        assertEquals("AKIA-TEST", env.get("AWS_ACCESS_KEY_ID"));
        assertEquals("secret-test", env.get("AWS_SECRET_ACCESS_KEY"));
        assertEquals("session-test", env.get("AWS_SESSION_TOKEN"));
        assertEquals("http://minio:9000", env.get("AWS_ENDPOINT_URL"));
        assertEquals("jga", env.get("K8SPUPS_S3_BUCKET"));
        assertEquals("JGAS000001,JGAS000002", env.get(S3Access.DATASETS_ENV));
        // The token the S3 server received names the policies of both datasets, in order.
        String token = URLDecoder.decode(forms.get(0).replaceAll(".*WebIdentityToken=", ""), StandardCharsets.UTF_8);
        String claims = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"policy\":\"jga-read-JGAS000001,jga-read-JGAS000002\""), claims);
    }

    @Test
    void withoutADatasetNoStsCallIsMade() {
        S3Sts sts = new S3Sts("http://minio:9000/", (url, form) -> { throw new AssertionError("no call expected"); });
        assertTrue(new S3Access(tokens, sts, "http://minio:9000", "jga", "jga-read-", 900).env("u", Set.of()).isEmpty());
    }

    @Test
    void whenTheStsCallFailsThePodGetsNoCredentials() {
        S3Sts sts = new S3Sts("http://minio:9000/", (url, form) -> { throw new java.io.IOException("refused"); });
        Map<String, String> env = new S3Access(tokens, sts, "http://minio:9000", "jga", "jga-read-", 900).env("u", Set.of("JGAS000001"));
        assertFalse(env.containsKey("AWS_ACCESS_KEY_ID"));
        assertTrue(env.isEmpty());
    }

    @Test
    void aDatasetThatTheS3ServerHasNoPolicyForDoesNotTakeTheOthersAway() {
        // The fake S3 server refuses any token whose policy claim names jga-read-JGAS000099.
        S3Sts sts = new S3Sts("http://minio:9000/", (url, form) -> {
            String token = URLDecoder.decode(form.replaceAll(".*WebIdentityToken=", ""), StandardCharsets.UTF_8);
            String claims = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
            if (claims.contains("jga-read-JGAS000099")) throw new IllegalStateException("policy not found");
            return RESPONSE;
        });
        S3Access access = new S3Access(tokens, sts, "http://minio:9000", "jga", "jga-read-", 900);
        Map<String, String> env = access.env("u", Set.of("JGAS000002", "JGAS000099"));
        assertEquals("JGAS000002", env.get(S3Access.DATASETS_ENV));
        assertEquals("AKIA-TEST", env.get("AWS_ACCESS_KEY_ID"));
        assertTrue(access.env("u", Set.of("JGAS000099")).isEmpty());
    }
}
