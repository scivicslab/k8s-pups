package com.scivicslab.k8spups.dataset;

import com.scivicslab.k8spups.k8s.K8sApiClient;
import com.scivicslab.k8spups.passport.PassportGrants;
import com.scivicslab.k8spups.passport.S3Access;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final Optional<PassportGrants> passport;
    private final Optional<S3Access> s3;

    public DatasetService(DatasetRepository repository, K8sApiClient k8sClient) {
        this(repository, k8sClient, Optional.empty(), Optional.empty());
    }

    /**
     * @param passport datasets granted by the Visas of a user's Passport, added to the datasets of the database
     * @param s3       when present, the datasets that Visas grant are also given to the Pod as access to an S3 server
     */
    public DatasetService(DatasetRepository repository, K8sApiClient k8sClient, Optional<PassportGrants> passport,
            Optional<S3Access> s3) {
        this.repository = repository;
        this.k8sClient = k8sClient;
        this.passport = passport;
        this.s3 = s3;
    }

    /** Whether the Visas of users come from their UserInfo response, so the caller must supply them. */
    public boolean wantsSuppliedVisas() {
        return passport.map(PassportGrants::acceptsSuppliedVisas).orElse(false);
    }

    /** Takes the Visas of a user from the UserInfo response of the user's login (see {@link PassportGrants#supply}). */
    public void supplyVisas(String user, List<String> visas) {
        passport.ifPresent(p -> p.supply(user, visas));
    }

    /** The S3 access, for the endpoints that publish the keys of its tokens. */
    public Optional<S3Access> s3Access() {
        return s3;
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

    /**
     * The datasets the user may read now: the access in the database that has not expired, and, when the Passport
     * support is on, the datasets of the catalog that the user's Visas grant.
     */
    public List<String> activeDatasets(String user) {
        List<String> fromDatabase = repository.activeDatasets(user);
        if (passport.isEmpty()) return fromDatabase;
        Set<String> granted = passport.get().datasets(user);
        return union(fromDatabase, granted.stream().filter(repository::datasetExists).toList());
    }

    /** The datasets that the Visas of the user grant, whether or not they are in the catalog (empty without Passport support). */
    private Set<String> visaDatasets(String user) {
        return passport.map(p -> p.datasets(user)).orElse(Set.of());
    }

    /**
     * Every dataset the user may read now, wherever it is read: the datasets mounted from NFS, and, when the S3 access
     * is on, the datasets of Visas that the S3 server holds. A running session is stopped when it holds a dataset
     * that is not in this list any more.
     */
    public List<String> accessibleDatasets(String user) {
        List<String> nfs = activeDatasets(user);
        return s3.isEmpty() ? nfs : union(nfs, List.copyOf(visaDatasets(user)));
    }

    /**
     * The environment that gives a tool Pod of the user access to the S3 datasets that the user's Visas grant: the
     * temporary credentials of the S3 server and the list of datasets they were made for. Empty when the S3 access is
     * off or no Visa grants a dataset.
     */
    public Map<String, String> s3Env(String user) {
        if (s3.isEmpty()) return Map.of();
        return s3.get().env(user, visaDatasets(user));
    }

    /** The two lists without duplicates, in order: the first list, then the new names of the second. */
    static List<String> union(List<String> first, List<String> second) {
        Set<String> all = new LinkedHashSet<>(first);
        all.addAll(second);
        return List.copyOf(all);
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
            List<String> datasets = activeDatasets(userId);
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
