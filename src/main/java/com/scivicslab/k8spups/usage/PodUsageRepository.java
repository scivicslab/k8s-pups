package com.scivicslab.k8spups.usage;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;

/**
 * Writes {@code pod_usage} (PostgreSQL, plain JDBC): one row per tool session, written when the Pod is created and
 * completed when the session becomes ready and when it ends. The database refuses to change what was written
 * (see {@code V8__pod_usage_and_storage_reservation.sql}).
 */
@ApplicationScoped
public class PodUsageRepository {

    /**
     * A Pod that was created for a session.
     *
     * @param sessionId the tool session
     * @param user      the user the session belongs to
     * @param tool      name of the tool
     * @param podName   name of the Pod
     * @param cores     the resource profile of the session and its CPU
     * @param createdAt when Kubernetes accepted the Pod
     */
    public record Start(String sessionId, String user, String tool, String podName, PodCores cores, Instant createdAt) {}

    @Inject
    AgroalDataSource dataSource;

    /** Adds the row of a session; does nothing when the session already has one (a restored session). */
    public void open(Start s) {
        execute("INSERT INTO pod_usage (session_id, user_name, tool, pod_name, profile, requests_cpu_millicores, "
            + "limits_cpu_millicores, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (session_id) DO NOTHING", ps -> {
                ps.setString(1, s.sessionId());
                ps.setString(2, s.user());
                ps.setString(3, s.tool());
                ps.setString(4, s.podName());
                ps.setString(5, s.cores().profile());
                setInt(ps, 6, s.cores().requestsMillis());
                setInt(ps, 7, s.cores().limitsMillis());
                ps.setTimestamp(8, Timestamp.from(s.createdAt()));
            });
    }

    /** Writes the time the session became ready, once; does nothing for a session that is ready or has ended. */
    public void ready(String sessionId, Instant at) {
        execute("UPDATE pod_usage SET ready_at = ?, last_seen_at = now() "
            + "WHERE session_id = ? AND ready_at IS NULL AND ended_at IS NULL", ps -> {
                ps.setTimestamp(1, Timestamp.from(at));
                ps.setString(2, sessionId);
            });
    }

    /**
     * Writes the time the Pod was gone and why, once; does nothing for a session that has ended. The time is never
     * earlier than the creation or the ready time of the session.
     */
    public void end(String sessionId, Instant at, String reason) {
        execute("UPDATE pod_usage SET ended_at = GREATEST(?::timestamptz, created_at, ready_at), end_reason = ? "
            + "WHERE session_id = ? AND ended_at IS NULL", ps -> {
                ps.setTimestamp(1, Timestamp.from(at));
                ps.setString(2, reason);
                ps.setString(3, sessionId);
            });
    }

    /** Writes now as the last time the controller saw each of these sessions alive, for the sessions that are open. */
    public void seen(Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) return;
        withArray(sessionIds, "UPDATE pod_usage SET last_seen_at = now() "
            + "WHERE ended_at IS NULL AND session_id = ANY(?)");
    }

    /**
     * Ends every open session that is not in {@code liveSessionIds}, at the last time the controller saw it alive.
     * Called once when the controller starts, with the sessions whose Pods it took over: a session that was open when
     * the previous controller stopped and whose Pod is gone now ended while no controller was watching.
     *
     * @return how many sessions were ended
     */
    public int endOpenExcept(Collection<String> liveSessionIds, String reason) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE pod_usage "
                 + "SET ended_at = GREATEST(last_seen_at, created_at, ready_at), end_reason = ? "
                 + "WHERE ended_at IS NULL AND NOT (session_id = ANY(?))")) {
            ps.setString(1, reason);
            ps.setArray(2, c.createArrayOf("text", liveSessionIds.toArray()));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Pod usage update failed: " + e.getMessage(), e);
        }
    }

    private void withArray(Collection<String> values, String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", values.toArray()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Pod usage update failed: " + e.getMessage(), e);
        }
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER); else ps.setInt(index, value);
    }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private void execute(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Pod usage update failed: " + e.getMessage(), e);
        }
    }
}
