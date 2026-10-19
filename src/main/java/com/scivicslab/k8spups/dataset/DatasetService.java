package com.scivicslab.k8spups.dataset;

import com.scivicslab.k8spups.k8s.K8sApiClient;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The dataset entitlement operations: the list of files and grants, and the per-user view directories
 * that follow from it. A plain POJO; the controller runs one instance as the actor {@code dataset-manager}, so
 * every change of a list and every rebuild of a view directory happens one at a time. Two rebuilds of the same
 * user's view (an administrator changing a grant while the user starts a tool) therefore never overlap.
 */
public class DatasetService {

    private static final Logger LOG = Logger.getLogger(DatasetService.class.getName());

    private final DatasetRepository repository;
    private final K8sApiClient k8sClient;

    public DatasetService(DatasetRepository repository, K8sApiClient k8sClient) {
        this.repository = repository;
        this.k8sClient = k8sClient;
    }

    /** True when the entitlement database is configured and reachable in this instance. */
    public boolean isAvailable() {
        return repository.isAvailable();
    }

    public List<DatasetRepository.FileRow> listFiles() {
        return isAvailable() ? repository.listFiles() : List.of();
    }

    public List<DatasetRepository.GrantRow> listGrants() {
        return isAvailable() ? repository.listGrants() : List.of();
    }

    public void addFile(String dataset, String path) {
        repository.addFile(dataset, path);
    }

    /** Deletes the file and every grant on it, then rebuilds the views of the users that had a grant. */
    public boolean deleteFile(long fileId) {
        Set<String> affected = new LinkedHashSet<>();
        for (var g : repository.listGrants()) {
            if (g.fileId() == fileId) affected.add(g.user());
        }
        repository.deleteFile(fileId);
        return rebuild(affected);
    }

    /** Grants the file to the user and rebuilds the user's view. */
    public boolean grant(String user, long fileId, Instant expiresAt, String grantedBy) {
        repository.grant(user, fileId, expiresAt, grantedBy);
        return rebuild(Set.of(user));
    }

    /** Revokes the grant and rebuilds the user's view. */
    public boolean revoke(String user, long fileId) {
        repository.revoke(user, fileId);
        return rebuild(Set.of(user));
    }

    /**
     * Rebuilds the user's view for a tool that is about to start.
     *
     * @return true when the user has at least one active grant and the view is ready to be mounted; false when
     *         there is nothing to mount, the database is not available, or the view could not be built (a stale
     *         view could still list files whose grant was revoked, so it must not be mounted)
     */
    public boolean prepareView(String userId) {
        if (!isAvailable()) return false;
        try {
            List<String> paths = repository.activePaths(userId);
            if (paths.isEmpty()) return false;
            if (k8sClient.syncDatasetView(userId, paths)) return true;
            LOG.warning("Dataset view not ready for " + userId + "; the session starts without it");
        } catch (Exception e) {
            LOG.warning("Dataset view failed for " + userId + ": " + e.getMessage());
        }
        return false;
    }

    private boolean rebuild(Set<String> users) {
        boolean ok = true;
        for (String user : users) {
            ok &= k8sClient.syncDatasetView(user, repository.activePaths(user));
        }
        return ok;
    }
}
