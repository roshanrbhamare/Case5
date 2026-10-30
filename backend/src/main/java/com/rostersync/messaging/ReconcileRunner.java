package com.rostersync.messaging;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.rostersync.config.RosterSyncProperties;
import com.rostersync.persistence.ReconcileRepository;
import com.rostersync.persistence.ReconcileRepository.Claimed;
import com.rostersync.service.ReconcileService;
import com.rostersync.support.Retry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Background driver for nightly runs. Claims work with a lease (one UPDATE over a
 * FOR UPDATE SKIP LOCKED subquery). If a worker dies mid-run its lease expires and another worker
 * resumes the run from its current phase; every phase is idempotent, so that is safe.
 */
@Component
public class ReconcileRunner {

    private static final Logger log = LoggerFactory.getLogger(ReconcileRunner.class);

    private final ReconcileRepository runs;
    private final ReconcileService reconcile;
    private final int leaseSeconds;

    public ReconcileRunner(ReconcileRepository runs, ReconcileService reconcile, RosterSyncProperties props) {
        this.runs = runs;
        this.reconcile = reconcile;
        this.leaseSeconds = props.reconcile().leaseSeconds();
    }

    /** @return whether a run was processed */
    public boolean tick(String owner) {
        int abandoned = runs.failAbandonedUploads();
        if (abandoned > 0) log.warn("Marked abandoned uploads as failed {}", kv("count", abandoned));

        Optional<Claimed> claimed = runs.claim(owner, leaseSeconds);
        if (claimed.isEmpty()) return false;
        Claimed run = claimed.get();
        log.info("Claimed nightly run {} {} {}", kv("tenantId", run.tenantId()), kv("runId", run.id()), kv("status", run.status()));
        try {
            reconcile.processRun(run.tenantId(), run.id(), owner);
        } catch (RuntimeException e) {
            if (Retry.isTransient(e)) {
                log.warn("Nightly run interrupted; it resumes when the lease expires {}", kv("runId", run.id()));
            } else {
                log.error("Nightly run failed {} {}", kv("runId", run.id()), kv("error", e.getClass().getSimpleName()));
                reconcile.markFailed(run.tenantId(), run.id(), "PROCESSING_ERROR");
            }
        }
        return true;
    }
}
