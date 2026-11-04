package com.scivicslab.k8spups.billing;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the billing records that the billing system asks for: the views {@code pod_usage_billable} and
 * {@code storage_reservation_daily} of the database {@code entitlement} (see {@code V8__pod_usage_and_storage_reservation.sql}).
 * Both reads of one request run in one transaction, so the time {@code generated_at} is the time that the seconds of the rows still
 * running were counted up to.
 */
@ApplicationScoped
public class BillingRepository {

    /** One Pod of a tool session. {@code endedAt} is null while the Pod runs. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PodUsageRow(long id, String sessionId, String userName, String tool, String profile,
                              Integer limitsCpuMillicores, String readyAt, String endedAt, String endReason,
                              double billedSeconds, double coreSeconds) {}

    /** The storage that an owner (a user or a group) had reserved on a day (Japan time): the largest size of that day. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DailyReservationRow(String ownerKind, String ownerName, String storageType, String day, long reservedBytes) {}

    /** The rows of one response, and the time they were counted up to. */
    public record PodUsagePage(String generatedAt, List<PodUsageRow> rows) {}

    public record DailyReservationPage(String generatedAt, List<DailyReservationRow> rows) {}

    @Inject
    AgroalDataSource dataSource;

    /**
     * The Pods that were ready before {@code to} and had not ended before {@code from}, in the order of their ids, after the
     * id {@code afterId}. A null {@code from} or {@code to} leaves that side open.
     */
    public PodUsagePage podUsage(Instant from, Instant to, long afterId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, session_id, user_name, tool, profile, limits_cpu_millicores, ready_at, ")
            .append("ended_at, end_reason, billed_seconds, core_seconds FROM pod_usage_billable WHERE id > ?");
        if (from != null) sql.append(" AND (ended_at IS NULL OR ended_at >= ?)");
        if (to != null) sql.append(" AND ready_at < ?");
        sql.append(" ORDER BY id LIMIT ?");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                int i = 1;
                ps.setLong(i++, afterId);
                if (from != null) ps.setTimestamp(i++, Timestamp.from(from));
                if (to != null) ps.setTimestamp(i++, Timestamp.from(to));
                ps.setInt(i, limit);
                List<PodUsageRow> rows = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int cpu = rs.getInt("limits_cpu_millicores");
                        rows.add(new PodUsageRow(rs.getLong("id"), rs.getString("session_id"), rs.getString("user_name"),
                            rs.getString("tool"), rs.getString("profile"), rs.wasNull() ? null : cpu,
                            instant(rs, "ready_at"), instant(rs, "ended_at"), rs.getString("end_reason"),
                            rs.getDouble("billed_seconds"), rs.getDouble("core_seconds")));
                    }
                }
                String generatedAt = now(c);
                c.commit();
                return new PodUsagePage(generatedAt, rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading pod_usage_billable failed: " + e.getMessage(), e);
        }
    }

    /** The reservations of the days {@code from} to {@code to}, both included. */
    public DailyReservationPage dailyReservations(LocalDate from, LocalDate to) {
        String sql = "SELECT owner_kind, owner_name, storage_type, day, reserved_bytes FROM storage_reservation_daily "
            + "WHERE day BETWEEN ? AND ? ORDER BY day, owner_kind, owner_name, storage_type";
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, from);
                ps.setObject(2, to);
                List<DailyReservationRow> rows = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new DailyReservationRow(rs.getString("owner_kind"), rs.getString("owner_name"),
                            rs.getString("storage_type"), rs.getObject("day", LocalDate.class).toString(),
                            rs.getLong("reserved_bytes")));
                    }
                }
                String generatedAt = now(c);
                c.commit();
                return new DailyReservationPage(generatedAt, rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading storage_reservation_daily failed: " + e.getMessage(), e);
        }
    }

    private static String instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant().toString();
    }

    /** The start time of the transaction, which is also the time {@code now()} gave to the views. */
    private static String now(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT now()"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getTimestamp(1).toInstant().toString();
        }
    }
}
