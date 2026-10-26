package com.scivicslab.k8spups.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class DatasetServiceUnionTest {

    @Test
    void theDatabaseDatasetsComeFirstAndNothingIsListedTwice() {
        assertEquals(List.of("JGAD000001", "JGAD000002", "JGAD000003"),
                DatasetService.union(List.of("JGAD000001", "JGAD000002"), List.of("JGAD000002", "JGAD000003")));
    }

    @Test
    void anEmptySecondListChangesNothing() {
        assertEquals(List.of("JGAD000001"), DatasetService.union(List.of("JGAD000001"), List.of()));
    }
}
