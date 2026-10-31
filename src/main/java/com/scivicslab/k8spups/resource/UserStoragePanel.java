package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.k8s.StorageTypes;
import com.scivicslab.k8spups.storage.StoragePolicy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The storage of one user as the administrator screens show it: the active allocations with the state of their volume
 * claim, the revoked ones with whether they can be given back, and the storage types an administrator may give.
 * The page of a user under Users shows this.
 */
@ApplicationScoped
public class UserStoragePanel {

    @Inject
    K8sPupsActorSystem actorSystem;

    /** The items {@code active}, {@code revoked} and {@code types} for the templates. */
    public Map<String, Object> data(String user) {
        List<com.scivicslab.k8spups.storage.StorageAllocationRepository.Allocation> history;
        try {
            history = actorSystem.getStorageAllocations().ask(s -> s.history(user)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Storage allocation failed: " + e.getMessage(), e);
        }
        List<Map<String, Object>> active = new ArrayList<>();
        List<Map<String, Object>> revoked = new ArrayList<>();
        for (var a : history) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", a.id());
            row.put("type", a.type());
            row.put("name", StorageTypes.name(a.type()));
            row.put("storageSize", a.size());   // not "size": Qute reads map.size as the number of entries
            row.put("grantedBy", a.grantedBy());
            row.put("grantedAt", a.grantedAt() == null ? "" : a.grantedAt().toString().substring(0, 16).replace('T', ' '));
            if (a.active()) {
                Map<String, String> pvc = actorSystem.getK8sClient().getUserPvcInfo(user, a.type());
                row.put("pvc", "true".equals(pvc.get("exists"))
                    ? pvc.getOrDefault("phase", "") + ("true".equals(pvc.get("inUse")) ? ", in use" : "")
                    : "not created yet");
                active.add(row);
            } else {
                row.put("revokedBy", a.revokedBy());
                row.put("revokedAt", a.revokedAt() == null ? "" : a.revokedAt().toString().substring(0, 16).replace('T', ' '));
                row.put("restorable", StoragePolicy.restorable(a.type())
                    && (actorSystem.getK8sClient().posixUsesNfs() || a.pv().isPresent()));
                revoked.add(row);
            }
        }
        List<Map<String, String>> types = new ArrayList<>();
        for (String t : actorSystem.getStorageTypeOptions()) {
            if (StoragePolicy.allocatable(t, actorSystem.getStorageTypeOptions())) {
                types.add(Map.of("type", t, "name", StorageTypes.name(t)));
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("active", active);
        data.put("revoked", revoked);
        data.put("types", types);
        return data;
    }
}
