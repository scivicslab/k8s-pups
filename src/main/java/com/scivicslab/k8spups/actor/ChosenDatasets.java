package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.k8s.MountSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The datasets the user attached at launch, taken out of the requested mounts: whole datasets read as files, datasets
 * read from the S3 server, and the files granted one by one. The launch form sends each as a mount of a marker type;
 * what is left is the user's own storage and the groups' storage. Pure logic.
 *
 * @param files      names of the datasets to mount as files
 * @param s3         names of the datasets to read from the S3 server
 * @param grantedFiles whether the files granted one by one are attached
 * @param storage    the other requested mounts
 */
public record ChosenDatasets(Set<String> files, Set<String> s3, boolean grantedFiles, List<MountSpec> storage) {

    /** A dataset mounted as files; the dataset's name is in {@link MountSpec#sharedFrom()}. */
    public static final String DATASET = "chosen-dataset";
    /** A dataset read from the S3 server; the name is in {@link MountSpec#sharedFrom()}. */
    public static final String S3_DATASET = "chosen-s3-dataset";
    /** The files granted one by one. */
    public static final String GRANTED_FILES = "chosen-dataset-files";

    public static ChosenDatasets split(List<MountSpec> requested) {
        Set<String> files = new LinkedHashSet<>();
        Set<String> s3 = new LinkedHashSet<>();
        boolean granted = false;
        List<MountSpec> storage = new ArrayList<>();
        if (requested != null) {
            for (MountSpec m : requested) {
                switch (m.storageType()) {
                    case DATASET -> files.add(m.sharedFrom());
                    case S3_DATASET -> s3.add(m.sharedFrom());
                    case GRANTED_FILES -> granted = true;
                    default -> storage.add(m);
                }
            }
        }
        return new ChosenDatasets(files, s3, granted, storage);
    }

    public boolean any() {
        return !files.isEmpty() || !s3.isEmpty() || grantedFiles;
    }
}
