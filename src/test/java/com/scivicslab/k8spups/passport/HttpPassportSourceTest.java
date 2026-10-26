package com.scivicslab.k8spups.passport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HttpPassportSourceTest {

    @Test
    void theUserNameIsEncodedForAUrlPath() {
        assertEquals("http://x/passport/e2eadmin", HttpPassportSource.urlFor("http://x/passport/{sub}", "e2eadmin"));
        assertEquals("http://x/passport/a%20b%2Fc", HttpPassportSource.urlFor("http://x/passport/{sub}", "a b/c"));
    }
}
