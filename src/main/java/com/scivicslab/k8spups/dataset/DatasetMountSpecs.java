package com.scivicslab.k8spups.dataset;

import java.util.ArrayList;
import java.util.List;

import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.k8s.MountSpec;

/**
 * Where the local datasets (those read as files) are mounted in a tool Pod when the user attached them, read-only.
 * Each dataset is mounted at {@code <directory>/<dataset name>}, where the directory is {@code datasets-local} under
 * the directory that the tool shows ({@code extraMountsUnder}: its home, or {@code /srv} for File Browser), or
 * {@code /datasets-local} for a tool that shows none. The files granted one by one are mounted at
 * {@code <directory>/granted-files}.
 */
public final class DatasetMountSpecs {

    /** Name of the directory of the files granted one by one, inside the directory of the datasets. */
    public static final String GRANTED_FILES = "granted-files";

    private DatasetMountSpecs() {}

    /** The directory of the datasets of a tool that shows {@code mountsUnder} (null = it shows none). */
    public static String directory(String mountsUnder) {
        String base = mountsUnder == null ? "" : mountsUnder.replaceAll("/+$", "");
        return base + "/datasets-local";
    }

    /** The directory where a tool that shows {@code mountsUnder} (null = none) sees the datasets of the S3 server. */
    public static String s3Directory(String mountsUnder) {
        String base = mountsUnder == null ? "" : mountsUnder.replaceAll("/+$", "");
        return base + "/datasets-S3";
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
