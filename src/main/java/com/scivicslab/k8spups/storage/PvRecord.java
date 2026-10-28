package com.scivicslab.k8spups.storage;

import java.util.Map;

/**
 * The PV a user's PVC was bound to, as written to {@code storage_allocation} when the allocation is revoked. With
 * these values the same directory can be given back: by binding a new PVC to the PV if the PV object still exists
 * (Released, because the StorageClass keeps it), or by writing the PV again if it was deleted.
 */
public record PvRecord(String name, String storageClass, String capacity, String csiDriver, String volumeHandle,
                       Map<String, String> volumeAttributes) {}
