package com.scivicslab.k8spups.storage;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rules for the storage administrators give to users, without the database or Kubernetes.
 *
 * Users get storage only through an allocation; the controller creates the PVC of an active allocation with the type
 * and size written there. Which allocation types can be given back after revoking depends on the StorageClass: a
 * Lustre CSI PV is kept (Retain), and the NFS directory of nfs-k8s is kept on the NFS server, so both can be restored;
 * a Longhorn volume is deleted with its PVC (reclaimPolicy Delete), so it cannot.
 */
public final class StoragePolicy {

    /** The storage a user gets without an administrator's action: the default allocation. */
    public record Grant(String type, String size) {}

    private static final Pattern SIZE = Pattern.compile("[1-9][0-9]{0,5}(Gi|Ti)");
    private static final List<String> ALLOCATABLE = List.of("lustre-csi", "longhorn", "nfs-k8s");
    private static final List<String> RESTORABLE = List.of("lustre-csi", "nfs-k8s");

    private StoragePolicy() {}

    /** A size an administrator may give: a whole number of Gi or Ti. */
    public static boolean validSize(String size) {
        return size != null && SIZE.matcher(size).matches();
    }

    /** A type that can be allocated: offered by the deployment, and one the controller can create a PVC for. */
    public static boolean allocatable(String type, List<String> offered) {
        return type != null && offered.contains(type) && ALLOCATABLE.contains(type);
    }

    /** Whether the storage of a revoked allocation of this type can be given back. */
    public static boolean restorable(String type) {
        return RESTORABLE.contains(type);
    }

    /**
     * The default allocation from {@code k8spups.storage.default-allocation}, written {@code <type>:<size>}
     * (e.g. {@code lustre-csi:50Gi}); empty when unset. A value that is not allocatable is an error, so a typing
     * mistake stops the controller instead of giving no one storage.
     */
    public static Optional<Grant> parseDefault(String value, List<String> offered) {
        if (value == null || value.isBlank()) return Optional.empty();
        String[] parts = value.trim().split(":", 2);
        if (parts.length != 2 || !allocatable(parts[0], offered) || !validSize(parts[1])) {
            throw new IllegalArgumentException("k8spups.storage.default-allocation must be <type>:<size> with a type of "
                + offered + " and a size such as 50Gi: " + value);
        }
        return Optional.of(new Grant(parts[0], parts[1]));
    }

    private static final Pattern GROUP = Pattern.compile("[a-z][a-z0-9]{1,30}");

    /**
     * A group name: lowercase letters and digits, starting with a letter. It becomes part of the PVC name and the
     * default mount path {@code /groups/<name>}, so it carries no characters that need escaping in either.
     */
    public static boolean validGroupName(String name) {
        return name != null && GROUP.matcher(name).matches();
    }

    /** A member's mode: "rw" read-write or "ro" read-only. */
    public static boolean validMode(String mode) {
        return "rw".equals(mode) || "ro".equals(mode);
    }

    /** Gibibytes of a size accepted by {@link #validSize}. */
    public static long gibibytes(String size) {
        long n = Long.parseLong(size.substring(0, size.length() - 2));
        return size.endsWith("Ti") ? n * 1024 : n;
    }
}
