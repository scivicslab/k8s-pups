package com.scivicslab.k8spups.actor;

import com.scivicslab.k8spups.dataset.DatasetService.Readable;
import com.scivicslab.k8spups.k8s.MountSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The datasets the user attached at launch, taken out of the requested mounts. The launch form offers two of them: the
 * local datasets (read as files, with the files granted one by one) and the datasets of the S3 server. Each is attached
 * as a whole: attaching one gives every dataset of that kind the user may read now. The form sends each as a mount of a
 * marker type; what is left is the user's own storage and the groups' storage. Pure logic.
 *
 * @param files        names of the datasets to mount as files
 * @param s3           names of the datasets to read from the S3 server
 * @param grantedFiles whether the files granted one by one are attached
 * @param storage      the other requested mounts
 */
public record ChosenDatasets(Set<String> files, Set<String> s3, boolean grantedFiles, List<MountSpec> storage) {

    /** The local datasets: every dataset the user may read as files, and the files granted one by one. */
    public static final String LOCAL = "chosen-datasets-local";
    /** The datasets of the S3 server: every one the user may read there. */
    public static final String S3 = "chosen-datasets-s3";

    /**
     * @param requested    the mounts the launch form sent
     * @param readable     the datasets the user may read now
     * @param grantedFiles whether files are granted to the user one by one now
     */
    public static ChosenDatasets split(List<MountSpec> requested, List<Readable> readable, boolean grantedFiles) {
        boolean local = false;
        boolean s3 = false;
        List<MountSpec> storage = new ArrayList<>();
        if (requested != null) {
            for (MountSpec m : requested) {
                switch (m.storageType()) {
                    case LOCAL -> local = true;
                    case S3 -> s3 = true;
                    default -> storage.add(m);
                }
            }
        }
        Set<String> files = new LinkedHashSet<>();
        Set<String> s3Names = new LinkedHashSet<>();
        for (Readable r : readable) {
            if (r.s3() && s3) s3Names.add(r.name());
            if (!r.s3() && local) files.add(r.name());
        }
        return new ChosenDatasets(files, s3Names, local && grantedFiles, storage);
    }

    public boolean any() {
        return !files.isEmpty() || !s3.isEmpty() || grantedFiles;
    }
}
