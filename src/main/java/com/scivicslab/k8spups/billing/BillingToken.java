package com.scivicslab.k8spups.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Checks the token that the billing system sends in the header {@code Authorization: Bearer <token>}. The token is a
 * secret shared between the billing system and this controller; it is kept in Vault and handed to the controller as the
 * setting {@code k8spups.billing.api-token}. When no token is configured, nothing is accepted.
 */
public final class BillingToken {

    private static final String SCHEME = "bearer ";

    private BillingToken() {}

    /** True when the header carries exactly the expected token. The comparison takes the same time wherever the tokens differ. */
    public static boolean accepts(String authorizationHeader, String expectedToken) {
        if (expectedToken == null || expectedToken.isEmpty()) return false;
        if (authorizationHeader == null || authorizationHeader.length() <= SCHEME.length()) return false;
        if (!authorizationHeader.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) return false;
        String sent = authorizationHeader.substring(SCHEME.length());
        return MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8));
    }
}
