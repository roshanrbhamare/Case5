package com.rostersync.persistence;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReconcileRepository {

    public enum RunStatus { STAGING, QUEUED, ANALYZING, BLOCKED, APPROVED, APPLYING, COMPLETED, REJECTED, FAILED }

    public record RunRow(UUID id, String tenantId, UUID batchId, RunStatus status, String uploadedBy, double threshold, int fileRows,
                         int rejectedRows, int duplicateRows, Integer activeBefore, Integer missingCount, Double missingRatio,
                         Integer creates, Integer updates, Integer reactivations, Integer deactivations, Integer unchanged,
                         Integer stale, Integer conflicts, Integer bucketsSkipped, Integer appliedChanges, String failureCode,
                         String decidedBy, String decisionNote, String leaseOwner, OffsetDateTime createdAt, OffsetDateTime analyzedAt,
                         OffsetDateTime decidedAt, OffsetDateTime completedAt) {}

    public record Claimed(UUID id, String tenantId, String status) {}

    public record ChangedRow(String employeeId, String fullName, String email, String phone, String managerId, String officeCode,
                             String shiftCode, long version, String hrHash, Long storedVersion, String storedHash, String storedStatus,
                             String storedInactiveReason, int rowNo) {}

    public record Duplicate(String employeeId, int rowNo) {}

    public record Digest(int bucket, String digest) {}

    public static final String COPY_STAGING_SQL = "COPY reconcile_staging (run_id, tenant_id, employee_id, full_name, email, phone, "
            + "manager_id, office_code, shift_code, version, hr_hash, bucket, row_no) FROM STDIN WITH (FORMAT csv)";

    private static final Set<String> UPDATABLE = Set.of("status", "file_rows", "rejected_rows", "duplicate_rows", "active_before",
            "missing_count", "missing_ratio", "creates", "updates", "reactivations", "deactivations", "unchanged", "stale", "conflicts",
            "buckets_skipped", "applied_changes", "failure_code", "decided_by", "decision_note", "lease_owner", "lease_expires_at",
            "analyzed_at", "decided_at", "completed_at");

    private static final RowMapper<RunRow> RUN = (rs, i) -> new RunRow(
            rs.getObject("id", UUID.class), rs.getString("tenant_id"), rs.getObject("batch_id", UUID.class),
            RunStatus.valueOf(rs.getString("status")), rs.getString("uploaded_by"), rs.getDouble("threshold"), rs.getInt("file_rows"),
            rs.getInt("rejected_rows"), rs.getInt("duplicate_rows"), Sql.nullableInt(rs, "active_before"), Sql.nullableInt(rs, "missing_count"),
            Sql.nullableDouble(rs, "missing_ratio"), Sql.nullableInt(rs, "creates"), Sql.nullableInt(rs, "updates"),
            Sql.nullableInt(rs, "reactivations"), Sql.nullableInt(rs, "deactivations"), Sql.nullableInt(rs, "unchanged"),
            Sql.nullableInt(rs, "stale"), Sql.nullableInt(rs, "conflicts"), Sql.nullableInt(rs, "buckets_skipped"),
            Sql.nullableInt(rs, "applied_changes"), rs.getString("failure_code"), rs.getString("decided_by"), rs.getString("decision_note"),
            rs.getString("lease_owner"), Sql.timestamp(rs, "created_at"), Sql.timestamp(rs, "analyzed_at"), Sql.timestamp(rs, "decided_at"),
            Sql.timestamp(rs, "completed_at"));

    private final JdbcTemplate jdbc;

    public ReconcileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RunRow create(UUID id, String tenantId, UUID batchId, String uploadedBy, double threshold, int leaseSeconds) {
        return jdbc.queryForObject("""
                INSERT INTO reconcile_runs (id, tenant_id, batch_id, status, uploaded_by, threshold, lease_expires_at)
                VALUES (?, ?, ?, 'STAGING', ?, ?, now() + make_interval(secs => ?))
                RETURNING *""", RUN, id, tenantId, batchId, uploadedBy, threshold, leaseSeconds);
    }

    public Optional<RunRow> get(String tenantId, UUID runId) {
        return jdbc.query("SELECT * FROM reconcile_runs WHERE tenant_id = ? AND id = ?", RUN, tenantId, runId).stream().findFirst();
    }

    public Optional<RunRow> getForUpdate(String tenantId, UUID runId) {
        return jdbc.query("SELECT * FROM reconcile_runs WHERE tenant_id = ? AND id = ? FOR UPDATE", RUN, tenantId, runId).stream().findFirst();
    }

    public List<RunRow> list(String tenantId, int limit) {
        return jdbc.query("SELECT * FROM reconcile_runs WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?", RUN, tenantId, limit);
    }

    /** Updates the given columns (whitelisted) and returns the new row. */
    public RunRow update(String tenantId, UUID runId, Map<String, Object> fields) {
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        fields.forEach((column, value) -> {
            if (!UPDATABLE.contains(column)) throw new IllegalArgumentException("Not an updatable column: " + column);
            sets.add(column + " = ?");
            params.add(value instanceof Enum<?> e ? e.name() : value);
        });
        sets.add("updated_at = now()");
        params.add(tenantId);
        params.add(runId);
        return jdbc.queryForObject("UPDATE reconcile_runs SET " + String.join(", ", sets) + " WHERE tenant_id = ? AND id = ? RETURNING *",
                RUN, params.toArray());
    }

    public void renewLease(String tenantId, UUID runId, String owner, int seconds) {
        jdbc.update("UPDATE reconcile_runs SET lease_owner = ?, lease_expires_at = now() + make_interval(secs => ?), updated_at = now() "
                + "WHERE tenant_id = ? AND id = ?", owner, seconds, tenantId, runId);
    }

    /** Keeps the highest version of each employee id that appears more than once. One sort, O(n log n) whatever the plan. */
    public List<Duplicate> removeDuplicates(UUID runId) {
        return jdbc.query("""
                WITH ranked AS (
                  SELECT employee_id, row_no,
                         row_number() OVER (PARTITION BY employee_id ORDER BY version DESC, row_no DESC) AS rn
                    FROM reconcile_staging WHERE run_id = ?
                )
                DELETE FROM reconcile_staging s
                 USING ranked r
                 WHERE r.rn > 1 AND s.run_id = ? AND s.employee_id = r.employee_id AND s.row_no = r.row_no
                 RETURNING s.employee_id, s.row_no""",
                (rs, i) -> new Duplicate(rs.getString("employee_id"), rs.getInt("row_no")), runId, runId);
    }

    /** Fresh planner statistics after a bulk load, so Postgres picks hash joins for the diff. */
    public void refreshStagingStats() {
        jdbc.execute("ANALYZE reconcile_staging");
    }

    public int countActive(String tenantId) {
        return jdbc.queryForObject("SELECT count(*)::int FROM employees WHERE tenant_id = ? AND status = 'ACTIVE'", Integer.class, tenantId);
    }

    /** Active employees the file does not mention. Hash anti-join on indexed columns. */
    public int countMissing(String tenantId, UUID runId) {
        return jdbc.queryForObject("""
                SELECT count(*)::int FROM employees e
                 WHERE e.tenant_id = ? AND e.status = 'ACTIVE'
                   AND NOT EXISTS (SELECT 1 FROM reconcile_staging s WHERE s.run_id = ? AND s.employee_id = e.employee_id)""",
                Integer.class, tenantId, runId);
    }

    /**
     * Per-bucket digests of (employee_id, hr_hash, version). Buckets whose digests match on both
     * sides are identical and skipped; only differing buckets are compared row by row.
     */
    public List<Digest> storeDigests(String tenantId) {
        return jdbc.query("""
                SELECT bucket, md5(string_agg(employee_id || ':' || hr_hash || ':' || hr_version, ',' ORDER BY employee_id)) AS digest
                  FROM employees WHERE tenant_id = ? AND status = 'ACTIVE' GROUP BY bucket""",
                (rs, i) -> new Digest(rs.getInt("bucket"), rs.getString("digest")), tenantId);
    }

    public List<Digest> fileDigests(UUID runId) {
        return jdbc.query("""
                SELECT bucket, md5(string_agg(employee_id || ':' || hr_hash || ':' || version, ',' ORDER BY employee_id)) AS digest
                  FROM reconcile_staging WHERE run_id = ? GROUP BY bucket""",
                (rs, i) -> new Digest(rs.getInt("bucket"), rs.getString("digest")), runId);
    }

    /** File rows in these buckets whose content, version or status differs from the store (or that are new). */
    public List<ChangedRow> changedRows(String tenantId, UUID runId, List<Integer> buckets) {
        return jdbc.query("""
                SELECT s.employee_id, s.full_name, s.email, s.phone, s.manager_id, s.office_code, s.shift_code, s.version, s.hr_hash, s.row_no,
                       e.hr_version AS stored_version, e.hr_hash AS stored_hash, e.status AS stored_status, e.inactive_reason AS stored_inactive_reason
                  FROM reconcile_staging s
                  LEFT JOIN employees e ON e.tenant_id = ? AND e.employee_id = s.employee_id
                 WHERE s.run_id = ? AND s.bucket = ANY(?::smallint[])
                   AND (e.employee_id IS NULL OR e.hr_hash <> s.hr_hash OR e.hr_version <> s.version OR e.status <> 'ACTIVE')
                 ORDER BY s.employee_id""",
                (rs, i) -> {
                    long stored = rs.getLong("stored_version");
                    Long storedVersion = rs.wasNull() ? null : stored;
                    return new ChangedRow(rs.getString("employee_id"), rs.getString("full_name"), rs.getString("email"), rs.getString("phone"),
                            rs.getString("manager_id"), rs.getString("office_code"), rs.getString("shift_code"), rs.getLong("version"),
                            rs.getString("hr_hash"), storedVersion, rs.getString("stored_hash"), rs.getString("stored_status"),
                            rs.getString("stored_inactive_reason"), rs.getInt("row_no"));
                },
                tenantId, runId, Sql.numberArray(buckets));
    }

    /**
     * Set-based insert of brand-new employees, with their outbox events, in one statement.
     * Initial loads and mass-joiner days stay fast.
     */
    public int bulkInsertNew(String tenantId, UUID runId, List<Integer> buckets) {
        return jdbc.queryForObject("""
                WITH inserted AS (
                  INSERT INTO employees (tenant_id, employee_id, full_name, email, phone, manager_id, office_code, shift_code,
                                         status, hr_version, hr_hash, bucket)
                  SELECT ?, s.employee_id, s.full_name, s.email, s.phone, s.manager_id, s.office_code, s.shift_code,
                         'ACTIVE', s.version, s.hr_hash, s.bucket
                    FROM reconcile_staging s
                   WHERE s.run_id = ? AND s.bucket = ANY(?::smallint[])
                     AND NOT EXISTS (SELECT 1 FROM employees e WHERE e.tenant_id = ? AND e.employee_id = s.employee_id)
                  ON CONFLICT (tenant_id, employee_id) DO NOTHING
                  RETURNING employee_id, hr_version, office_code, shift_code
                ), events AS (
                  INSERT INTO outbox (event_id, tenant_id, aggregate_id, event_type, payload)
                  SELECT gen_random_uuid(), ?, i.employee_id, 'EmployeeCreated',
                         jsonb_build_object('employeeId', i.employee_id, 'hrVersion', i.hr_version, 'transportVersion', 0,
                                            'status', 'ACTIVE', 'inactiveReason', NULL,
                                            'changedFields', jsonb_build_array('fullName', 'email', 'phone', 'managerId', 'officeCode', 'shiftCode', 'status'),
                                            'officeCode', i.office_code, 'shiftCode', i.shift_code, 'source', 'NIGHTLY_FILE')
                    FROM inserted i
                )
                SELECT count(*)::int FROM inserted""",
                Integer.class, tenantId, runId, Sql.numberArray(buckets), tenantId, tenantId);
    }

    /**
     * Set-based UPDATE for the common case: an active employee whose file version is newer.
     * Rows are locked first (FOR UPDATE); under READ COMMITTED a row changed by a concurrent
     * live event is re-checked, and if its version moved past the file's it is left alone.
     */
    public List<String> bulkApplyUpdates(String tenantId, UUID runId, List<Integer> buckets) {
        String bucketArray = Sql.numberArray(buckets);
        return jdbc.queryForList("""
                WITH before AS (
                  SELECT e.employee_id, e.full_name, e.email, e.phone, e.manager_id, e.office_code, e.shift_code
                    FROM employees e
                    JOIN reconcile_staging s ON s.run_id = ? AND s.employee_id = e.employee_id
                   WHERE e.tenant_id = ? AND e.bucket = ANY(?::smallint[]) AND s.bucket = ANY(?::smallint[])
                     AND e.status = 'ACTIVE' AND s.version > e.hr_version
                   FOR UPDATE OF e
                ), updated AS (
                  UPDATE employees e
                     SET full_name = s.full_name, email = s.email, phone = s.phone, manager_id = s.manager_id,
                         office_code = s.office_code, shift_code = s.shift_code, hr_version = s.version, hr_hash = s.hr_hash, updated_at = now()
                    FROM before b, reconcile_staging s
                   WHERE e.tenant_id = ? AND e.employee_id = b.employee_id AND s.run_id = ? AND s.employee_id = b.employee_id
                     AND e.status = 'ACTIVE' AND s.version > e.hr_version
                  RETURNING e.employee_id, e.hr_version, e.transport_version, e.office_code, e.shift_code,
                            array_remove(ARRAY[
                              CASE WHEN b.full_name IS DISTINCT FROM s.full_name THEN 'fullName' END,
                              CASE WHEN b.email IS DISTINCT FROM s.email THEN 'email' END,
                              CASE WHEN b.phone IS DISTINCT FROM s.phone THEN 'phone' END,
                              CASE WHEN b.manager_id IS DISTINCT FROM s.manager_id THEN 'managerId' END,
                              CASE WHEN b.office_code IS DISTINCT FROM s.office_code THEN 'officeCode' END,
                              CASE WHEN b.shift_code IS DISTINCT FROM s.shift_code THEN 'shiftCode' END
                            ], NULL) AS changed
                ), events AS (
                  INSERT INTO outbox (event_id, tenant_id, aggregate_id, event_type, payload)
                  SELECT gen_random_uuid(), ?, u.employee_id, 'EmployeeUpdated',
                         jsonb_build_object('employeeId', u.employee_id, 'hrVersion', u.hr_version, 'transportVersion', u.transport_version,
                                            'status', 'ACTIVE', 'inactiveReason', NULL, 'changedFields', to_jsonb(u.changed),
                                            'officeCode', u.office_code, 'shiftCode', u.shift_code, 'source', 'NIGHTLY_FILE')
                    FROM updated u WHERE cardinality(u.changed) > 0
                )
                SELECT employee_id FROM updated""",
                String.class, runId, tenantId, bucketArray, bucketArray, tenantId, runId, tenantId);
    }

    /** Set-based deactivation of active employees the file no longer lists (brake passed or operator approved). */
    public List<String> bulkDeactivateMissing(String tenantId, UUID runId, List<Integer> buckets) {
        return jdbc.queryForList("""
                WITH gone AS (
                  UPDATE employees e
                     SET status = 'INACTIVE', inactive_reason = 'MISSING_FROM_EXPORT', updated_at = now()
                   WHERE e.tenant_id = ? AND e.status = 'ACTIVE' AND e.bucket = ANY(?::smallint[])
                     AND NOT EXISTS (SELECT 1 FROM reconcile_staging s WHERE s.run_id = ? AND s.employee_id = e.employee_id)
                  RETURNING e.employee_id, e.hr_version, e.transport_version, e.office_code, e.shift_code
                ), events AS (
                  INSERT INTO outbox (event_id, tenant_id, aggregate_id, event_type, payload)
                  SELECT gen_random_uuid(), ?, g.employee_id, 'EmployeeDeactivated',
                         jsonb_build_object('employeeId', g.employee_id, 'hrVersion', g.hr_version, 'transportVersion', g.transport_version,
                                            'status', 'INACTIVE', 'inactiveReason', 'MISSING_FROM_EXPORT', 'changedFields', jsonb_build_array('status'),
                                            'officeCode', g.office_code, 'shiftCode', g.shift_code, 'source', 'NIGHTLY_FILE')
                    FROM gone g
                )
                SELECT employee_id FROM gone""",
                String.class, tenantId, Sql.numberArray(buckets), runId, tenantId);
    }

    public void clearStaging(UUID runId) {
        jdbc.update("DELETE FROM reconcile_staging WHERE run_id = ?", runId);
    }

    public int appliedSince(String tenantId, int hours) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(applied_changes), 0)::int FROM reconcile_runs
                 WHERE tenant_id = ? AND status = 'COMPLETED' AND completed_at > now() - make_interval(hours => ?)""",
                Integer.class, tenantId, hours);
    }

    /**
     * Claims the oldest run that needs work, with a lease. SKIP LOCKED lets several workers
     * claim different runs at once; an expired lease means its worker died, so another one
     * takes over. QUEUED moves to ANALYZING and APPROVED to APPLYING in the same statement.
     */
    public Optional<Claimed> claim(String owner, int leaseSeconds) {
        return jdbc.query("""
                UPDATE reconcile_runs r
                   SET status = CASE r.status WHEN 'QUEUED' THEN 'ANALYZING' WHEN 'APPROVED' THEN 'APPLYING' ELSE r.status END,
                       lease_owner = ?, lease_expires_at = now() + make_interval(secs => ?), updated_at = now()
                 WHERE r.id = (
                   SELECT c.id FROM reconcile_runs c
                    WHERE c.status IN ('QUEUED', 'APPROVED')
                       OR (c.status IN ('ANALYZING', 'APPLYING') AND c.lease_expires_at < now())
                    ORDER BY c.created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1)
                RETURNING r.id, r.tenant_id, r.status""",
                (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getString("tenant_id"), rs.getString("status")), owner, leaseSeconds)
                .stream().findFirst();
    }

    /** Uploads whose connection died mid-stream: mark them failed so the tenant can upload again. */
    public int failAbandonedUploads() {
        return jdbc.queryForObject("""
                WITH failed AS (
                  UPDATE reconcile_runs SET status = 'FAILED', failure_code = 'UPLOAD_ABANDONED', updated_at = now()
                   WHERE status = 'STAGING' AND lease_expires_at < now()
                  RETURNING id
                ), cleared AS (
                  DELETE FROM reconcile_staging s USING failed f WHERE s.run_id = f.id
                )
                SELECT count(*)::int FROM failed""", Integer.class);
    }
}
