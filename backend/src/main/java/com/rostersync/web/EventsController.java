package com.rostersync.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rostersync.service.IngestService;
import com.rostersync.service.IngestService.IngestResult;
import com.rostersync.service.RosterPrincipal;
import com.rostersync.support.AppException.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class EventsController {

    private static final int MAX_EVENTS = 5000;

    private final IngestService ingest;
    private final ObjectMapper json;

    public EventsController(IngestService ingest, ObjectMapper json) {
        this.ingest = ingest;
        this.json = json;
    }

    /**
     * Each event is passed on as a plain map and validated one by one in the domain, so one
     * malformed record yields one error entry instead of failing the whole request.
     */
    @PostMapping("/events")
    @PreAuthorize("@access.allows('EVENTS_INGEST')")
    public IngestResult ingest(@RequestBody(required = false) JsonNode body, @AuthenticationPrincipal RosterPrincipal principal) {
        JsonNode events = body == null ? null : body.get("events");
        if (events == null || !events.isArray() || events.isEmpty() || events.size() > MAX_EVENTS) {
            throw new ValidationException("INVALID_BATCH", "Send a JSON body like {\"events\": [...]} with 1 to 5000 events.",
                    Map.of("fields", List.of("events")));
        }
        List<Object> raw = new ArrayList<>(events.size());
        events.forEach(node -> raw.add(json.convertValue(node, Object.class)));
        return ingest.ingest(principal.tenantId(), raw, principal.actor());
    }
}
