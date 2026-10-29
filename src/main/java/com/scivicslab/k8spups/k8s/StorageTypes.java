package com.scivicslab.k8spups.k8s;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Names and one-line descriptions shown to users for the storage types a deployment offers
 * ({@code k8spups.storage-type-options}).
 *
 * The storage types are the same on every cluster: {@code posix} (shared file storage, a POSIX file system that several
 * tools use at the same time) and {@code longhorn} (block storage). What backs {@code posix} differs between clusters
 * (an NFS server on W206, a Lustre StorageClass on a007) and is set by {@code k8spups.posix.backend}, not by the key.
 * The deployment chooses only which keys it offers. The dashboard and the Storage Settings tool show these
 * names, and show no storage type the deployment does not offer.
 */
public final class StorageTypes {

    private static final Map<String, String> NAMES = Map.of(
            "posix", "Shared file storage",
            "longhorn", "Block storage");

    private static final String SHARED = "Files that several of your tools can use at the same time.";
    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "posix", SHARED,
            "longhorn", "Faster storage that one tool at a time can use.");

    private StorageTypes() {}

    /** The name shown for a storage type key; an unknown key is shown as it is. */
    public static String name(String type) {
        return NAMES.getOrDefault(type, type);
    }

    /** The one-line description shown for a storage type key; empty for an unknown key. */
    public static String description(String type) {
        return DESCRIPTIONS.getOrDefault(type, "");
    }

    /** Key to description for the offered types, in the order the deployment lists them. */
    public static Map<String, String> descriptions(List<String> offered) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String type : offered) out.put(type, description(type));
        return out;
    }

    /** Key to name for the offered types, in the order the deployment lists them. */
    public static Map<String, String> names(List<String> offered) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String type : offered) out.put(type, name(type));
        return out;
    }
}
