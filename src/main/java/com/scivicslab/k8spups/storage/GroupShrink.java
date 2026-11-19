package com.scivicslab.k8spups.storage;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rules for making a group's storage smaller, without the database or Kubernetes.
 *
 * The size of a group's storage on a007 is a Lustre project quota (hard block limit) that the exa CSI driver sets
 * when it provisions the volume. Kubernetes refuses to make a PVC smaller, and changing the PV's numbers does not
 * change the quota; only an administrator of the file system can lower it, with {@code lfs setquota}. The controller
 * has no such right. A shrink is therefore done in two steps:
 *
 * <ol>
 *   <li>prepare: the storage is taken away (the PVC is deleted, the PV is kept and recorded) and the wanted size is
 *       remembered ({@code storage_group.shrink_to}); the administrator is shown the commands to run on a007;</li>
 *   <li>continue: after the administrator ran them, the PV is given the new capacity and a PVC of the new size is
 *       bound to it again; the group's size becomes the new size.</li>
 * </ol>
 *
 * A pending shrink can be cancelled; the group is then taken away at its old size and can be given back as usual.
 * The ordinary give-back is refused while a shrink is pending: if the quota was already lowered, giving the storage
 * back at the old size would make Kubernetes report more than the file system allows.
 */
public final class GroupShrink {

    private GroupShrink() {}

    /** The Lustre project quota of a PV of the exa CSI driver: the project ID and the file system it is on. */
    public record LustreQuota(String projectId, String filesystem) {

        private static final Pattern PROJECT_ID = Pattern.compile("[0-9]{1,10}");
        private static final Pattern FILESYSTEM = Pattern.compile("/[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*");

        /**
         * The quota from the PV's {@code volumeAttributes}: {@code projectId} (e.g. 12345) and {@code exaFS}, the
         * file system the driver mounts, written {@code <server>@<net>:...:/<fsname>} (e.g.
         * {@code mds0@o2ib0:mds1@o2ib0:/lustre0}). The part after the last colon names the file system;
         * on a007 it is mounted at the same path. Empty when either is missing or not of this form, so no command
         * is built from a value that could carry shell syntax.
         */
        public static Optional<LustreQuota> of(Map<String, String> volumeAttributes) {
            if (volumeAttributes == null) return Optional.empty();
            String id = volumeAttributes.get("projectId");
            String exaFs = volumeAttributes.get("exaFS");
            if (id == null || exaFs == null || !PROJECT_ID.matcher(id).matches()) return Optional.empty();
            String fs = exaFs.substring(exaFs.lastIndexOf(':') + 1);
            if (!FILESYSTEM.matcher(fs).matches()) return Optional.empty();
            return Optional.of(new LustreQuota(id, fs));
        }
    }

    /**
     * The commands an administrator runs on a007. {@code check} shows the space used (column "kbytes", in KiB),
     * which must not be more than {@code newLimitKib}, and the hard limit (column "blimit"); {@code lower} sets the
     * new hard limit; {@code undo} sets the old limit again, for a shrink cancelled after the limit was lowered.
     */
    public record Commands(String check, String lower, String undo, long newLimitKib) {}

    /** The commands for lowering the quota from {@code oldSize} to {@code newSize} (sizes accepted by validSize). */
    public static Commands commands(LustreQuota q, String oldSize, String newSize) {
        long newKib = kibibytes(newSize);
        // lfs reads a number without a unit as KiB, the unit "lfs quota" prints, so the two can be compared directly
        return new Commands(
            "lfs quota -p " + q.projectId() + " " + q.filesystem(),
            "lfs setquota -p " + q.projectId() + " -B " + newKib + " " + q.filesystem(),
            "lfs setquota -p " + q.projectId() + " -B " + kibibytes(oldSize) + " " + q.filesystem(),
            newKib);
    }

    /** KiB of a size accepted by {@link StoragePolicy#validSize}. */
    public static long kibibytes(String size) {
        return StoragePolicy.gibibytes(size) * 1024 * 1024;
    }

    /**
     * Why a shrink of a group to {@code wanted} cannot be prepared, or null when it can.
     *
     * @param active   whether the group's storage is given (not taken away)
     * @param shrinkTo the size of a pending shrink, or null
     * @param current  the group's size now
     */
    public static String refusePrepare(boolean active, String shrinkTo, String current, String wanted) {
        if (shrinkTo != null) {
            return "A shrink to " + shrinkTo + " is already pending; continue or cancel it first";
        }
        if (!active) return "The group's storage is taken away; give it back before making it smaller";
        if (!StoragePolicy.validSize(wanted)) return "Size must be a whole number of Gi or Ti, for example 500Gi";
        long now = StoragePolicy.gibibytes(current), next = StoragePolicy.gibibytes(wanted);
        if (next == now) return "The storage already has this size";
        if (next > now) return "This size is larger than the storage; use Enlarge to make it larger";
        return null;
    }

    /** Why a pending shrink cannot be continued or cancelled, or null when it can. */
    public static String refuseContinue(boolean active, String shrinkTo) {
        if (active || shrinkTo == null) return "No shrink is pending for this group";
        return null;
    }

    /** Why the ordinary give-back is refused, or null when it is allowed. */
    public static String refuseRestore(String shrinkTo) {
        if (shrinkTo == null) return null;
        return "A shrink to " + shrinkTo + " is pending: continue it, or cancel it before giving the storage back "
            + "at its old size";
    }
}
