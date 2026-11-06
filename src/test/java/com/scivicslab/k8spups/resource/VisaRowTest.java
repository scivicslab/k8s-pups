package com.scivicslab.k8spups.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.k8spups.passport.PassportGrants;

import org.junit.jupiter.api.Test;

class VisaRowTest {

    @Test
    void theVisaSummaryIsSplitIntoItsParts() {
        // the form PassportGrants writes a Visa's summary in
        var d = new PassportGrants.Decision("https://example.org/datasets/JGAD000003 | by dac | source https://example.org/committees/0001"
            + " | alg RS256 | issuer http://ga4gh-visa-issuer.k8s-pups-with-audit.svc:8080", true, "grants the dataset JGAD000003");
        var row = DashboardResource.VisaRow.of("the Visa issuer", d);
        assertEquals("https://example.org/datasets/JGAD000003", row.grants());
        assertEquals("dac", row.by());
        assertEquals("https://example.org/committees/0001", row.source());
        assertEquals("http://ga4gh-visa-issuer.k8s-pups-with-audit.svc:8080", row.issuer());
        assertTrue(row.accepted());
        assertEquals("the Visa issuer", row.where());
    }
}
