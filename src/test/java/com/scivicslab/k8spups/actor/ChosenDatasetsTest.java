package com.scivicslab.k8spups.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.k8spups.k8s.MountSpec;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ChosenDatasetsTest {

    @Test
    void theAttachedDatasetsAreTakenOutOfTheStorageMounts() {
        var c = ChosenDatasets.split(List.of(
            new MountSpec("group", "/w/groups/lab1", "lab1"),
            new MountSpec(ChosenDatasets.DATASET, "/w/datasets/JGAD000003", "JGAD000003"),
            new MountSpec(ChosenDatasets.S3_DATASET, "", "JGAS000001"),
            new MountSpec(ChosenDatasets.GRANTED_FILES, "/w/datasets/granted-files", null),
            new MountSpec("posix", "/w/storage/posix", null)));
        assertEquals(Set.of("JGAD000003"), c.files());
        assertEquals(Set.of("JGAS000001"), c.s3());
        assertTrue(c.grantedFiles());
        assertEquals(List.of("group", "posix"), c.storage().stream().map(MountSpec::storageType).toList());
        assertTrue(c.any());
    }

    @Test
    void nothingAttachedMeansNoDataset() {
        var c = ChosenDatasets.split(List.of(new MountSpec("group", "/w/groups/lab1", "lab1")));
        assertFalse(c.any());
        assertEquals(1, c.storage().size());
        assertFalse(ChosenDatasets.split(null).any());
    }
}
