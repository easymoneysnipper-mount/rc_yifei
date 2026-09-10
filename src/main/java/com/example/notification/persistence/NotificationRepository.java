package com.example.notification.persistence;

import com.example.notification.model.NotificationRecord;
import com.example.notification.model.NotificationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    private final RowMapper<NotificationRecord> mapper = this::map;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public NotificationRecord insert(NotificationRecord notification) {
        jdbc.update("""
                INSERT INTO notifications (id, idempotency_key, request_hash, target_url, headers_json, body_json,
                    status, attempts, next_attempt_at, lease_token, lease_until, last_http_status, last_error,
                    created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, notification.id(), notification.idempotencyKey(), notification.requestHash(), notification.targetUrl(),
                notification.headersJson(), notification.bodyJson(), notification.status().name(), notification.attempts(),
                Timestamp.from(notification.nextAttemptAt()), notification.leaseToken(), timestamp(notification.leaseUntil()),
                notification.lastHttpStatus(), notification.lastError(), Timestamp.from(notification.createdAt()),
                Timestamp.from(notification.updatedAt()), timestamp(notification.completedAt()));
        return notification;
    }

    public Optional<NotificationRecord> findByIdempotencyKey(String key) {
        return queryOne("SELECT * FROM notifications WHERE idempotency_key = ?", key);
    }

    public Optional<NotificationRecord> findById(UUID id) {
        return queryOne("SELECT * FROM notifications WHERE id = ?", id);
    }

    @Transactional
    public List<NotificationRecord> claimDue(int batchSize, Instant now, Instant leaseUntil) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM notifications
                WHERE (status = 'PENDING' AND next_attempt_at <= ?)
                   OR (status = 'DELIVERING' AND lease_until < ?)
                ORDER BY next_attempt_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, UUID.class, Timestamp.from(now), Timestamp.from(now), batchSize);

        return ids.stream().map(id -> {
            UUID token = UUID.randomUUID();
            jdbc.update("""
                    UPDATE notifications
                    SET status = 'DELIVERING', attempts = attempts + 1, lease_token = ?, lease_until = ?,
                        updated_at = ?
                    WHERE id = ?
                    """, token, Timestamp.from(leaseUntil), Timestamp.from(now), id);
            return findById(id).orElseThrow();
        }).toList();
    }

    public boolean markSucceeded(UUID id, UUID leaseToken, Instant now, int httpStatus) {
        return jdbc.update("""
                UPDATE notifications
                SET status = 'SUCCEEDED', last_http_status = ?, last_error = NULL, lease_token = NULL,
                    lease_until = NULL, completed_at = ?, updated_at = ?
                WHERE id = ? AND status = 'DELIVERING' AND lease_token = ?
                """, httpStatus, Timestamp.from(now), Timestamp.from(now), id, leaseToken) == 1;
    }

    public boolean markRetry(UUID id, UUID leaseToken, Instant now, Instant nextAttempt, Integer httpStatus,
                             String error) {
        return jdbc.update("""
                UPDATE notifications
                SET status = 'PENDING', next_attempt_at = ?, last_http_status = ?, last_error = ?,
                    lease_token = NULL, lease_until = NULL, updated_at = ?
                WHERE id = ? AND status = 'DELIVERING' AND lease_token = ?
                """, Timestamp.from(nextAttempt), httpStatus, truncate(error), Timestamp.from(now), id, leaseToken) == 1;
    }

    public boolean markFailed(UUID id, UUID leaseToken, Instant now, Integer httpStatus, String error) {
        return jdbc.update("""
                UPDATE notifications
                SET status = 'FAILED', last_http_status = ?, last_error = ?, lease_token = NULL,
                    lease_until = NULL, completed_at = ?, updated_at = ?
                WHERE id = ? AND status = 'DELIVERING' AND lease_token = ?
                """, httpStatus, truncate(error), Timestamp.from(now), Timestamp.from(now), id, leaseToken) == 1;
    }

    private Optional<NotificationRecord> queryOne(String sql, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst();
    }

    private NotificationRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationRecord(
                rs.getObject("id", UUID.class), rs.getString("idempotency_key"), rs.getString("request_hash"),
                rs.getString("target_url"), rs.getString("headers_json"), rs.getString("body_json"),
                NotificationStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(), rs.getObject("lease_token", UUID.class),
                instant(rs.getTimestamp("lease_until")), (Integer) rs.getObject("last_http_status"),
                rs.getString("last_error"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), instant(rs.getTimestamp("completed_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 997) + "...";
    }
}
