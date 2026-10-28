package com.scivicslab.k8spups.storage;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads and writes {@code storage_allocation} and {@code tool_mount_preference} (PostgreSQL, plain JDBC). */
@ApplicationScoped
public class StorageAllocationRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One row of {@code storage_allocation}; {@code pv} is empty until the allocation is revoked. */
    public record Allocation(long id, String user, String type, String size, String state, String grantedBy,
                             Instant grantedAt, String revokedBy, Instant revokedAt, Long restoredFrom,
                             Optional<PvRecord> pv) {
        public boolean active() { return "active".equals(state); }
    }

    private static final String COLUMNS = "id, user_name, storage_type, size, state, granted_by, granted_at, revoked_by, "
        + "revoked_at, restored_from, pv_name, pv_storage_class, pv_capacity, pv_csi_driver, pv_volume_handle, "
        + "pv_volume_attributes::text";

    @Inject
    AgroalDataSource dataSource;

    /** The user's active allocations, one per type. */
    public List<Allocation> active(String user) {
        return query("SELECT " + COLUMNS + " FROM storage_allocation WHERE user_name = ? AND state = 'active' "
            + "ORDER BY storage_type", ps -> ps.setString(1, user), StorageAllocationRepository::row);
    }

    /** Every allocation of the user, active and revoked, newest first. */
    public List<Allocation> history(String user) {
        return query("SELECT " + COLUMNS + " FROM storage_allocation WHERE user_name = ? ORDER BY granted_at DESC, id DESC",
            ps -> ps.setString(1, user), StorageAllocationRepository::row);
    }

    /** Every active allocation of every user. */
    public List<Allocation> allActive() {
        return query("SELECT " + COLUMNS + " FROM storage_allocation WHERE state = 'active' ORDER BY user_name, storage_type",
            ps -> {}, StorageAllocationRepository::row);
    }

    /** Whether the user ever had an allocation (an administrator's revoke must not bring the default back). */
    public boolean hasAny(String user) {
        return !query("SELECT 1 FROM storage_allocation WHERE user_name = ? LIMIT 1", ps -> ps.setString(1, user),
            rs -> 1).isEmpty();
    }

    public Optional<Allocation> find(long id) {
        return query("SELECT " + COLUMNS + " FROM storage_allocation WHERE id = ?", ps -> ps.setLong(1, id),
            StorageAllocationRepository::row).stream().findFirst();
    }

    /** Adds an active allocation; fails when the user already has an active one of the type (unique index). */
    public long insert(String user, String type, String size, String grantedBy, Long restoredFrom) {
        return update("INSERT INTO storage_allocation (user_name, storage_type, size, granted_by, restored_from) "
            + "VALUES (?, ?, ?, ?, ?) RETURNING id", ps -> {
                ps.setString(1, user); ps.setString(2, type); ps.setString(3, size); ps.setString(4, grantedBy);
                if (restoredFrom == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, restoredFrom);
            });
    }

    public void resize(long id, String size) {
        execute("UPDATE storage_allocation SET size = ? WHERE id = ? AND state = 'active'",
            ps -> { ps.setString(1, size); ps.setLong(2, id); });
    }

    /** Marks the allocation revoked and keeps the PV it was bound to, for a later restore. */
    public void revoke(long id, String by, Optional<PvRecord> pv) {
        execute("UPDATE storage_allocation SET state = 'revoked', revoked_by = ?, revoked_at = now(), pv_name = ?, "
            + "pv_storage_class = ?, pv_capacity = ?, pv_csi_driver = ?, pv_volume_handle = ?, "
            + "pv_volume_attributes = ?::jsonb WHERE id = ? AND state = 'active'", ps -> {
                ps.setString(1, by);
                PvRecord r = pv.orElse(null);
                ps.setString(2, r != null ? r.name() : null);
                ps.setString(3, r != null ? r.storageClass() : null);
                ps.setString(4, r != null ? r.capacity() : null);
                ps.setString(5, r != null ? r.csiDriver() : null);
                ps.setString(6, r != null ? r.volumeHandle() : null);
                ps.setString(7, r != null ? toJson(r.volumeAttributes()) : null);
                ps.setLong(8, id);
            });
    }

    /** The mounts the user chose for the tool the last time, as stored (a JSON array); "[]" when none. */
    public String mountPreference(String user, String tool) {
        List<String> rows = query("SELECT mounts::text FROM tool_mount_preference WHERE user_name = ? AND tool = ?",
            ps -> { ps.setString(1, user); ps.setString(2, tool); }, rs -> rs.getString(1));
        return rows.isEmpty() ? "[]" : rows.get(0);
    }

    public void saveMountPreference(String user, String tool, String mountsJson) {
        execute("INSERT INTO tool_mount_preference (user_name, tool, mounts, updated_at) VALUES (?, ?, ?::jsonb, now()) "
            + "ON CONFLICT (user_name, tool) DO UPDATE SET mounts = EXCLUDED.mounts, updated_at = now()",
            ps -> { ps.setString(1, user); ps.setString(2, tool); ps.setString(3, mountsJson); });
    }

    private static Allocation row(ResultSet rs) throws SQLException {
        String pvName = rs.getString(11);
        Optional<PvRecord> pv = pvName == null ? Optional.empty() : Optional.of(new PvRecord(pvName, rs.getString(12),
            rs.getString(13), rs.getString(14), rs.getString(15), fromJson(rs.getString(16))));
        long restored = rs.getLong(10);
        return new Allocation(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), instant(rs.getTimestamp(7)), rs.getString(8), instant(rs.getTimestamp(9)),
            rs.wasNull() ? null : restored, pv);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String toJson(Map<String, String> m) {
        try {
            return JSON.writeValueAsString(m == null ? Map.of() : m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> fromJson(String s) throws SQLException {
        if (s == null) return Map.of();
        try {
            return JSON.readValue(s, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new SQLException("pv_volume_attributes is not a JSON object of strings: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    @FunctionalInterface
    private interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    private <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Storage allocation query failed: " + e.getMessage(), e);
        }
    }

    private void execute(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Storage allocation update failed: " + e.getMessage(), e);
        }
    }

    private long update(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Storage allocation update failed: " + e.getMessage(), e);
        }
    }
}
