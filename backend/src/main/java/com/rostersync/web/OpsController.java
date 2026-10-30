package com.rostersync.web;

import com.rostersync.persistence.IngestBatchRepository;
import com.rostersync.persistence.IngestBatchRepository.Batch;
import com.rostersync.persistence.ReconcileRepository.RunStatus;
import com.rostersync.persistence.Transactions;
import com.rostersync.service.OpsService;
import com.rostersync.service.RosterPrincipal;
import com.rostersync.support.AppException.NotFoundException;
import com.rostersync.web.Views.BatchView;
import com.rostersync.web.Views.RunView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operations overview and the sync-activity error reports. */
@RestController
@RequestMapping("/v1")
public class OpsController {

    public record ErrorView(int record, String employeeId, String code, String field) {}

    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final OpsService ops;
    private final Transactions tx;
    private final IngestBatchRepository batches;

    public OpsController(OpsService ops, Transactions tx, IngestBatchRepository batches) {
        this.ops = ops;
        this.tx = tx;
        this.batches = batches;
    }

    @GetMapping("/ops/summary")
    @PreAuthorize("@access.allows('OPS_READ')")
    public Map<String, Object> summary(@AuthenticationPrincipal RosterPrincipal principal) {
        OpsService.Summary s = ops.summary(principal.tenantId());
        Map<String, Object> last24h = new LinkedHashMap<>();
        last24h.put("received", s.totals().received());
        last24h.put("applied", s.totals().applied() + s.nightlyApplied());
        last24h.put("liveApplied", s.totals().applied());
        last24h.put("nightlyApplied", s.nightlyApplied());
        last24h.put("stale", s.totals().stale());
        last24h.put("duplicate", s.totals().duplicate());
        last24h.put("rejected", s.totals().rejected());
        last24h.put("files", s.totals().files());

        Map<String, Object> outbox = new LinkedHashMap<>();
        outbox.put("pending", s.outbox().pending());
        outbox.put("dead", s.outbox().dead());
        outbox.put("oldestPendingSeconds", Math.round(s.outbox().oldestPendingSeconds()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employees", Map.of("active", s.counts().getOrDefault("ACTIVE", 0), "inactive", s.counts().getOrDefault("INACTIVE", 0)));
        body.put("last24h", last24h);
        body.put("hourly", s.hourly());
        body.put("topErrors", s.topErrors());
        body.put("outbox", outbox);
        body.put("consumer", s.consumer());
        body.put("recentRuns", s.runs().stream().map(RunView::of).toList());
        body.put("blockedRun", s.runs().stream().filter(r -> r.status() == RunStatus.BLOCKED).findFirst().map(RunView::of).orElse(null));
        return body;
    }

    @GetMapping("/batches")
    @PreAuthorize("@access.allows('BATCHES_READ')")
    public Map<String, List<BatchView>> list(@AuthenticationPrincipal RosterPrincipal principal) {
        String tenantId = principal.tenantId();
        return Map.of("batches", tx.call(() -> batches.list(tenantId, 30)).stream().map(BatchView::of).toList());
    }

    @GetMapping("/batches/{batchId}/errors")
    @PreAuthorize("@access.allows('BATCHES_READ')")
    public Map<String, Object> errors(@PathVariable String batchId, @AuthenticationPrincipal RosterPrincipal principal) {
        if (!UUID_PATTERN.matcher(batchId).matches()) throw new NotFoundException("Batch");
        String tenantId = principal.tenantId();
        UUID id = UUID.fromString(batchId);
        Batch batch = tx.call(() -> batches.get(tenantId, id)).orElseThrow(() -> new NotFoundException("Batch"));
        List<ErrorView> errors = tx.call(() -> batches.errors(tenantId, id, 500)).stream()
                .map(e -> new ErrorView(e.index(), e.employeeId(), e.code(), e.field())).toList();
        return Map.of("batch", BatchView.of(batch), "errors", errors);
    }
}
