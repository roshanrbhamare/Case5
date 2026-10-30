package com.rostersync.service;

import com.rostersync.messaging.StreamStats;
import com.rostersync.persistence.EmployeeRepository;
import com.rostersync.persistence.IngestBatchRepository;
import com.rostersync.persistence.IngestBatchRepository.CodeCount;
import com.rostersync.persistence.IngestBatchRepository.Hourly;
import com.rostersync.persistence.IngestBatchRepository.Totals;
import com.rostersync.persistence.OutboxRepository;
import com.rostersync.persistence.OutboxRepository.OutboxStats;
import com.rostersync.persistence.ReconcileRepository;
import com.rostersync.persistence.ReconcileRepository.RunRow;
import com.rostersync.persistence.Transactions;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Everything the operations overview needs, in one call, for one tenant. */
@Service
public class OpsService {

    public record Summary(Map<String, Integer> counts, Totals totals, int nightlyApplied, List<Hourly> hourly, List<CodeCount> topErrors,
                          List<RunRow> runs, OutboxStats outbox, StreamStats.Snapshot consumer) {}

    private final Transactions tx;
    private final EmployeeRepository employees;
    private final IngestBatchRepository batches;
    private final ReconcileRepository runs;
    private final OutboxRepository outbox;
    private final StreamStats streamStats;

    public OpsService(Transactions tx, EmployeeRepository employees, IngestBatchRepository batches, ReconcileRepository runs,
                      OutboxRepository outbox, StreamStats streamStats) {
        this.tx = tx;
        this.employees = employees;
        this.batches = batches;
        this.runs = runs;
        this.outbox = outbox;
        this.streamStats = streamStats;
    }

    public Summary summary(String tenantId) {
        Summary tenantData = tx.call(() -> new Summary(
                employees.countByStatus(tenantId),
                batches.totalsSince(tenantId, 24),
                runs.appliedSince(tenantId, 24),
                batches.hourlySince(tenantId, 24),
                batches.topErrorCodes(tenantId, 24, 5),
                runs.list(tenantId, 5),
                null,
                null));
        return new Summary(tenantData.counts(), tenantData.totals(), tenantData.nightlyApplied(), tenantData.hourly(), tenantData.topErrors(),
                tenantData.runs(), outbox.stats(tenantId), streamStats.snapshot());
    }
}
