package com.scivicslab.k8spups.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.k8spups.dataset.DatasetService.Readable;
import com.scivicslab.k8spups.k8s.MountSpec;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ChosenDatasetsTest {

    private static final List<Readable> READABLE = List.of(
        new Readable("JGAD000001", false), new Readable("JGAD000003", false), new Readable("JGAS000001", true));

    @Test
    void attachingTheLocalDatasetsGivesEveryLocalDatasetAndTheGrantedFiles() {
        var c = ChosenDatasets.split(List.of(
            new MountSpec("group", "/w/groups/lab1", "lab1"),
            new MountSpec(ChosenDatasets.LOCAL, "/w/datasets-local", null),
            new MountSpec("posix", "/w/storage/posix", null)), READABLE, true);
        assertEquals(Set.of("JGAD000001", "JGAD000003"), c.files());
        assertEquals(Set.of(), c.s3());
        assertTrue(c.grantedFiles());
        assertEquals(List.of("group", "posix"), c.storage().stream().map(MountSpec::storageType).toList());
        assertTrue(c.any());
    }

    @Test
    void attachingTheS3DatasetsGivesEveryS3DatasetOnly() {
        var c = ChosenDatasets.split(List.of(new MountSpec(ChosenDatasets.S3, "/w/datasets-S3", null)), READABLE, true);
        assertEquals(Set.of(), c.files());
        assertEquals(Set.of("JGAS000001"), c.s3());
        assertFalse(c.grantedFiles());
        assertTrue(c.storage().isEmpty());
    }

    @Test
    void nothingAttachedMeansNoDataset() {
        var c = ChosenDatasets.split(List.of(new MountSpec("group", "/w/groups/lab1", "lab1")), READABLE, true);
        assertFalse(c.any());
        assertEquals(1, c.storage().size());
        assertFalse(ChosenDatasets.split(null, READABLE, true).any());
    }
}
