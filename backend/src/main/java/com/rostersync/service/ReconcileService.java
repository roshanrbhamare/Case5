package com.rostersync.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.rostersync.config.RosterMetrics;
import com.rostersync.config.RosterSyncProperties;
import com.rostersync.domain.DomainEvents;
import com.rostersync.domain.EventValidator;
import com.rostersync.domain.EventValidator.FieldIssue;
import com.rostersync.domain.EventValidator.Invalid;
import com.rostersync.domain.EventValidator.Valid;
import com.rostersync.domain.Fields;
import com.rostersync.domain.Fields.InactiveReason;
import com.rostersync.domain.Fields.Status;
import com.rostersync.domain.HrEvent;
import com.rostersync.domain.RecordHasher;
import com.rostersync.domain.ReconcilePolicy;
import com.rostersync.domain.ReconcilePolicy.Action;
import com.rostersync.domain.SafetyBrake;
import com.rostersync.persistence.CacheEvictor;
import com.rostersync.persistence.IngestBatchRepository;
import com.rostersync.persistence.IngestBatchRepository.Batch;
import com.rostersync.persistence.IngestBatchRepository.BatchError;
import com.rostersync.persistence.OutboxRepository;
import com.rostersync.persistence.ReconcileRepository;
import com.rostersync.persistence.ReconcileRepository.ChangedRow;
import com.rostersync.persistence.ReconcileRepository.Digest;
import com.rostersync.persistence.ReconcileRepository.Duplicate;
import com.rostersync.persistence.ReconcileRepository.RunRow;
import com.rostersync.persistence.ReconcileRepository.RunStatus;
import com.rostersync.persistence.Transactions;
import com.rostersync.support.AppException;
import com.rostersync.support.AppException.ConflictException;
import com.rostersync.support.AppException.NotFoundException;
import com.rostersync.support.AppException.ValidationException;
import com.rostersync.support.Chaos;
import com.rostersync.support.Retry;
import io.micrometer.core.instrument.Timer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

/**
 * Nightly reconciliation in three phases, each restartable:
 *
 * <ol>
 *   <li><b>stage</b>: stream the CSV row by row (parse, validate, hash) straight into
 *       {@code COPY reconcile_staging}. Memory stays flat whatever the file size.</li>
 *   <li><b>analyze</b>: compare per-bucket digests (256 buckets) of the store and the file;
 *       only differing buckets are compared row by row. Count what would change and apply
 *       the safety brake before anything is written.</li>
 *   <li><b>apply</b>: bucket group by bucket group, one transaction each: set-based inserts,
 *       updates and exits, then per-record savepoints for rare cases. Groups already applied
 *       compare equal on a retry, so a crash mid-apply resumes without double-applying.</li>
 * </ol>
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);
    private static final int MAX_STORED_ERRORS = 5000;
    private static final int STAGING_LEASE_SECONDS = 15 * 60;
    private static final int COPY_CHUNK_CHARS = 256 * 1024;

    private record Base(int duplicates, int activeBefore, int missing, List<Integer> differing) {}

    private record GroupResult(int applied, int failed, List<String> touched) {}

    private static final class FileHeaderException extends RuntimeException {
        final List<String> missing;

        FileHeaderException(List<String> missing) {
            super("missing columns");
            this.missing = missing;
        }
    }

    private final Transactions tx;
    private final DataSource dataSource;
    private final ReconcileRepository runs;
    private final IngestBatchRepository batches;
    private final OutboxRepository outbox;
    private final EmployeeWriter writer;
    private final OfficeDirectory offices;
    private final CacheEvictor cache;
    private final RosterMetrics metrics;
    private final Chaos chaos;
    private final SafetyBrake brake;
    private final int groupSize;
    private final int leaseSeconds;

    public ReconcileService(Transactions tx, DataSource dataSource, ReconcileRepository runs, IngestBatchRepository batches,
                            OutboxRepository outbox, EmployeeWriter writer, OfficeDirectory offices, CacheEvictor cache, RosterMetrics metrics,
                            Chaos chaos, RosterSyncProperties props) {
        this.tx = tx;
        this.dataSource = dataSource;
        this.runs = runs;
        this.batches = batches;
        this.outbox = outbox;
        this.writer = writer;
        this.offices = offices;
        this.cache = cache;
        this.metrics = metrics;
        this.chaos = chaos;
        this.brake = new SafetyBrake(props.safetyBrakeThreshold());
        this.groupSize = props.reconcile().bucketGroup();
        this.leaseSeconds = props.reconcile().leaseSeconds();
    }

    // ---- Phase 1: stage --------------------------------------------------------------------

    public RunRow stageUpload(String tenantId, InputStream input, String actor) {
        UUID runId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        try {
            tx.call(() -> runs.create(runId, tenantId, batchId, actor, brake.threshold(), STAGING_LEASE_SECONDS));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("RECONCILE_IN_PROGRESS",
                    "Another export for this client is still being processed or is waiting for an operator decision.");
        }

        Timer.Sample sample = Timer.start();
        Set<String> knownOffices = offices.codes(tenantId);
        int[] stats = new int[2]; // rows, rejected
        List<BatchError> errors = new ArrayList<>();
        try {
            tx.run(() -> copyIntoStaging(runId, tenantId, input, knownOffices, stats, errors));
        } catch (RuntimeException e) {
            failRun(tenantId, runId, failureCode(e));
            throw translate(e, stats[0]);
        } finally {
            sample.stop(metrics.reconcilePhase("stage"));
        }

        runs.refreshStagingStats();
        RunRow run = tx.call(() -> {
            batches.insert(new Batch(batchId, tenantId, "NIGHTLY_FILE", actor, stats[0], 0, 0, 0, stats[1], null), errors);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("status", RunStatus.QUEUED);
            fields.put("file_rows", stats[0]);
            fields.put("rejected_rows", stats[1]);
            fields.put("lease_owner", null);
            fields.put("lease_expires_at", null);
            return runs.update(tenantId, runId, fields);
        });
        log.info("Nightly export staged {} {} {} {}", kv("tenantId", tenantId), kv("runId", runId), kv("rows", stats[0]), kv("rejected", stats[1]));
        return run;
    }

    /** Parsed record to validated, hashed CSV line for COPY, batched into ~256 KB chunks. */
    private void copyIntoStaging(UUID runId, String tenantId, InputStream input, Set<String> knownOffices, int[] stats, List<BatchError> errors) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        CopyIn copy = null;
        try (Reader reader = withoutBom(new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 64 * 1024));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true).setIgnoreHeaderCase(true).setTrim(true).setIgnoreEmptyLines(true)
                     .setAllowMissingColumnNames(true).setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL)
                     .build().parse(reader)) {
            List<String> missing = EventValidator.missingColumns(parser.getHeaderNames());
            if (!missing.isEmpty()) throw new FileHeaderException(missing);

            copy = connection.unwrap(PGConnection.class).getCopyAPI().copyIn(ReconcileRepository.COPY_STAGING_SQL);
            StringBuilder buffer = new StringBuilder(COPY_CHUNK_CHARS + 1024);
            for (CSVRecord record : parser) {
                int rowNo = ++stats[0];
                Map<String, String> row = new HashMap<>();
                for (String column : EventValidator.FILE_COLUMNS.keySet()) {
                    row.put(column, record.isMapped(column) && record.isSet(column) ? record.get(column) : null);
                }
                EventValidator.Result checked = EventValidator.validateFileRow(row, knownOffices);
                if (checked instanceof Invalid invalid) {
                    stats[1]++;
                    for (FieldIssue e : invalid.errors()) {
                        if (errors.size() < MAX_STORED_ERRORS) errors.add(new BatchError(rowNo, invalid.employeeId(), e.code(), e.field()));
                        metrics.rejection(e.code());
                    }
                    continue;
                }
                HrEvent event = ((Valid) checked).event();
                var d = event.data();
                appendCsv(buffer, runId.toString(), tenantId, event.employeeId(), d.fullName(), d.email(), d.phone(), d.managerId(),
                        d.officeCode(), d.shiftCode(), Long.toString(event.version()), RecordHasher.hrHash(event.employeeId(), d),
                        Integer.toString(RecordHasher.bucketOf(event.employeeId())), Integer.toString(rowNo));
                if (buffer.length() >= COPY_CHUNK_CHARS) flush(copy, buffer);
            }
            flush(copy, buffer);
            copy.endCopy();
        } catch (SQLException e) {
            cancel(copy);
            throw new IllegalStateException("COPY into staging failed", e);
        } catch (IOException e) {
            cancel(copy);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            cancel(copy);
            throw e;
        }
    }

    // ---- Phases 2 and 3 --------------------------------------------------------------------

    /** Drives a run as far as it can go. Safe to call again after a crash at any point. */
    public RunRow processRun(String tenantId, UUID runId, String owner) {
        RunRow run = get(tenantId, runId).orElseThrow(() -> new NotFoundException("Reconciliation run"));
        if (run.status() == RunStatus.QUEUED) run = update(tenantId, runId, Map.of("status", RunStatus.ANALYZING));
        if (run.status() == RunStatus.APPROVED) run = update(tenantId, runId, Map.of("status", RunStatus.APPLYING));
        if (run.status() == RunStatus.ANALYZING) run = analyze(run, owner);
        if (run.status() == RunStatus.APPLYING) run = apply(run, owner);
        return run;
    }

    private RunRow analyze(RunRow run, String owner) {
        Timer.Sample sample = Timer.start();
        String tenantId = run.tenantId();
        UUID runId = run.id();

        Base base = tx.call(() -> {
            List<Duplicate> duplicates = runs.removeDuplicates(runId);
            if (!duplicates.isEmpty()) {
                batches.addErrors(run.batchId(), tenantId, duplicates.stream().limit(MAX_STORED_ERRORS)
                        .map(d -> new BatchError(d.rowNo(), d.employeeId(), "DUPLICATE_IN_FILE", "employeeId")).toList());
            }
            return new Base(duplicates.size(), runs.countActive(tenantId), runs.countMissing(tenantId, runId), differingBuckets(tenantId, runId));
        });

        Map<Action, Integer> tally = new EnumMap<>(Action.class);
        for (Action a : Action.values()) tally.put(a, 0);
        for (List<Integer> group : chunk(base.differing(), groupSize)) {
            List<ChangedRow> rows = tx.call(() -> {
                runs.renewLease(tenantId, runId, owner, leaseSeconds);
                return runs.changedRows(tenantId, runId, group);
            });
            for (ChangedRow r : rows) tally.merge(decide(r), 1, Integer::sum);
        }

        int validRows = run.fileRows() - run.rejectedRows() - base.duplicates();
        int changed = tally.get(Action.CREATE) + tally.get(Action.UPDATE) + tally.get(Action.REACTIVATE) + tally.get(Action.STALE) + tally.get(Action.CONFLICT);
        SafetyBrake.Verdict verdict = brake.evaluate(base.activeBefore(), base.missing());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("duplicate_rows", base.duplicates());
        fields.put("active_before", base.activeBefore());
        fields.put("missing_count", base.missing());
        fields.put("missing_ratio", Math.round(verdict.missingRatio() * 10_000) / 10_000.0);
        fields.put("creates", tally.get(Action.CREATE));
        fields.put("updates", tally.get(Action.UPDATE));
        fields.put("reactivations", tally.get(Action.REACTIVATE));
        fields.put("deactivations", base.missing());
        fields.put("unchanged", Math.max(0, validRows - changed));
        fields.put("stale", tally.get(Action.STALE));
        fields.put("conflicts", tally.get(Action.CONFLICT));
        fields.put("buckets_skipped", Fields.BUCKETS - base.differing().size());
        fields.put("analyzed_at", OffsetDateTime.now());
        sample.stop(metrics.reconcilePhase("analyze"));

        if (verdict.tripped()) {
            fields.put("status", RunStatus.BLOCKED);
            fields.put("lease_owner", null);
            fields.put("lease_expires_at", null);
            RunRow blocked = tx.call(() -> {
                RunRow updated = runs.update(tenantId, runId, fields);
                outbox.add(DomainEvents.reconcileBlocked(tenantId, runId, base.activeBefore(), base.missing(), verdict));
                return updated;
            });
            metrics.safetyBrakeTrip();
            metrics.reconcileRun("blocked");
            log.warn("Safety brake tripped: export looks incomplete. Nothing applied; waiting for an operator. {} {} {} {} {} {}",
                    kv("tenantId", tenantId), kv("runId", runId), kv("activeCount", base.activeBefore()), kv("missingCount", base.missing()),
                    kv("missingRatio", verdict.missingRatio()), kv("threshold", verdict.threshold()));
            return blocked;
        }
        fields.put("status", RunStatus.APPLYING);
        return update(tenantId, runId, fields);
    }

    private RunRow apply(RunRow run, String owner) {
        Timer.Sample sample = Timer.start();
        String tenantId = run.tenantId();
        UUID runId = run.id();
        int applied = 0;
        int failed = 0;

        List<Integer> differing = tx.call(() -> differingBuckets(tenantId, runId));
        for (List<Integer> group : chunk(differing, groupSize)) {
            GroupResult result = Retry.withRetry(() -> tx.call(() -> applyGroup(tenantId, runId, group, owner)), 3,
                    () -> metrics.retry("reconcile_apply"));
            applied += result.applied();
            failed += result.failed();
            cache.evictEmployees(tenantId, result.touched());
            // Failure injection: die with some groups committed and the rest not yet applied.
            chaos.maybeCrash(Chaos.RECONCILE_MID_APPLY);
        }

        int appliedChanges = applied;
        int failedRows = failed;
        RunRow completed = tx.call(() -> {
            runs.clearStaging(runId);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("status", RunStatus.COMPLETED);
            fields.put("applied_changes", appliedChanges);
            fields.put("failure_code", failedRows > 0 ? "SOME_ROWS_FAILED" : null);
            fields.put("completed_at", OffsetDateTime.now());
            fields.put("lease_owner", null);
            fields.put("lease_expires_at", null);
            return runs.update(tenantId, runId, fields);
        });
        sample.stop(metrics.reconcilePhase("apply"));
        metrics.reconcileRun("completed");
        metrics.events("nightly", "applied", applied);
        log.info("Nightly reconciliation applied {} {} {} {}", kv("tenantId", tenantId), kv("runId", runId), kv("applied", applied), kv("failed", failed));
        return completed;
    }

    /** One bucket group in one transaction: set-based for common cases, savepoints for the rest. */
    private GroupResult applyGroup(String tenantId, UUID runId, List<Integer> group, String owner) {
        int created = runs.bulkInsertNew(tenantId, runId, group);
        List<String> touched = new ArrayList<>(runs.bulkApplyUpdates(tenantId, runId, group));
        touched.addAll(runs.bulkDeactivateMissing(tenantId, runId, group));
        int applied = created + touched.size();
        int failed = 0;

        // Whatever still differs is rare (rehires, creation races): per record, each in its own
        // savepoint. Stale and conflicting rows are counted in the plan, never written.
        for (ChangedRow row : runs.changedRows(tenantId, runId, group)) {
            if (!ReconcilePolicy.isApplicable(decide(row))) continue;
            try {
                Action action = tx.inSavepoint(() -> writer.applyFileRow(tenantId, row, "NIGHTLY_FILE"));
                if (ReconcilePolicy.isApplicable(action)) {
                    applied++;
                    touched.add(row.employeeId());
                }
            } catch (RuntimeException e) {
                if (Retry.isTransient(e)) throw e;
                failed++;
                log.error("Nightly row failed to apply {} {}", kv("employeeId", row.employeeId()), kv("error", e.getClass().getSimpleName()));
            }
        }
        runs.renewLease(tenantId, runId, owner, leaseSeconds);
        return new GroupResult(applied, failed, touched);
    }

    // ---- Operator decisions ----------------------------------------------------------------

    public RunRow decide(String tenantId, UUID runId, boolean approve, String actor, String note) {
        RunRow run = tx.call(() -> {
            RunRow current = runs.getForUpdate(tenantId, runId).orElseThrow(() -> new NotFoundException("Reconciliation run"));
            if (current.status() != RunStatus.BLOCKED) {
                throw new ConflictException("RUN_NOT_AWAITING_DECISION",
                        "This run is " + current.status().name().toLowerCase() + " and is not waiting for a decision.");
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("decided_by", actor);
            fields.put("decision_note", note);
            fields.put("decided_at", OffsetDateTime.now());
            if (approve) {
                fields.put("status", RunStatus.APPROVED);
            } else {
                runs.clearStaging(runId);
                fields.put("status", RunStatus.REJECTED);
                fields.put("completed_at", OffsetDateTime.now());
            }
            return runs.update(tenantId, runId, fields);
        });
        if (!approve) metrics.reconcileRun("rejected");
        log.info("Operator decided on blocked run {} {} {} {}", kv("tenantId", tenantId), kv("runId", runId),
                kv("decision", approve ? "approved" : "rejected"), kv("actor", actor));
        return run;
    }

    public List<RunRow> list(String tenantId, int limit) {
        return tx.call(() -> runs.list(tenantId, limit));
    }

    public Optional<RunRow> get(String tenantId, UUID runId) {
        return tx.call(() -> runs.get(tenantId, runId));
    }

    public void markFailed(String tenantId, UUID runId, String code) {
        failRun(tenantId, runId, code);
        metrics.reconcileRun("failed");
    }

    // ---- internals --------------------------------------------------------------------------

    private List<Integer> differingBuckets(String tenantId, UUID runId) {
        Map<Integer, String> store = new HashMap<>();
        Map<Integer, String> file = new HashMap<>();
        for (Digest d : runs.storeDigests(tenantId)) store.put(d.bucket(), d.digest());
        for (Digest d : runs.fileDigests(runId)) file.put(d.bucket(), d.digest());
        TreeSet<Integer> buckets = new TreeSet<>(store.keySet());
        buckets.addAll(file.keySet());
        return buckets.stream().filter(b -> !java.util.Objects.equals(store.get(b), file.get(b))).toList();
    }

    private static Action decide(ChangedRow row) {
        ReconcilePolicy.Stored stored = row.storedVersion() == null ? null
                : new ReconcilePolicy.Stored(row.storedVersion(), row.storedHash(), Status.valueOf(row.storedStatus()),
                        row.storedInactiveReason() == null ? null : InactiveReason.valueOf(row.storedInactiveReason()));
        return ReconcilePolicy.decide(new ReconcilePolicy.FileRow(row.version(), row.hrHash()), stored);
    }

    private RunRow update(String tenantId, UUID runId, Map<String, Object> fields) {
        return tx.call(() -> runs.update(tenantId, runId, fields));
    }

    private void failRun(String tenantId, UUID runId, String code) {
        try {
            tx.run(() -> {
                runs.clearStaging(runId);
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("status", RunStatus.FAILED);
                fields.put("failure_code", code);
                fields.put("lease_owner", null);
                fields.put("lease_expires_at", null);
                runs.update(tenantId, runId, fields);
            });
        } catch (RuntimeException e) {
            log.error("Could not mark run as failed {} {}", kv("runId", runId), kv("error", e.getClass().getSimpleName()));
        }
    }

    private static String failureCode(RuntimeException e) {
        if (e instanceof FileHeaderException) return "INVALID_FILE_HEADER";
        if (e instanceof AppException app) return app.code();
        if (e instanceof UncheckedIOException) return "UNREADABLE_FILE";
        return "UPLOAD_FAILED";
    }

    private static RuntimeException translate(RuntimeException e, int rows) {
        if (e instanceof FileHeaderException header) {
            return new ValidationException("INVALID_FILE_HEADER", "The file is missing required columns: " + String.join(", ", header.missing) + ".");
        }
        if (e instanceof AppException) return e;
        if (e instanceof UncheckedIOException) {
            return new ValidationException("UNREADABLE_FILE", "The file could not be read as CSV near row " + (rows + 1) + ".");
        }
        return e;
    }

    private static Reader withoutBom(BufferedReader reader) throws IOException {
        reader.mark(1);
        if (reader.read() != '﻿') reader.reset();
        return reader;
    }

    /** COPY csv: empty unquoted means NULL; anything with a delimiter, quote or newline is quoted. */
    private static void appendCsv(StringBuilder out, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) out.append(',');
            String value = fields[i];
            if (value == null) continue;
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                out.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                out.append(value);
            }
        }
        out.append('\n');
    }

    private static void flush(CopyIn copy, StringBuilder buffer) throws SQLException {
        if (buffer.isEmpty()) return;
        byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
        copy.writeToCopy(bytes, 0, bytes.length);
        buffer.setLength(0);
    }

    private static void cancel(CopyIn copy) {
        try {
            if (copy != null && copy.isActive()) copy.cancelCopy();
        } catch (SQLException ignored) {
            // The transaction rolls back anyway.
        }
    }

    private static <T> List<List<T>> chunk(List<T> items, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) out.add(items.subList(i, Math.min(items.size(), i + size)));
        return out;
    }
}
