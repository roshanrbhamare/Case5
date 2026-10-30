package com.rostersync.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rostersync.domain.DomainEvents.OutboxMessage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    public record OutboxRow(long id, UUID eventId, String tenantId, String aggregateId, String eventType, String payloadJson,
                            int attempts, OffsetDateTime createdAt) {}

    public record OutboxStats(int pending, int dead, double oldestPendingSeconds) {}

    private static final RowMapper<OutboxRow> MAPPER = (rs, i) -> new OutboxRow(rs.getLong("id"), rs.getObject("event_id", UUID.class),
            rs.getString("tenant_id"), rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"),
            rs.getInt("attempts"), Sql.timestamp(rs, "created_at"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Called inside the business transaction: the change and its event commit or vanish together. */
    public void add(OutboxMessage message) {
        try {
            jdbc.update("INSERT INTO outbox (event_id, tenant_id, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?, ?::jsonb)",
                    UUID.randomUUID(), message.tenantId(), message.aggregateId(), message.eventType(), json.writeValueAsString(message.payload()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload is not serialisable", e);
        }
    }

    /** SKIP LOCKED lets several relays drain the outbox in parallel without double-claiming. */
    public List<OutboxRow> claimBatch(int limit) {
        return jdbc.query("""
                SELECT id, event_id, tenant_id, aggregate_id, event_type, payload::text AS payload, attempts, created_at
                  FROM outbox
                 WHERE status = 'PENDING' AND next_attempt_at <= now()
                 ORDER BY id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED""", MAPPER, limit);
    }

    public void markSent(List<Long> ids) {
        jdbc.update("UPDATE outbox SET status = 'SENT', attempts = attempts + 1, published_at = now(), last_error = NULL WHERE id = ANY(?::bigint[])",
                Sql.numberArray(ids));
    }

    public void scheduleRetry(long id, String error, long delayMs) {
        jdbc.update("UPDATE outbox SET attempts = attempts + 1, last_error = ?, next_attempt_at = now() + make_interval(secs => ? / 1000.0) WHERE id = ?",
                error, (double) delayMs, id);
    }

    public void moveToDeadLetter(OutboxRow row, String error) {
        jdbc.update("""
                INSERT INTO dead_letter (outbox_id, event_id, tenant_id, event_type, payload, attempts, last_error)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)""",
                row.id(), row.eventId(), row.tenantId(), row.eventType(), row.payloadJson(), row.attempts() + 1, error);
        jdbc.update("UPDATE outbox SET status = 'DEAD', attempts = attempts + 1, last_error = ? WHERE id = ?", error, row.id());
    }

    /** Queue depth and lag; a null tenant means the whole system (metrics). */
    public OutboxStats stats(String tenantId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'PENDING')::int AS pending,
                       count(*) FILTER (WHERE status = 'DEAD')::int AS dead,
                       coalesce(extract(epoch FROM now() - min(created_at) FILTER (WHERE status = 'PENDING')), 0)::float8 AS oldest
                  FROM outbox
                 WHERE (?::text IS NULL OR tenant_id = ?) AND status IN ('PENDING', 'DEAD')""",
                (rs, i) -> new OutboxStats(rs.getInt("pending"), rs.getInt("dead"), rs.getDouble("oldest")), tenantId, tenantId);
    }
}
