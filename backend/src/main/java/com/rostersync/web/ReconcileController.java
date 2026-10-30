package com.rostersync.web;

import com.rostersync.config.RosterSyncProperties;
import com.rostersync.persistence.ReconcileRepository.RunRow;
import com.rostersync.service.ReconcileService;
import com.rostersync.service.RosterPrincipal;
import com.rostersync.support.AppException.NotFoundException;
import com.rostersync.support.AppException.PayloadTooLargeException;
import com.rostersync.support.AppException.UnsupportedMediaTypeException;
import com.rostersync.support.AppException.ValidationException;
import com.rostersync.web.Views.RunView;
import jakarta.servlet.http.HttpServletRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class ReconcileController {

    public record DecisionRequest(String note) {}

    private static final Set<String> CSV_TYPES = Set.of("text/csv", "application/octet-stream", "application/vnd.ms-excel");
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ReconcileService reconcile;
    private final long maxBytes;

    public ReconcileController(ReconcileService reconcile, RosterSyncProperties props) {
        this.reconcile = reconcile;
        this.maxBytes = props.reconcile().maxUploadMb() * 1024L * 1024L;
    }

    /** The body is read as a raw stream (no multipart, no buffering): rows flow straight to COPY. */
    @PostMapping("/reconcile/runs")
    @PreAuthorize("@access.allows('RECONCILE_UPLOAD')")
    public ResponseEntity<RunView> upload(HttpServletRequest request, @AuthenticationPrincipal RosterPrincipal principal) throws IOException {
        String type = request.getContentType() == null ? "" : request.getContentType().split(";")[0].trim().toLowerCase();
        if (!CSV_TYPES.contains(type)) throw new UnsupportedMediaTypeException("Upload the export as a CSV file (Content-Type: text/csv).");
        RunRow run = reconcile.stageUpload(principal.tenantId(), new LimitedInputStream(request.getInputStream(), maxBytes), principal.actor());
        return ResponseEntity.accepted().location(URI.create("/v1/reconcile/runs/" + run.id())).body(RunView.of(run));
    }

    @GetMapping("/reconcile/runs")
    @PreAuthorize("@access.allows('RECONCILE_READ')")
    public Map<String, List<RunView>> list(@AuthenticationPrincipal RosterPrincipal principal) {
        return Map.of("runs", reconcile.list(principal.tenantId(), 30).stream().map(RunView::of).toList());
    }

    @GetMapping("/reconcile/runs/{runId}")
    @PreAuthorize("@access.allows('RECONCILE_READ')")
    public RunView get(@PathVariable String runId, @AuthenticationPrincipal RosterPrincipal principal) {
        return RunView.of(reconcile.get(principal.tenantId(), runId(runId)).orElseThrow(() -> new NotFoundException("Reconciliation run")));
    }

    @PostMapping("/reconcile/runs/{runId}/approve")
    @PreAuthorize("@access.allows('RECONCILE_DECIDE')")
    public RunView approve(@PathVariable String runId, @RequestBody(required = false) DecisionRequest body, @AuthenticationPrincipal RosterPrincipal principal) {
        return RunView.of(reconcile.decide(principal.tenantId(), runId(runId), true, principal.actor(), note(body)));
    }

    @PostMapping("/reconcile/runs/{runId}/reject")
    @PreAuthorize("@access.allows('RECONCILE_DECIDE')")
    public RunView reject(@PathVariable String runId, @RequestBody(required = false) DecisionRequest body, @AuthenticationPrincipal RosterPrincipal principal) {
        return RunView.of(reconcile.decide(principal.tenantId(), runId(runId), false, principal.actor(), note(body)));
    }

    private static String note(DecisionRequest body) {
        String note = body == null || body.note() == null ? "" : body.note().trim();
        if (note.length() < 3 || note.length() > 500) {
            throw new ValidationException("DECISION_NOTE_REQUIRED", "Add a short note explaining the decision (3 to 500 characters).",
                    Map.of("fields", List.of("note")));
        }
        return note;
    }

    private static UUID runId(String value) {
        if (!UUID_PATTERN.matcher(value).matches()) throw new NotFoundException("Reconciliation run");
        return UUID.fromString(value);
    }

    /** Caps a streamed upload without buffering it. */
    static final class LimitedInputStream extends FilterInputStream {
        private final long max;
        private long seen;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) count(n);
            return n;
        }

        private void count(long n) {
            seen += n;
            if (seen > max) throw new PayloadTooLargeException("The file is larger than " + (max / 1024 / 1024) + " MB.");
        }
    }
}
