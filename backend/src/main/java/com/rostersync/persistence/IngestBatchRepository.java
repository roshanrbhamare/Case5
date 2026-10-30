package com.rostersync.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class IngestBatchRepository {

    public record Batch(UUID id, String tenantId, String source, String actor, int received, int applied, int stale, int duplicate,
                        int rejected, OffsetDateTime createdAt) {}

    public record BatchError(int index, String employeeId, String code, String field) {}

    public record Totals(int received, int applied, int stale, int duplicate, int rejected, int files) {}

    public record Hourly(OffsetDateTime hour, int applied, int skipped, int rejected) {}

    public record CodeCount(String code, int n) {}

    private static final RowMapper<Batch> BATCH = (rs, i) -> new Batch(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
            rs.getString("source"), rs.getString("actor"), rs.getInt("received"), rs.getInt("applied"), rs.getInt("stale"),
            rs.getInt("duplicate"), rs.getInt("rejected"), Sql.timestamp(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public IngestBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Batch batch, List<BatchError> errors) {
        jdbc.update("""
                INSERT INTO ingest_batches (id, tenant_id, source, actor, received, applied, stale, duplicate, rejected)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET received = EXCLUDED.received, applied = EXCLUDED.applied, stale = EXCLUDED.stale,
                  duplicate = EXCLUDED.duplicate, rejected = EXCLUDED.rejected""",
                batch.id(), batch.tenantId(), batch.source(), batch.actor(), batch.received(), batch.applied(), batch.stale(),
                batch.duplicate(), batch.rejected());
        addErrors(batch.id(), batch.tenantId(), errors);
    }

    /** One batched round trip regardless of error count. */
    public void addErrors(UUID batchId, String tenantId, List<BatchError> errors) {
        if (errors.isEmpty()) return;
        jdbc.batchUpdate("INSERT INTO ingest_errors (batch_id, tenant_id, record_index, employee_id, code, field) VALUES (?, ?, ?, ?, ?, ?)",
                errors.stream().map(e -> new Object[] {batchId, tenantId, e.index(), e.employeeId(), e.code(), e.field()}).toList());
    }

    public List<Batch> list(String tenantId, int limit) {
        return jdbc.query("SELECT * FROM ingest_batches WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?", BATCH, tenantId, limit);
    }

    public Optional<Batch> get(String tenantId, UUID batchId) {
        return jdbc.query("SELECT * FROM ingest_batches WHERE tenant_id = ? AND id = ?", BATCH, tenantId, batchId).stream().findFirst();
    }

    public List<BatchError> errors(String tenantId, UUID batchId, int limit) {
        return jdbc.query("""
                SELECT record_index, employee_id, code, field FROM ingest_errors
                 WHERE tenant_id = ? AND batch_id = ? ORDER BY record_index, id LIMIT ?""",
                (rs, i) -> new BatchError(rs.getInt("record_index"), rs.getString("employee_id"), rs.getString("code"), rs.getString("field")),
                tenantId, batchId, limit);
    }

    /**
     * Live-feed outcomes, plus nightly files counted separately: a file's rows only count as
     * received/rejected here; what a run applied lives on reconcile_runs.
     */
    public Totals totalsSince(String tenantId, int hours) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(received) FILTER (WHERE source = 'API'), 0)::int AS received,
                       coalesce(sum(applied) FILTER (WHERE source = 'API'), 0)::int AS applied,
                       coalesce(sum(stale) FILTER (WHERE source = 'API'), 0)::int AS stale,
                       coalesce(sum(duplicate) FILTER (WHERE source = 'API'), 0)::int AS duplicate,
                       coalesce(sum(rejected), 0)::int AS rejected,
                       count(*) FILTER (WHERE source = 'NIGHTLY_FILE')::int AS files
                  FROM ingest_batches WHERE tenant_id = ? AND created_at > now() - make_interval(hours => ?)""",
                (rs, i) -> new Totals(rs.getInt("received"), rs.getInt("applied"), rs.getInt("stale"), rs.getInt("duplicate"),
                        rs.getInt("rejected"), rs.getInt("files")),
                tenantId, hours);
    }

    /** Live feed only; nightly files have their own card. */
    public List<Hourly> hourlySince(String tenantId, int hours) {
        return jdbc.query("""
                SELECT date_trunc('hour', created_at) AS hour, sum(applied)::int AS applied, sum(stale + duplicate)::int AS skipped,
                       sum(rejected)::int AS rejected
                  FROM ingest_batches WHERE tenant_id = ? AND source = 'API' AND created_at > now() - make_interval(hours => ?)
                 GROUP BY 1 ORDER BY 1""",
                (rs, i) -> new Hourly(Sql.timestamp(rs, "hour"), rs.getInt("applied"), rs.getInt("skipped"), rs.getInt("rejected")),
                tenantId, hours);
    }

    public List<CodeCount> topErrorCodes(String tenantId, int hours, int limit) {
        return jdbc.query("""
                SELECT ie.code, count(*)::int AS n FROM ingest_errors ie JOIN ingest_batches b ON b.id = ie.batch_id
                 WHERE ie.tenant_id = ? AND b.created_at > now() - make_interval(hours => ?)
                 GROUP BY ie.code ORDER BY n DESC LIMIT ?""",
                (rs, i) -> new CodeCount(rs.getString("code"), rs.getInt("n")), tenantId, hours, limit);
    }
}
