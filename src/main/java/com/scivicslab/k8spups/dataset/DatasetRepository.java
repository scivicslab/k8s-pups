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
