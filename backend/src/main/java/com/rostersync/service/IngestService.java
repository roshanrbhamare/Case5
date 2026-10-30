package com.rostersync.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rostersync.config.RosterMetrics;
import com.rostersync.domain.EventValidator;
import com.rostersync.domain.EventValidator.FieldIssue;
import com.rostersync.domain.EventValidator.Invalid;
import com.rostersync.domain.EventValidator.Valid;
import com.rostersync.domain.HrEvent;
import com.rostersync.persistence.CacheEvictor;
import com.rostersync.persistence.IngestBatchRepository;
import com.rostersync.persistence.IngestBatchRepository.Batch;
import com.rostersync.persistence.IngestBatchRepository.BatchError;
import com.rostersync.persistence.Transactions;
import com.rostersync.service.EmployeeWriter.ApplyResult;
import com.rostersync.service.EmployeeWriter.Outcome;
import com.rostersync.support.Retry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Live HR feed. Each record is validated and applied in its own short transaction, so one
 * bad or failing record never affects the rest of the batch and row locks are held for
 * milliseconds. Records are applied in batch order.
 */
@Service
public class IngestService {

    public record Summary(int received, int applied, int stale, int duplicate, int rejected) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordResult(int index, @JsonInclude(JsonInclude.Include.ALWAYS) String employeeId, Outcome outcome,
                               List<String> changedFields, Boolean conflict, List<FieldIssue> warnings, List<FieldIssue> errors) {}

    public record IngestResult(UUID batchId, Summary summary, List<RecordResult> results) {}

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final Transactions tx;
    private final EmployeeWriter writer;
    private final IngestBatchRepository batches;
    private final OfficeDirectory offices;
    private final CacheEvictor cache;
    private final RosterMetrics metrics;

    public IngestService(Transactions tx, EmployeeWriter writer, IngestBatchRepository batches, OfficeDirectory offices,
                         CacheEvictor cache, RosterMetrics metrics) {
        this.tx = tx;
        this.writer = writer;
        this.batches = batches;
        this.offices = offices;
        this.cache = cache;
        this.metrics = metrics;
    }

    public IngestResult ingest(String tenantId, List<Object> rawEvents, String actor) {
        Set<String> knownOffices = offices.codes(tenantId);
        UUID batchId = UUID.randomUUID();
        int applied = 0, stale = 0, duplicate = 0, rejected = 0;
        List<RecordResult> results = new ArrayList<>();
        List<BatchError> errors = new ArrayList<>();
        Timer timer = metrics.ingestTimer();

        for (int index = 0; index < rawEvents.size(); index++) {
            EventValidator.Result checked = EventValidator.validateEvent(rawEvents.get(index), knownOffices);
            if (checked instanceof Invalid invalid) {
                rejected++;
                results.add(new RecordResult(index, invalid.employeeId(), Outcome.REJECTED, null, null, null, invalid.errors()));
                for (FieldIssue e : invalid.errors()) {
                    errors.add(new BatchError(index, invalid.employeeId(), e.code(), e.field()));
                    metrics.rejection(e.code());
                }
                metrics.event("api", "rejected");
                log.debug("Record rejected at the boundary {} {} {} {}", kv("tenantId", tenantId), kv("batchId", batchId), kv("index", index),
                
                        kv("employeeId", invalid.employeeId()), kv("codes", invalid.errors().stream().map(FieldIssue::code).toList()));
                continue;
            }

            Valid valid = (Valid) checked;
            HrEvent event = valid.event();
            Timer.Sample sample = Timer.start();
            try {
                ApplyResult result = Retry.withRetry(() -> tx.call(() -> writer.applyHrEvent(tenantId, event, "HR_EVENT")), 3,
                        () -> metrics.retry("ingest"));
                switch (result.outcome()) {
                    case APPLIED -> applied++;
                    case STALE -> stale++;
                    case DUPLICATE -> duplicate++;
                    default -> {}
                }
                metrics.event("api", result.outcome().name().toLowerCase());
                if (result.conflict()) metrics.versionConflict();
                if (result.outcome() == Outcome.APPLIED) cache.evictEmployee(tenantId, event.employeeId());
                results.add(new RecordResult(index, event.employeeId(), result.outcome(),
                        result.changedFields().isEmpty() ? null : result.changedFields(),
                        result.conflict() ? Boolean.TRUE : null,
                        valid.warnings().isEmpty() ? null : valid.warnings(), null));
            } catch (RuntimeException e) {
                rejected++;
                log.error("Failed to apply HR event {} {} {} {}", kv("tenantId", tenantId), kv("employeeId", event.employeeId()),
                        kv("batchId", batchId), kv("error", e.getClass().getSimpleName()));
                FieldIssue failure = new FieldIssue("PROCESSING_FAILED", null);
                results.add(new RecordResult(index, event.employeeId(), Outcome.REJECTED, null, null, null, List.of(failure)));
                errors.add(new BatchError(index, event.employeeId(), failure.code(), null));
                metrics.event("api", "failed");
            } finally {
                sample.stop(timer);
            }
        }

        Summary summary = new Summary(rawEvents.size(), applied, stale, duplicate, rejected);
        tx.run(() -> batches.insert(new Batch(batchId, tenantId, "API", actor, summary.received(), summary.applied(),
                summary.stale(), summary.duplicate(), summary.rejected(), null), errors));
        log.info("HR batch processed {} {} {} {} {} {} {}", kv("tenantId", tenantId), kv("batchId", batchId), kv("received", summary.received()),
                kv("applied", applied), kv("stale", stale), kv("duplicate", duplicate), kv("rejected", rejected));
        return new IngestResult(batchId, summary, results);
    }
}
