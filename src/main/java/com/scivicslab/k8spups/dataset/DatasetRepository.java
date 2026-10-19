package com.scivicslab.k8spups.dataset;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Reads and writes the dataset entitlement list (PostgreSQL, plain JDBC). */
@ApplicationScoped
public class DatasetRepository {

    public record FileRow(long id, String dataset, String path) {}

    public record GrantRow(String user, long fileId, String dataset, String path,
                           Instant grantedAt, Instant expiresAt, String grantedBy) {}

    @Inject
    Instance<AgroalDataSource> dataSource;

    /** True when the database is configured and active in this instance. */
    public boolean isAvailable() {
        if (!dataSource.isResolvable()) return false;
        try (Connection c = dataSource.get().getConnection()) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    public List<FileRow> listFiles() {
        return query("SELECT id, dataset, path FROM dataset_file ORDER BY dataset, path", ps -> {},
            rs -> new FileRow(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    public long addFile(String dataset, String path) {
        DatasetPath.requireRelativePath(path);
        DatasetPath.requireRelativePath(dataset);
        return update("INSERT INTO dataset_file (dataset, path) VALUES (?, ?) "
                + "ON CONFLICT (dataset, path) DO UPDATE SET path = EXCLUDED.path RETURNING id",
            ps -> { ps.setString(1, dataset); ps.setString(2, path); });
    }

    /** The id of a catalog file by its relative path, or null when it is not in the catalog. */
    public Long fileId(String path) {
        var ids = query("SELECT id FROM dataset_file WHERE path = ?", ps -> ps.setString(1, path), rs -> rs.getLong(1));
        return ids.isEmpty() ? null : ids.get(0);
    }

    public void deleteFile(long fileId) {
        execute("DELETE FROM dataset_file WHERE id = ?", ps -> ps.setLong(1, fileId));
    }

    public List<GrantRow> listGrants() {
        return query("SELECT g.user_name, g.file_id, f.dataset, f.path, g.granted_at, g.expires_at, g.granted_by "
                + "FROM dataset_grant g JOIN dataset_file f ON f.id = g.file_id ORDER BY g.user_name, f.path",
            ps -> {}, this::grantRow);
    }

    public void grant(String user, long fileId, Instant expiresAt, String grantedBy) {
        DatasetPath.requireUserName(user);
        execute("INSERT INTO dataset_grant (user_name, file_id, expires_at, granted_by) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (user_name, file_id) DO UPDATE SET expires_at = EXCLUDED.expires_at, "
                + "granted_by = EXCLUDED.granted_by, granted_at = now()",
            ps -> {
                ps.setString(1, user);
                ps.setLong(2, fileId);
                ps.setTimestamp(3, expiresAt == null ? null : Timestamp.from(expiresAt));
                ps.setString(4, grantedBy);
            });
    }

    public void revoke(String user, long fileId) {
        execute("DELETE FROM dataset_grant WHERE user_name = ? AND file_id = ?",
            ps -> { ps.setString(1, user); ps.setLong(2, fileId); });
    }

    /** Relative paths of the files the user may read now (grants that have not expired). */
    public List<String> activePaths(String user) {
        return query("SELECT f.path FROM dataset_grant g JOIN dataset_file f ON f.id = g.file_id "
                + "WHERE g.user_name = ? AND (g.expires_at IS NULL OR g.expires_at > now()) ORDER BY f.path",
            ps -> ps.setString(1, user), rs -> rs.getString(1));
    }

    /** Users that have at least one grant that has not expired. */
    public List<String> usersWithActiveGrants() {
        return query("SELECT DISTINCT user_name FROM dataset_grant "
                + "WHERE expires_at IS NULL OR expires_at > now() ORDER BY user_name",
            ps -> {}, rs -> rs.getString(1));
    }


    // ── dataset level ──────────────────────────────────────────────────────────

    public record Page<T>(List<T> items, long total, int page, int size) {
        public boolean hasPrevious() { return page > 0; }
        public boolean hasNext() { return (long) (page + 1) * size < total; }
        public int pages() { return (int) Math.max(1, (total + size - 1) / size); }
    }

    public record DatasetRow(String name, long fileCount, long userCount) {}

    public record AccessRow(String user, String dataset, Instant grantedAt, Instant expiresAt, String grantedBy) {}

    public record UserRow(String user, long datasetCount, long fileCount) {}

    private static String like(String q) {
        return "%" + (q == null ? "" : q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
    }

    /** Datasets whose name contains {@code q}, one page, with the number of files and of users with access. */
    public Page<DatasetRow> datasets(String q, int page, int size) {
        long total = query("SELECT count(*) FROM dataset WHERE name ILIKE ?", ps -> ps.setString(1, like(q)),
            rs -> rs.getLong(1)).get(0);
        List<DatasetRow> rows = query(
            "SELECT d.name, (SELECT count(*) FROM dataset_file f WHERE f.dataset = d.name), "
                + "(SELECT count(*) FROM dataset_access a WHERE a.dataset = d.name) "
                + "FROM dataset d WHERE d.name ILIKE ? ORDER BY d.name LIMIT ? OFFSET ?",
            ps -> { ps.setString(1, like(q)); ps.setInt(2, size); ps.setInt(3, page * size); },
            rs -> new DatasetRow(rs.getString(1), rs.getLong(2), rs.getLong(3)));
        return new Page<>(rows, total, page, size);
    }

    public boolean datasetExists(String name) {
        return !query("SELECT 1 FROM dataset WHERE name = ?", ps -> ps.setString(1, name), rs -> 1).isEmpty();
    }

    /** The relative paths of the files of one dataset that contain {@code q}, one page. */
    public Page<String> files(String dataset, String q, int page, int size) {
        long total = query("SELECT count(*) FROM dataset_file WHERE dataset = ? AND path ILIKE ?",
            ps -> { ps.setString(1, dataset); ps.setString(2, like(q)); }, rs -> rs.getLong(1)).get(0);
        List<String> rows = query("SELECT path FROM dataset_file WHERE dataset = ? AND path ILIKE ? ORDER BY path LIMIT ? OFFSET ?",
            ps -> { ps.setString(1, dataset); ps.setString(2, like(q)); ps.setInt(3, size); ps.setInt(4, page * size); },
            rs -> rs.getString(1));
        return new Page<>(rows, total, page, size);
    }

    /** Adds the entries to the catalog (datasets are created as needed); returns how many files were new. */
    public long importCatalog(List<CsvLines.CatalogEntry> entries) {
        long added = 0;
        try (Connection c = dataSource.get().getConnection()) {
            c.setAutoCommit(false);
            Set<String> names = new TreeSet<>();
            for (var e : entries) names.add(e.dataset());
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dataset (name) VALUES (?) ON CONFLICT DO NOTHING")) {
                for (String n : names) { ps.setString(1, n); ps.addBatch(); }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO dataset_file (dataset, path) VALUES (?, ?) ON CONFLICT (dataset, path) DO NOTHING")) {
                int pending = 0;
                for (var e : entries) {
                    ps.setString(1, e.dataset()); ps.setString(2, e.path()); ps.addBatch();
                    if (++pending == 2000) { added += sum(ps.executeBatch()); pending = 0; }
                }
                if (pending > 0) added += sum(ps.executeBatch());
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Catalog import failed: " + e.getMessage(), e);
        }
        return added;
    }

    private static long sum(int[] counts) {
        long n = 0;
        for (int c : counts) n += Math.max(c, 0);
        return n;
    }

    public void deleteDataset(String name) {
        execute("DELETE FROM dataset WHERE name = ?", ps -> ps.setString(1, name));
    }

    public void grantDataset(String user, String dataset, Instant expiresAt, String grantedBy) {
        DatasetPath.requireUserName(user);
        execute("INSERT INTO dataset_access (user_name, dataset, expires_at, granted_by) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (user_name, dataset) DO UPDATE SET expires_at = EXCLUDED.expires_at, "
                + "granted_by = EXCLUDED.granted_by, granted_at = now()",
            ps -> {
                ps.setString(1, user); ps.setString(2, dataset);
                ps.setTimestamp(3, expiresAt == null ? null : Timestamp.from(expiresAt));
                ps.setString(4, grantedBy);
            });
    }

    public void revokeDataset(String user, String dataset) {
        execute("DELETE FROM dataset_access WHERE user_name = ? AND dataset = ?",
            ps -> { ps.setString(1, user); ps.setString(2, dataset); });
    }

    /** Grants from an import; lines for datasets that are not in the catalog are skipped. Returns how many were set. */
    public long importAccess(List<CsvLines.AccessEntry> entries, String grantedBy) {
        long set = 0;
        try (Connection c = dataSource.get().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO dataset_access (user_name, dataset, expires_at, granted_by) "
                        + "SELECT ?, d.name, ?, ? FROM dataset d WHERE d.name = ? "
                        + "ON CONFLICT (user_name, dataset) DO UPDATE SET expires_at = EXCLUDED.expires_at, "
                        + "granted_by = EXCLUDED.granted_by, granted_at = now()")) {
                int pending = 0;
                for (var e : entries) {
                    ps.setString(1, e.user());
                    ps.setTimestamp(2, e.expiresAt() == null ? null : Timestamp.from(e.expiresAt()));
                    ps.setString(3, grantedBy); ps.setString(4, e.dataset()); ps.addBatch();
                    if (++pending == 1000) { set += sum(ps.executeBatch()); pending = 0; }
                }
                if (pending > 0) set += sum(ps.executeBatch());
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Access import failed: " + e.getMessage(), e);
        }
        return set;
    }

    /** The users that have access to a dataset (all of them: a dataset has few users). */
    public List<AccessRow> accessOfDataset(String dataset) {
        return query("SELECT user_name, dataset, granted_at, expires_at, granted_by FROM dataset_access WHERE dataset = ? ORDER BY user_name",
            ps -> ps.setString(1, dataset), this::accessRow);
    }

    /** The datasets a user has access to (a user has few datasets). */
    public List<AccessRow> accessOfUser(String user) {
        return query("SELECT user_name, dataset, granted_at, expires_at, granted_by FROM dataset_access WHERE user_name = ? ORDER BY dataset",
            ps -> ps.setString(1, user), this::accessRow);
    }

    /** The file-level exceptions of a user, one page. */
    public Page<String> exceptionsOfUser(String user, int page, int size) {
        long total = query("SELECT count(*) FROM dataset_grant WHERE user_name = ?", ps -> ps.setString(1, user), rs -> rs.getLong(1)).get(0);
        List<String> rows = query("SELECT f.path FROM dataset_grant g JOIN dataset_file f ON f.id = g.file_id WHERE g.user_name = ? ORDER BY f.path LIMIT ? OFFSET ?",
            ps -> { ps.setString(1, user); ps.setInt(2, size); ps.setInt(3, page * size); }, rs -> rs.getString(1));
        return new Page<>(rows, total, page, size);
    }

    /** Datasets a user may read now (access that has not expired). */
    public List<String> activeDatasets(String user) {
        return query("SELECT dataset FROM dataset_access WHERE user_name = ? AND (expires_at IS NULL OR expires_at > now()) ORDER BY dataset",
            ps -> ps.setString(1, user), rs -> rs.getString(1));
    }

    /** Users with an entry in either grant table, whose name contains {@code q}, one page. */
    public Page<UserRow> users(String q, int page, int size) {
        String names = "(SELECT user_name FROM dataset_access UNION SELECT user_name FROM dataset_grant) u";
        long total = query("SELECT count(*) FROM " + names + " WHERE u.user_name ILIKE ?", ps -> ps.setString(1, like(q)), rs -> rs.getLong(1)).get(0);
        List<UserRow> rows = query("SELECT u.user_name, (SELECT count(*) FROM dataset_access a WHERE a.user_name = u.user_name), "
                + "(SELECT count(*) FROM dataset_grant g WHERE g.user_name = u.user_name) FROM " + names
                + " WHERE u.user_name ILIKE ? ORDER BY u.user_name LIMIT ? OFFSET ?",
            ps -> { ps.setString(1, like(q)); ps.setInt(2, size); ps.setInt(3, page * size); },
            rs -> new UserRow(rs.getString(1), rs.getLong(2), rs.getLong(3)));
        return new Page<>(rows, total, page, size);
    }


    // ── audit trail ────────────────────────────────────────────────────────────

    public record AuditRow(long id, Instant at, String eventType, String actor, String user, String sessionId,
                           String podName, String tool, String dataset, String detail) {}

    /** Appends one event to the audit trail (nothing can change it afterwards). */
    public void addAudit(String eventType, String actor, String user, String sessionId, String podName,
                         String tool, String dataset, String detail) {
        execute("INSERT INTO audit_event (event_type, actor, user_name, session_id, pod_name, tool, dataset, detail) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ps -> {
                ps.setString(1, eventType); ps.setString(2, actor); ps.setString(3, user); ps.setString(4, sessionId);
                ps.setString(5, podName); ps.setString(6, tool); ps.setString(7, dataset);
                ps.setString(8, detail == null ? "" : detail);
            });
    }

    /** The audit trail, newest first; each filter that is not empty must match (user, dataset and type exactly). */
    public Page<AuditRow> audit(String user, String dataset, String type, int page, int size) {
        String where = "WHERE (? = '' OR user_name = ?) AND (? = '' OR dataset = ?) AND (? = '' OR event_type = ?)";
        String u = user == null ? "" : user.strip(), d = dataset == null ? "" : dataset.strip(), t = type == null ? "" : type.strip();
        long total = query("SELECT count(*) FROM audit_event " + where,
            ps -> { ps.setString(1, u); ps.setString(2, u); ps.setString(3, d); ps.setString(4, d); ps.setString(5, t); ps.setString(6, t); },
            rs -> rs.getLong(1)).get(0);
        List<AuditRow> rows = query("SELECT id, at, event_type, actor, user_name, session_id, pod_name, tool, dataset, detail "
                + "FROM audit_event " + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            ps -> {
                ps.setString(1, u); ps.setString(2, u); ps.setString(3, d); ps.setString(4, d); ps.setString(5, t); ps.setString(6, t);
                ps.setInt(7, size); ps.setInt(8, page * size);
            },
            rs -> new AuditRow(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)));
        return new Page<>(rows, total, page, size);
    }

    private AccessRow accessRow(ResultSet rs) throws SQLException {
        Timestamp granted = rs.getTimestamp(3);
        Timestamp expires = rs.getTimestamp(4);
        return new AccessRow(rs.getString(1), rs.getString(2), granted == null ? null : granted.toInstant(),
            expires == null ? null : expires.toInstant(), rs.getString(5));
    }

    private GrantRow grantRow(ResultSet rs) throws SQLException {
        Timestamp granted = rs.getTimestamp(5);
        Timestamp expires = rs.getTimestamp(6);
        return new GrantRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
            granted == null ? null : granted.toInstant(), expires == null ? null : expires.toInstant(),
            rs.getString(7));
    }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    @FunctionalInterface
    private interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    private <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
        try (Connection c = dataSource.get().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Dataset query failed: " + e.getMessage(), e);
        }
    }

    private void execute(String sql, Binder binder) {
        try (Connection c = dataSource.get().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Dataset update failed: " + e.getMessage(), e);
        }
    }

    private long update(String sql, Binder binder) {
        try (Connection c = dataSource.get().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Dataset update failed: " + e.getMessage(), e);
        }
    }
}
