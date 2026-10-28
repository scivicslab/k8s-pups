package com.scivicslab.k8spups.storage;

import com.scivicslab.k8spups.dataset.DatasetRepository;
import com.scivicslab.k8spups.k8s.K8sApiClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The storage administrators give to users. A user's PVC exists only for an active allocation, with the type and
 * size written in {@code storage_allocation}; users cannot create, expand, delete or share storage themselves.
 *
 * A plain POJO; the controller runs one instance as the actor {@code storage-allocations}, so two changes of one
 * user's storage (an administrator's revoke while the user starts a tool) happen one after the other. Each change
 * is written to the audit trail ({@code audit_event}).
 */
public class StorageAllocationService {

    /** Name the controller registers this service under in the actor system. */
    public static final String ACTOR_NAME = "storage-allocations";

    /** The actor written as {@code actor} of the audit events the controller causes itself. */
    public static final String CONTROLLER = "controller";

    private static final Logger LOG = Logger.getLogger(StorageAllocationService.class.getName());

    private final StorageAllocationRepository repository;
    private final DatasetRepository audit;
    private final K8sApiClient k8sClient;
    private final List<String> offered;
    private final Optional<StoragePolicy.Grant> defaultGrant;

    /**
     * @param offered      the storage types of the deployment ({@code k8spups.storage-type-options})
     * @param defaultGrant the allocation a user gets at the first login, unless an administrator gave or revoked one
     */
    public StorageAllocationService(StorageAllocationRepository repository, DatasetRepository audit,
            K8sApiClient k8sClient, List<String> offered, Optional<StoragePolicy.Grant> defaultGrant) {
        this.repository = repository;
        this.audit = audit;
        this.k8sClient = k8sClient;
        this.offered = List.copyOf(offered);
        this.defaultGrant = defaultGrant;
    }

    /**
     * Gives the user the default allocation if the user never had one, and creates the PVCs of the user's active
     * allocations that do not exist yet. Called when the user opens the dashboard, so the storage is there before
     * the first tool starts. A user whose allocations an administrator revoked does not get the default again.
     */
    public void ensureStorage(String user) {
        if (defaultGrant.isPresent() && !repository.hasAny(user)) {
            StoragePolicy.Grant g = defaultGrant.get();
            // A PVC of the type made before allocations existed becomes the default allocation, at its own size
            String current = k8sClient.getUserPvcInfo(user, g.type()).get("size");
            String size = current != null && StoragePolicy.validSize(current) ? current : g.size();
            repository.insert(user, g.type(), size, CONTROLLER, null);
            audit.addAudit("storage.allocate", CONTROLLER, user, null, null, null, null,
                "default allocation " + g.type() + " " + size);
            LOG.info("Default storage allocated: " + user + " " + g.type() + " " + size);
        }
        for (var a : repository.active(user)) {
            if (!offered.contains(a.type())) continue;
            try {
                if (!"true".equals(k8sClient.getUserPvcInfo(user, a.type()).get("exists"))) {
                    k8sClient.createAllocatedPvc(user, a.type(), a.size());
                }
            } catch (Exception e) {
                LOG.warning("PVC of the allocation " + a.id() + " could not be created: " + e.getMessage());
            }
        }
    }

    /** Type to size of the user's active allocations of the offered types, in the order the deployment lists them. */
    public Map<String, String> activeTypes(String user) {
        Map<String, String> byType = new LinkedHashMap<>();
        var active = repository.active(user);
        for (String t : offered) {
            for (var a : active) if (a.type().equals(t)) byType.put(t, a.size());
        }
        return byType;
    }

    /** Every active allocation of every user. */
    public List<StorageAllocationRepository.Allocation> allActive() {
        return repository.allActive();
    }

    /** Every allocation of the user, active and revoked, newest first. */
    public List<StorageAllocationRepository.Allocation> history(String user) {
        return repository.history(user);
    }

    /** Gives the user storage of a type; returns an error message, or null when done. */
    public String allocate(String admin, String user, String type, String size) {
        if (!StoragePolicy.allocatable(type, offered)) return "This storage type cannot be allocated here: " + type;
        if (!StoragePolicy.validSize(size)) return "Size must be a whole number of Gi or Ti, for example 50Gi";
        if (activeTypes(user).containsKey(type)) return "The user already has this storage";
        Map<String, String> pvc = k8sClient.getUserPvcInfo(user, type);
        String detail = type + " " + size;
        if ("true".equals(pvc.get("exists"))) {
            // A PVC made before allocations existed (users created their own storage then): it becomes this allocation,
            // enlarged to the size if it is smaller. Its files stay.
            String current = pvc.get("size");
            if (current != null && StoragePolicy.validSize(current)
                    && StoragePolicy.gibibytes(size) > StoragePolicy.gibibytes(current)) {
                try {
                    k8sClient.growPvc(user, type, size);
                } catch (Exception e) {
                    return "The existing PVC could not be enlarged: " + e.getMessage();
                }
            } else if (current != null && StoragePolicy.validSize(current)) {
                size = current;
            }
            detail = type + " " + size + " (existing PVC taken over)";
            repository.insert(user, type, size, admin, null);
        } else {
            repository.insert(user, type, size, admin, null);
            k8sClient.createAllocatedPvc(user, type, size);
        }
        audit.addAudit("storage.allocate", admin, user, null, null, null, null, detail);
        return null;
    }

    /** Makes the storage of an active allocation larger; returns an error message, or null when done. */
    public String resize(String admin, long id, String size) {
        var a = repository.find(id).filter(StorageAllocationRepository.Allocation::active).orElse(null);
        if (a == null) return "No such active allocation";
        if (!StoragePolicy.validSize(size)) return "Size must be a whole number of Gi or Ti, for example 50Gi";
        if (StoragePolicy.gibibytes(size) <= StoragePolicy.gibibytes(a.size())) return "Storage can only be made larger";
        try {
            k8sClient.growPvc(a.user(), a.type(), size);
        } catch (Exception e) {
            return "The PVC could not be made larger: " + e.getMessage();
        }
        repository.resize(id, size);
        audit.addAudit("storage.resize", admin, a.user(), null, null, null, null, a.type() + " " + a.size() + " -> " + size);
        return null;
    }

    /**
     * Takes the storage away: records the PV, deletes the PVC and marks the allocation revoked. Refused while a tool
     * of the user mounts it. Returns an error message, or null when done.
     */
    public String revoke(String admin, long id) {
        var a = repository.find(id).filter(StorageAllocationRepository.Allocation::active).orElse(null);
        if (a == null) return "No such active allocation";
        if (k8sClient.isUserPvcInUse(a.user(), a.type())) return "A tool of the user is using this storage; stop it first";
        Optional<PvRecord> pv = k8sClient.boundPv(a.user(), a.type());
        if ("true".equals(k8sClient.getUserPvcInfo(a.user(), a.type()).get("exists"))
                && !k8sClient.deleteUserPvc(a.user(), a.type())) {
            return "The PVC could not be deleted";
        }
        repository.revoke(id, admin, pv);
        audit.addAudit("storage.revoke", admin, a.user(), null, null, null, null,
            a.type() + " " + a.size() + pv.map(r -> " pv " + r.name()).orElse(""));
        return null;
    }

    /** Gives back the storage of a revoked allocation; returns an error message, or null when done. */
    public String restore(String admin, long revokedId) {
        var a = repository.find(revokedId).filter(x -> !x.active()).orElse(null);
        if (a == null) return "No such revoked allocation";
        if (!StoragePolicy.restorable(a.type())) return "This storage type is deleted with its PVC and cannot be restored";
        if (!offered.contains(a.type())) return "This storage type is not offered here any more: " + a.type();
        if (activeTypes(a.user()).containsKey(a.type())) return "The user already has this storage";
        try {
            if ("lustre-csi".equals(a.type())) {
                if (a.pv().isEmpty()) return "No PV was recorded when this allocation was revoked";
                k8sClient.restoreLustreCsiPvc(a.user(), a.pv().get());
            } else {
                // nfs-k8s: the directory on the NFS server is named after the user, so a new PV and PVC find it again
                k8sClient.createAllocatedPvc(a.user(), a.type(), a.size());
            }
        } catch (Exception e) {
            return "The storage could not be restored: " + e.getMessage();
        }
        repository.insert(a.user(), a.type(), a.size(), admin, a.id());
        audit.addAudit("storage.restore", admin, a.user(), null, null, null, null,
            a.type() + " " + a.size() + " from allocation " + a.id());
        return null;
    }

    /** The mounts the user chose for the tool the last time (a JSON array), "[]" when none. */
    public String mountPreference(String user, String tool) {
        return repository.mountPreference(user, tool);
    }

    public void saveMountPreference(String user, String tool, String mountsJson) {
        repository.saveMountPreference(user, tool, mountsJson);
    }
}
