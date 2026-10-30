package com.rostersync.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.rostersync.domain.AccessPolicy.Role;
import com.rostersync.domain.EventValidator;
import com.rostersync.domain.TransportPrefs;
import com.rostersync.persistence.DirectoryRepository.Office;
import com.rostersync.persistence.EmployeeRepository.ListFilter;
import com.rostersync.service.EmployeeQueryService;
import com.rostersync.service.EmployeeRecord;
import com.rostersync.service.OfficeDirectory;
import com.rostersync.service.RosterPrincipal;
import com.rostersync.service.TransportService;
import com.rostersync.support.AppException.NotFoundException;
import com.rostersync.support.AppException.ValidationException;
import com.rostersync.web.Views.BookingView;
import com.rostersync.web.Views.EmployeeView;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class EmployeesController {

    private static final Set<String> TRANSPORT_PATCH_FIELDS = Set.of("version", "pickupPoint", "specialAssistance");

    private final EmployeeQueryService employees;
    private final TransportService transport;
    private final OfficeDirectory offices;

    public EmployeesController(EmployeeQueryService employees, TransportService transport, OfficeDirectory offices) {
        this.employees = employees;
        this.transport = transport;
        this.offices = offices;
    }

    @GetMapping("/employees")
    @PreAuthorize("@access.allows('EMPLOYEES_READ')")
    public Map<String, Object> list(@RequestParam(required = false) String status, @RequestParam(required = false) String office,
                                    @RequestParam(required = false) String search, @RequestParam(required = false) String after,
                                    @RequestParam(required = false) String limit, @AuthenticationPrincipal RosterPrincipal principal) {
        int pageSize = parseLimit(limit);
        if (status != null && !status.equals("ACTIVE") && !status.equals("INACTIVE")) throw invalidQuery();
        if (office != null && office.length() > 16) throw invalidQuery();
        String term = search == null ? null : search.trim();
        if (term != null && term.length() > 100) throw invalidQuery();
        if (after != null && !EventValidator.EMPLOYEE_ID.matcher(after).matches()) throw invalidQuery();

        List<EmployeeRecord> records = employees.list(principal.tenantId(),
                new ListFilter(status, emptyToNull(office), emptyToNull(term), after, pageSize));
        Map<String, Object> page = new HashMap<>();
        page.put("employees", records.stream().map(r -> EmployeeView.of(r, principal.role())).toList());
        page.put("nextCursor", records.size() == pageSize ? records.get(records.size() - 1).employeeId() : null);
        return page;
    }

    @GetMapping("/employees/{employeeId}")
    @PreAuthorize("@access.allows('EMPLOYEES_READ')")
    public EmployeeView get(@PathVariable String employeeId, @AuthenticationPrincipal RosterPrincipal principal) {
        EmployeeRecord record = employees.get(principal.tenantId(), employeeId(employeeId));
        if (record == null) throw new NotFoundException("Employee");
        return EmployeeView.of(record, principal.role());
    }

    @GetMapping("/employees/{employeeId}/bookings")
    @PreAuthorize("@access.allows('EMPLOYEES_READ')")
    public Map<String, List<BookingView>> bookings(@PathVariable String employeeId, @AuthenticationPrincipal RosterPrincipal principal) {
        return Map.of("bookings", employees.upcomingBookings(principal.tenantId(), employeeId(employeeId)).stream().map(BookingView::of).toList());
    }

    /**
     * Partial update of transport-owned fields. Parsed by hand so that "field not sent" and
     * "field cleared (null)" stay different, and unknown fields are refused.
     */
    @PatchMapping("/employees/{employeeId}/transport")
    @PreAuthorize("@access.allows('TRANSPORT_WRITE')")
    public TransportService.Result updateTransport(@PathVariable String employeeId, @RequestBody(required = false) JsonNode body,
                                                   @AuthenticationPrincipal RosterPrincipal principal) {
        if (body == null || !body.isObject()) throw invalidTransport();
        for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
            if (!TRANSPORT_PATCH_FIELDS.contains(names.next())) throw invalidTransport();
        }
        JsonNode version = body.get("version");
        if (version == null || !version.canConvertToLong() || !version.isIntegralNumber() || version.asLong() < 0) throw invalidTransport();

        boolean hasPickup = body.has("pickupPoint");
        String pickup = hasPickup ? text(body.get("pickupPoint"), 3, 300) : null;
        boolean hasAssist = body.has("specialAssistance");
        String assist = hasAssist ? text(body.get("specialAssistance"), 0, 200) : null;
        if (assist != null && assist.isEmpty()) assist = null;

        return transport.update(principal.tenantId(), employeeId(employeeId), new TransportPrefs.Patch(hasPickup, pickup, hasAssist, assist),
                version.asLong());
    }

    @GetMapping("/offices")
    @PreAuthorize("@access.allows('EMPLOYEES_READ')")
    public Map<String, List<Office>> offices(@AuthenticationPrincipal RosterPrincipal principal) {
        return Map.of("offices", offices.list(principal.tenantId()));
    }

    @GetMapping("/me")
    @PreAuthorize("@access.allows('SELF_READ')")
    public Map<String, Object> me(@AuthenticationPrincipal RosterPrincipal principal) {
        if (principal.role() != Role.EMPLOYEE || principal.employeeId() == null) throw new NotFoundException("Employee profile");
        EmployeeRecord record = employees.get(principal.tenantId(), principal.employeeId());
        if (record == null) throw new NotFoundException("Employee profile");
        return Map.of(
                "employee", EmployeeView.of(record, principal.role()),
                "bookings", employees.upcomingBookings(principal.tenantId(), principal.employeeId()).stream().map(BookingView::of).toList());
    }

    private static String text(JsonNode node, int min, int max) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw invalidTransport();
        String value = node.asText().trim();
        if (value.length() < min || value.length() > max) throw invalidTransport();
        return value;
    }

    private static String employeeId(String value) {
        if (!EventValidator.EMPLOYEE_ID.matcher(value).matches()) throw new NotFoundException("Employee");
        return value;
    }

    private static int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) return 50;
        try {
            int value = Integer.parseInt(limit.trim());
            if (value < 1 || value > 200) throw invalidQuery();
            return value;
        } catch (NumberFormatException e) {
            throw invalidQuery();
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static ValidationException invalidQuery() {
        return new ValidationException("INVALID_QUERY", "Check the filters and try again.");
    }

    private static ValidationException invalidTransport() {
        return new ValidationException("INVALID_TRANSPORT_UPDATE",
                "Send the current version and the transport fields to change (pickupPoint, specialAssistance).");
    }
}
