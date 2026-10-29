package com.rostersync.domain;

import com.rostersync.domain.Fields.Owner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates at the boundary, one record at a time. Error entries carry a code and a field
 * name, never the offending value, so error reports are safe to store, show and log.
 *
 * <p>Works on plain maps (parsed JSON or CSV rows) so the domain has no dependency on any
 * web or JSON library.
 */
public final class EventValidator {

    public record FieldIssue(String code, String field) {}

    public sealed interface Result permits Valid, Invalid {}

    public record Valid(HrEvent event, List<FieldIssue> warnings) implements Result {}

    public record Invalid(String employeeId, List<FieldIssue> errors) implements Result {}

    public static final Pattern EMPLOYEE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[1-9]\\d{9,14}$");
    private static final Pattern OFFICE_CODE = Pattern.compile("^[A-Z0-9-]{2,16}$");
    private static final Pattern SHIFT_CODE = Pattern.compile("^[A-Z0-9_-]{1,16}$");
    private static final Pattern EMAIL = Pattern.compile("^(?!\\.)(?!.*\\.\\.)[A-Za-z0-9_'+\\-.]*[A-Za-z0-9_+-]@([A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}$");
    private static final Pattern DIGITS = Pattern.compile("^\\d{1,16}$");
    private static final long MAX_VERSION = 9_007_199_254_740_991L;

    /** Nightly export columns, mapped onto event fields. */
    public static final Map<String, String> FILE_COLUMNS = fileColumns();

    private static final Map<String, String> FIELD_CODES = Map.ofEntries(
            Map.entry("eventId", "EVENT_ID"), Map.entry("type", "EVENT_TYPE"), Map.entry("employeeId", "EMPLOYEE_ID"),
            Map.entry("version", "VERSION"), Map.entry("occurredAt", "OCCURRED_AT"), Map.entry("data", "DATA"),
            Map.entry("fullName", "FULL_NAME"), Map.entry("email", "EMAIL"), Map.entry("phone", "PHONE"),
            Map.entry("managerId", "MANAGER_ID"), Map.entry("officeCode", "OFFICE_CODE"), Map.entry("shiftCode", "SHIFT_CODE"));

    private EventValidator() {}

    @SuppressWarnings("unchecked")
    public static Result validateEvent(Object raw, Set<String> knownOffices) {
        if (!(raw instanceof Map<?, ?> map)) return new Invalid(null, List.of(new FieldIssue("INVALID_RECORD", null)));
        Map<String, Object> event = (Map<String, Object>) map;

        Object rawId = event.get("employeeId");
        String safeEmployeeId = rawId instanceof String s && EMPLOYEE_ID.matcher(s).matches() ? s : null;
        List<FieldIssue> errors = new ArrayList<>();
        List<FieldIssue> warnings = new ArrayList<>();

        // Envelope
        Object eventId = event.get("eventId");
        if (eventId != null && !(eventId instanceof String s && s.length() <= 128)) errors.add(invalid("eventId"));

        HrEvent.Type type = null;
        Object rawType = event.get("type");
        if (rawType == null) errors.add(missing("type"));
        else if (rawType instanceof String s) {
            try {
                type = HrEvent.Type.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add(invalid("type"));
            }
        } else errors.add(invalid("type"));

        if (rawId == null) errors.add(missing("employeeId"));
        else if (safeEmployeeId == null) errors.add(invalid("employeeId"));

        Long version = parseVersion(event.get("version"));
        if (event.get("version") == null) errors.add(missing("version"));
        else if (version == null) errors.add(invalid("version"));

        Object occurredAt = event.get("occurredAt");
        if (occurredAt != null && !(occurredAt instanceof String s && s.length() <= 40)) errors.add(invalid("occurredAt"));

        // Snapshot (only once the envelope says what kind of event this is)
        HrSnapshot snapshot = null;
        if (errors.isEmpty() && type != HrEvent.Type.DEACTIVATE) {
            if (!(event.get("data") instanceof Map<?, ?> data)) {
                errors.add(new FieldIssue("MISSING_DATA", "data"));
            } else {
                var partition = OwnershipPolicy.partition(Owner.HR, (Map<String, Object>) data);
                partition.notOwned().forEach(f -> warnings.add(new FieldIssue("FIELD_NOT_OWNED_BY_HR", f)));
                partition.unknown().forEach(f -> warnings.add(new FieldIssue("UNKNOWN_FIELD", f)));
                snapshot = validateSnapshot(partition.allowed(), errors);
                if (snapshot != null) {
                    if (!knownOffices.contains(snapshot.officeCode())) errors.add(new FieldIssue("UNKNOWN_OFFICE_CODE", "officeCode"));
                    if (snapshot.managerId() != null && snapshot.managerId().equals(safeEmployeeId)) errors.add(invalid("managerId"));
                }
            }
        }

        if (!errors.isEmpty()) return new Invalid(safeEmployeeId, dedupe(errors));
        return new Valid(new HrEvent((String) eventId, type, safeEmployeeId, version, snapshot), warnings);
    }

    /** A nightly-file row is a CREATE snapshot: the file lists everyone who is active. */
    public static Result validateFileRow(Map<String, String> row, Set<String> knownOffices) {
        Map<String, Object> data = new LinkedHashMap<>();
        FILE_COLUMNS.forEach((column, field) -> {
            if (!field.equals("employeeId") && !field.equals("version")) data.put(field, row.get(column));
        });
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "CREATE");
        event.put("employeeId", row.get("employee_id"));
        event.put("version", row.get("version"));
        event.put("data", data);
        return validateEvent(event, knownOffices);
    }

    /** Required columns the header lacks; empty when the header is usable. */
    public static List<String> missingColumns(List<String> header) {
        Set<String> present = new LinkedHashSet<>();
        header.forEach(h -> present.add(h.trim().toLowerCase(Locale.ROOT)));
        return FILE_COLUMNS.keySet().stream().filter(c -> !present.contains(c)).toList();
    }

    private static HrSnapshot validateSnapshot(Map<String, Object> input, List<FieldIssue> errors) {
        int before = errors.size();
        String fullName = text(input, "fullName", true, errors);
        String email = text(input, "email", true, errors);
        String phone = text(input, "phone", true, errors);
        String managerId = text(input, "managerId", false, errors);
        String officeCode = text(input, "officeCode", true, errors);
        String shiftCode = text(input, "shiftCode", false, errors);

        if (email != null) email = email.toLowerCase(Locale.ROOT);
        if (phone != null) phone = phone.replaceAll("[\\s()-]", "");
        if (officeCode != null) officeCode = officeCode.toUpperCase(Locale.ROOT);
        if (shiftCode != null) shiftCode = shiftCode.toUpperCase(Locale.ROOT);

        if (fullName != null && fullName.length() > 200) errors.add(invalid("fullName"));
        if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) errors.add(invalid("email"));
        if (phone != null && !PHONE.matcher(phone).matches()) errors.add(invalid("phone"));
        if (managerId != null && !EMPLOYEE_ID.matcher(managerId).matches()) errors.add(invalid("managerId"));
        if (officeCode != null && !OFFICE_CODE.matcher(officeCode).matches()) errors.add(invalid("officeCode"));
        if (shiftCode != null && !SHIFT_CODE.matcher(shiftCode).matches()) errors.add(invalid("shiftCode"));

        return errors.size() == before ? new HrSnapshot(fullName, email, phone, managerId, officeCode, shiftCode) : null;
    }

    /** Trimmed text; blank counts as absent. A non-text value is invalid. */
    private static String text(Map<String, Object> input, String field, boolean required, List<FieldIssue> errors) {
        Object value = input.get(field);
        if (value == null) {
            if (required) errors.add(missing(field));
            return null;
        }
        if (!(value instanceof String s)) {
            errors.add(invalid(field));
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            if (required) errors.add(missing(field));
            return null;
        }
        return trimmed;
    }

    private static Long parseVersion(Object value) {
        long v;
        if (value instanceof Integer i) v = i;
        else if (value instanceof Long l) v = l;
        else if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite()) v = d.longValue();
        else if (value instanceof java.math.BigInteger b && b.bitLength() < 63) v = b.longValue();
        else if (value instanceof String s && DIGITS.matcher(s.trim()).matches()) v = Long.parseLong(s.trim());
        else return null;
        return v > 0 && v <= MAX_VERSION ? v : null;
    }

    private static FieldIssue missing(String field) {
        return new FieldIssue("MISSING_" + FIELD_CODES.get(field), field);
    }

    private static FieldIssue invalid(String field) {
        return new FieldIssue("INVALID_" + FIELD_CODES.get(field), field);
    }

    private static List<FieldIssue> dedupe(List<FieldIssue> errors) {
        return List.copyOf(new LinkedHashSet<>(errors));
    }

    private static Map<String, String> fileColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("employee_id", "employeeId");
        columns.put("full_name", "fullName");
        columns.put("email", "email");
        columns.put("phone", "phone");
        columns.put("manager_id", "managerId");
        columns.put("office_code", "officeCode");
        columns.put("shift_code", "shiftCode");
        columns.put("version", "version");
        return java.util.Collections.unmodifiableMap(columns);
    }
}
