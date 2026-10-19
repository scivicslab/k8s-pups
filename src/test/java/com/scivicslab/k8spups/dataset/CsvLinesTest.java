package com.scivicslab.k8spups.dataset;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvLinesTest {

    @Test
    void catalogAcceptsBothForms() {
        var p = CsvLines.catalog(List.of("# header", "", "JGAD000001,JGAD000001/a.bam", "JGAD000002/b/c.vcf.gz"));
        assertTrue(p.errors().isEmpty(), p.errors().toString());
        assertEquals(new CsvLines.CatalogEntry("JGAD000001", "JGAD000001/a.bam"), p.entries().get(0));
        assertEquals(new CsvLines.CatalogEntry("JGAD000002", "JGAD000002/b/c.vcf.gz"), p.entries().get(1));
    }

    @Test
    void catalogReportsBadLinesWithTheirNumbers() {
        var p = CsvLines.catalog(List.of("JGAD000001,JGAD000002/x", "JGAD000001,../x", "a,b,c", "onlyname", "JGAD000003,JGAD000003/ok"));
        assertEquals(1, p.entries().size());
        assertEquals(4, p.errors().size());
        assertTrue(p.errors().get(0).startsWith("line 1"));
    }

    @Test
    void accessReadsTheOptionalLastDay() {
        var p = CsvLines.access(List.of("alice,JGAD000001", "bob,JGAD000002,2030-01-31"));
        assertTrue(p.errors().isEmpty());
        assertNull(p.entries().get(0).expiresAt());
        assertEquals(Instant.parse("2030-02-01T00:00:00Z"), p.entries().get(1).expiresAt());
    }

    @Test
    void accessReportsUnsafeNamesAndBadDates() {
        var p = CsvLines.access(List.of("a b,JGAD000001", "alice,JGAD/1", "alice,JGAD000001,soon", "alice"));
        assertTrue(p.entries().isEmpty());
        assertEquals(4, p.errors().size());
    }
}
