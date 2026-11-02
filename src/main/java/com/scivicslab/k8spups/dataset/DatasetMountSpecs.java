package com.scivicslab.k8spups.dataset;

import java.util.ArrayList;
import java.util.List;

import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.k8s.MountSpec;

/**
 * Where the datasets that a user may read are mounted in a tool Pod. The controller mounts all of them at the start of
 * every tool, read-only; the user does not choose them. Each dataset is mounted at {@code <directory>/<dataset name>},
 * where the directory is {@code datasets} under the directory that the tool shows ({@code extraMountsUnder}: its home,
 * or {@code /srv} for File Browser), or {@code /datasets} for a tool that shows none. The files granted one by one
 * are mounted at {@code <directory>/granted-files}.
 */
public final class DatasetMountSpecs {

    /** Name of the directory of the files granted one by one, inside the directory of the datasets. */
    public static final String GRANTED_FILES = "granted-files";

    private DatasetMountSpecs() {}

    /** The directory of the datasets of a tool that shows {@code mountsUnder} (null = it shows none). */
    public static String directory(String mountsUnder) {
        String base = mountsUnder == null ? "" : mountsUnder.replaceAll("/+$", "");
        return base + "/datasets";
    }

    /**
     * @param mountsUnder the directory that the tool shows, or null
     * @param datasets    the datasets the user may read now
     * @param fileView    whether files are granted one by one
     */
    public static List<MountSpec> of(String mountsUnder, List<String> datasets, boolean fileView) {
        String directory = directory(mountsUnder);
        List<MountSpec> mounts = new ArrayList<>();
        for (String dataset : datasets) {
            mounts.add(new MountSpec(K8sApiClient.DATASET_ROOT_STORAGE_TYPE, directory + "/" + dataset, null, dataset, true));
        }
        if (fileView) {
            mounts.add(new MountSpec(K8sApiClient.DATASET_STORAGE_TYPE, directory + "/" + GRANTED_FILES, null, null, true));
        }
        return mounts;
    }
}
