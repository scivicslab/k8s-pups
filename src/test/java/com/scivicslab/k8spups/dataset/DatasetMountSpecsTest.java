package com.scivicslab.k8spups.dataset;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.k8s.MountSpec;

class DatasetMountSpecsTest {

    @Test
    void everyDatasetIsMountedReadOnlyUnderTheDirectoryTheToolShows() {
        var m = DatasetMountSpecs.of("/srv", List.of("JGAD000001", "JGAD000002"), false);
        assertEquals(List.of("/srv/datasets/JGAD000001", "/srv/datasets/JGAD000002"),
            m.stream().map(MountSpec::mountPath).toList());
        assertTrue(m.stream().allMatch(x -> x.readOnly() && K8sApiClient.DATASET_ROOT_STORAGE_TYPE.equals(x.storageType())));
        assertEquals(List.of("JGAD000001", "JGAD000002"), m.stream().map(MountSpec::subPath).toList());
    }

    @Test
    void aToolThatShowsNoDirectoryGetsTheDatasetsAtTheRoot() {
        var m = DatasetMountSpecs.of(null, List.of("JGAD000001"), false);
        assertEquals("/datasets/JGAD000001", m.get(0).mountPath());
        assertEquals("/home/jovyan/datasets", DatasetMountSpecs.directory("/home/jovyan/"));
    }

    @Test
    void theFilesGrantedOneByOneAreMountedNextToTheDatasets() {
        var m = DatasetMountSpecs.of("/home/user", List.of("JGAD000001"), true);
        assertEquals(2, m.size());
        MountSpec view = m.get(1);
        assertEquals("/home/user/datasets/granted-files", view.mountPath());
        assertEquals(K8sApiClient.DATASET_STORAGE_TYPE, view.storageType());
        assertTrue(view.readOnly());
    }

    @Test
    void nothingIsMountedWhenNothingIsGranted() {
        assertEquals(List.of(), DatasetMountSpecs.of("/srv", List.of(), false));
    }
}
