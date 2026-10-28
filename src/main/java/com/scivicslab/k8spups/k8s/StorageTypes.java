package com.scivicslab.k8spups.k8s;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Names shown to users for the storage types a deployment offers ({@code k8spups.storage-type-options}).
 *
 * The storage types differ between clusters: W206 offers {@code nfs-k8s} (NFS server stonefly520), {@code longhorn}
 * and {@code nfs-home}; the personal genome k0s cluster offers {@code lustre-csi} (Lustre CSI driver on
 * {@code lustre12}) and {@code longhorn}. Each key always means the same kind of storage, so its name is fixed here and
 * the deployment chooses only which keys it offers. The dashboard and the Storage Settings tool show these names, and
 * show no storage type the deployment does not offer.
 */
public final class StorageTypes {

    private static final Map<String, String> NAMES = Map.of(
            "lustre-csi", "Lustre (k8s)",
            "longhorn", "Longhorn",
            "nfs-k8s", "NFS (k8s)",
            "nfs-home", "NFS (Home)");

    private StorageTypes() {}

    /** The name shown for a storage type key; an unknown key is shown as it is. */
    public static String name(String type) {
        return NAMES.getOrDefault(type, type);
    }

    /** Key to name for the offered types, in the order the deployment lists them. */
    public static Map<String, String> names(List<String> offered) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String type : offered) out.put(type, name(type));
        return out;
    }
}
