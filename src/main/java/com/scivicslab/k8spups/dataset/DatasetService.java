package com.scivicslab.k8spups.dataset;

import com.scivicslab.k8spups.k8s.K8sApiClient;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The dataset entitlement operations: the catalog of datasets and files, who may read which dataset (the main grant),
 * the files granted one by one (the exception), and what a tool Pod gets mounted as a result. A plain POJO; the
 * controller runs one instance as the actor {@code dataset-manager}, so every change and every rebuild of a user's
 * file-level view happens one at a time. Two rebuilds of the same view (an administrator changing a grant while the
 * user starts a tool) therefore never overlap.
 */
public class DatasetService {

    /** Name the controller registers this service under in the actor system. */
    public static final String ACTOR_NAME = "dataset-manager";

    private static final Logger LOG = Logger.getLogger(DatasetService.class.getName());

    /** What a tool Pod of one user gets: whole datasets (subPath mounts) and, if any, the file-level view. */
    public record Mounts(List<String> datasets, boolean fileView) {
        public static final Mounts NONE = new Mounts(List.of(), false);
    }

    /** Result of a catalog import. */
    public record CatalogImport(long linesRead, long filesAdded, List<String> errors) {}

    /** Result of an access import. */
    public record AccessImport(long linesRead, long granted, List<String> errors) {}

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

    // ── catalog ────────────────────────────────────────────────────────────────

    public DatasetRepository.Page<DatasetRepository.DatasetRow> datasets(String q, int page, int size) {
        return repository.datasets(q, page, size);
    }

    public boolean datasetExists(String name) {
        return repository.datasetExists(name);
    }

    public DatasetRepository.Page<String> files(String dataset, String q, int page, int size) {
        return repository.files(dataset, q, page, size);
    }

    /** Adds the files of a catalog import ({@code dataset,path} or {@code path} per line). */
    public CatalogImport importCatalog(String text) {
        var parsed = CsvLines.catalog(text.lines().toList());
        long added = parsed.entries().isEmpty() ? 0 : repository.importCatalog(parsed.entries());
        return new CatalogImport(text.lines().count(), added, parsed.errors());
    }

    /** Records one finished catalog import (a file is imported in chunks; this is one event per file). */
    public void auditCatalogImport(String by, long lines, long added, long errors) {
        repository.addAudit("import.catalog", by, null, null, null, null, null,
            lines + " lines, " + added + " files added, " + errors + " errors");
    }

    /** Deletes a dataset with its files and access, then rebuilds the views of the users that had access. */
    public boolean deleteDataset(String name, String by) {
        Set<String> affected = new LinkedHashSet<>();
        for (var a : repository.accessOfDataset(name)) affected.add(a.user());
        repository.deleteDataset(name);
        repository.addAudit("dataset.delete", by, null, null, null, null, name,
            "access of " + affected.size() + " users removed with it");
        return rebuild(affected);
    }

    // ── access to whole datasets (the main grant) ──────────────────────────────

    public List<DatasetRepository.AccessRow> accessOfDataset(String dataset) {
        return repository.accessOfDataset(dataset);
    }

    public List<DatasetRepository.AccessRow> accessOfUser(String user) {
        return repository.accessOfUser(user);
    }

    public DatasetRepository.Page<DatasetRepository.UserRow> users(String q, int page, int size) {
        return repository.users(q, page, size);
    }

    public boolean grantDataset(String user, String dataset, Instant expiresAt, String grantedBy) {
        repository.grantDataset(user, dataset, expiresAt, grantedBy);
        repository.addAudit("dataset.grant", grantedBy, user, null, null, null, dataset,
            expiresAt == null ? "no expiry" : "valid until " + expiresAt);
        return rebuild(Set.of(user));
    }

    public boolean revokeDataset(String user, String dataset, String by) {
        repository.revokeDataset(user, dataset);
        repository.addAudit("dataset.revoke", by, user, null, null, null, dataset, "");
        return rebuild(Set.of(user));
    }

    /** Sets the access of an import ({@code user,dataset[,YYYY-MM-DD]} per line) and rebuilds the users' views. */
    public AccessImport importAccess(String text, String grantedBy) {
        var parsed = CsvLines.access(text.lines().toList());
        long granted = parsed.entries().isEmpty() ? 0 : repository.importAccess(parsed.entries(), grantedBy);
        for (var e : parsed.entries()) {
            repository.addAudit("import.access", grantedBy, e.user(), null, null, null, e.dataset(),
                e.expiresAt() == null ? "no expiry" : "valid until " + e.expiresAt());
        }
        Set<String> users = new LinkedHashSet<>();
        for (var e : parsed.entries()) users.add(e.user());
        rebuild(users);
        return new AccessImport(text.lines().count(), granted, parsed.errors());
    }

    // ── files granted one by one (the exception) ───────────────────────────────

    public DatasetRepository.Page<String> exceptionsOfUser(String user, int page, int size) {
        return repository.exceptionsOfUser(user, page, size);
    }

    /** Grants one catalog file to the user; false when the file is not in the catalog. */
    public boolean grantFile(String user, String path, Instant expiresAt, String grantedBy) {
        Long id = repository.fileId(path);
        if (id == null) return false;
        repository.grant(user, id, expiresAt, grantedBy);
        repository.addAudit("file.grant", grantedBy, user, null, null, null, datasetOf(path),
            path + (expiresAt == null ? ", no expiry" : ", valid until " + expiresAt));
        rebuild(Set.of(user));
        return true;
    }

    public boolean revokeFile(String user, String path, String by) {
        Long id = repository.fileId(path);
        if (id == null) return false;
        repository.revoke(user, id);
        repository.addAudit("file.revoke", by, user, null, null, null, datasetOf(path), path);
        rebuild(Set.of(user));
        return true;
    }

    /** The datasets the user may read now (access that has not expired). */
    public List<String> activeDatasets(String user) {
        return repository.activeDatasets(user);
    }

    // ── audit trail ────────────────────────────────────────────────────────────

    /** What the audit trail needs to know about a session that mounts datasets. */
    public record SessionMounts(String user, String sessionId, String podName, String tool,
                                List<String> datasets, boolean fileView) {}

    /** Records that a session started with these datasets (one event per dataset, one for the file-level view). */
    public void auditSessionStart(SessionMounts s) {
        auditSession("session.start", s, "");
    }

    /** Records that a session that mounted datasets ended, and why. */
    public void auditSessionStop(SessionMounts s, String reason) {
        auditSession("session.stop", s, reason);
    }

    private void auditSession(String type, SessionMounts s, String detail) {
        for (String d : s.datasets()) {
            repository.addAudit(type, "controller", s.user(), s.sessionId(), s.podName(), s.tool(), d, detail);
        }
        if (s.fileView()) {
            repository.addAudit(type, "controller", s.user(), s.sessionId(), s.podName(), s.tool(), null,
                ("files granted one by one " + detail).strip());
        }
    }

    public DatasetRepository.Page<DatasetRepository.AuditRow> audit(String user, String dataset, String type, int page, int size) {
        return repository.audit(user, dataset, type, page, size);
    }

    private static String datasetOf(String path) {
        return path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
    }

    // ── mounts of a tool Pod ───────────────────────────────────────────────────

    /**
     * Prepares what a tool Pod of the user gets mounted: the read-only volume on the original directory (for the
     * dataset subPath mounts) and the file-level view. The file-level view is rebuilt first; if that fails it is left
     * out, because a stale view could still list files whose grant was revoked. Datasets need no rebuild: the list
     * comes from the database at this moment.
     */
    public Mounts prepareMounts(String userId) {
        if (!isAvailable()) return Mounts.NONE;
        try {
            List<String> datasets = repository.activeDatasets(userId);
            List<String> paths = repository.activePaths(userId);
            if (datasets.isEmpty() && paths.isEmpty()) return Mounts.NONE;
            if (!datasets.isEmpty()) k8sClient.ensureDatasetRootPvPvc(userId);
            boolean fileView = !paths.isEmpty() && k8sClient.syncDatasetView(userId, paths);
            if (!paths.isEmpty() && !fileView) {
                LOG.warning("File-level dataset view not ready for " + userId + "; the session starts without it");
            }
            return new Mounts(datasets, fileView);
        } catch (Exception e) {
            LOG.warning("Dataset mounts failed for " + userId + ": " + e.getMessage());
            return Mounts.NONE;
        }
    }

    /** Rebuilds the file-level view of each user (a removed file-level grant must leave the running Pods at once). */
    private boolean rebuild(Set<String> users) {
        boolean ok = true;
        for (String user : users) {
            ok &= k8sClient.syncDatasetView(user, repository.activePaths(user));
        }
        return ok;
    }
}
