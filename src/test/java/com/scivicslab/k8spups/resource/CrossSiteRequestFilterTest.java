package com.scivicslab.k8spups.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decision of {@link CrossSiteRequestFilter}, which needs no running server. */
class CrossSiteRequestFilterTest {

    private static final String HOST = "192.168.5.25";

    @Test
    void aRequestThatOnlyReadsPassesWhateverSentIt() {
        assertTrue(CrossSiteRequestFilter.allowed("GET", "cross-site", "https://evil.example", HOST, null));
        assertTrue(CrossSiteRequestFilter.allowed("HEAD", "cross-site", null, HOST, null));
        assertTrue(CrossSiteRequestFilter.allowed("OPTIONS", "cross-site", "https://evil.example", HOST, null));
    }

    @Test
    void aPostFromAPageOfThisApplicationPasses() {
        assertTrue(CrossSiteRequestFilter.allowed("POST", "same-origin", "https://192.168.5.25", HOST, null));
        assertTrue(CrossSiteRequestFilter.allowed("DELETE", "same-origin", null, HOST, null));
        assertTrue(CrossSiteRequestFilter.allowed("PUT", "none", null, HOST, null));
    }

    @Test
    void aStateChangingRequestFromAnotherSiteIsRefused() {
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE", "post"}) {
            assertFalse(CrossSiteRequestFilter.allowed(method, "cross-site", "https://evil.example", HOST, null), method);
            assertFalse(CrossSiteRequestFilter.allowed(method, "same-site", "https://other.192.168.5.25", HOST, null), method);
        }
    }

    @Test
    void secFetchSiteSettlesItBeforeOriginIsRead() {
        // A forged Origin cannot rescue a request the browser reports as cross-site, nor a missing one condemn a same-origin request.
        assertFalse(CrossSiteRequestFilter.allowed("POST", "cross-site", "https://192.168.5.25", HOST, null));
        assertTrue(CrossSiteRequestFilter.allowed("POST", "same-origin", "https://evil.example", HOST, null));
        assertTrue(CrossSiteRequestFilter.allowed("POST", " Same-Origin ", null, HOST, null));
    }

    @Test
    void anOldBrowserIsJudgedByOrigin() {
        assertTrue(CrossSiteRequestFilter.allowed("POST", null, "https://192.168.5.25", HOST, null));
        assertTrue(CrossSiteRequestFilter.allowed("POST", "", "https://192.168.5.25", HOST, null));
        assertTrue(CrossSiteRequestFilter.allowed("POST", null, "https://sc.ddbj.nig.ac.jp", "pups-controller:8080", "sc.ddbj.nig.ac.jp"));
        assertFalse(CrossSiteRequestFilter.allowed("POST", null, "https://evil.example", HOST, null));
        assertFalse(CrossSiteRequestFilter.allowed("POST", null, "null", HOST, null));
    }

    @Test
    void aSenderThatIsNotABrowserPasses() {
        assertTrue(CrossSiteRequestFilter.allowed("POST", null, null, HOST, null));
        assertTrue(CrossSiteRequestFilter.allowed("DELETE", "", "  ", HOST, null));
    }
}
