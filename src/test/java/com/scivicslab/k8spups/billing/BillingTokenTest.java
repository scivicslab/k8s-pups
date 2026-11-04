package com.scivicslab.k8spups.billing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingTokenTest {

    @Test
    void theHeaderWithTheExpectedTokenIsAccepted() {
        assertTrue(BillingToken.accepts("Bearer s3cret-token", "s3cret-token"));
    }

    @Test
    void aDifferentTokenIsRefused() {
        assertFalse(BillingToken.accepts("Bearer other-token", "s3cret-token"));
    }

    @Test
    void aTokenThatOnlyStartsLikeTheExpectedOneIsRefused() {
        assertFalse(BillingToken.accepts("Bearer s3cret-token-and-more", "s3cret-token"));
        assertFalse(BillingToken.accepts("Bearer s3cret", "s3cret-token"));
    }

    @Test
    void aMissingHeaderIsRefused() {
        assertFalse(BillingToken.accepts(null, "s3cret-token"));
        assertFalse(BillingToken.accepts("", "s3cret-token"));
    }

    @Test
    void aHeaderOfAnotherSchemeIsRefused() {
        assertFalse(BillingToken.accepts("Basic s3cret-token", "s3cret-token"));
        assertFalse(BillingToken.accepts("s3cret-token", "s3cret-token"));
    }

    @Test
    void nothingIsAcceptedWhenNoTokenIsConfigured() {
        assertFalse(BillingToken.accepts("Bearer ", ""));
        assertFalse(BillingToken.accepts("Bearer anything", ""));
        assertFalse(BillingToken.accepts("Bearer anything", null));
    }

    @Test
    void theSchemeNameIsNotCaseSensitive() {
        assertTrue(BillingToken.accepts("bearer s3cret-token", "s3cret-token"));
    }
}
