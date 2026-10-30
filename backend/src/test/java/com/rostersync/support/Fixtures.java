package com.rostersync.support;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builders for HR events and nightly-export CSVs. */
public final class Fixtures {

    public static final String HEADER = "employee_id,full_name,email,phone,manager_id,office_code,shift_code,version";

    private Fixtures() {}

    /** A valid HR snapshot; pass key/value pairs to override fields. */
    public static Map<String, Object> snapshot(Object... overrides) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fullName", "Asha Rao");
        data.put("email", "asha.rao@acme.example");
        data.put("phone", "+919800000001");
        data.put("managerId", null);
        data.put("officeCode", "BLR-WF");
        data.put("shiftCode", "S0900");
        for (int i = 0; i < overrides.length; i += 2) data.put((String) overrides[i], overrides[i + 1]);
        return data;
    }

    public static Map<String, Object> event(String type, String employeeId, long version, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("employeeId", employeeId);
        event.put("version", version);
        if (data != null) event.put("data", data);
        return event;
    }

    public static Map<String, Object> event(String type, String employeeId, long version) {
        return event(type, employeeId, version, null);
    }

    /** One export row, from a stored employee row or built by hand. */
    public record Row(String employeeId, String fullName, String email, String phone, String managerId, String officeCode, String shiftCode,
                      long version) {

        public Row withShift(String shift, long newVersion) {
            return new Row(employeeId, fullName, email, phone, managerId, officeCode, shift, newVersion);
        }

        public Row withOffice(String office, String shift, long newVersion) {
            return new Row(employeeId, fullName, email, phone, managerId, office, shift, newVersion);
        }

        public Row withPhone(String newPhone) {
            return new Row(employeeId, fullName, email, newPhone, managerId, officeCode, shiftCode, version);
        }

        public Row withVersion(long newVersion) {
            return new Row(employeeId, fullName, email, phone, managerId, officeCode, shiftCode, newVersion);
        }
    }

    /** What a correct export of the current store would look like. */
    public static List<Row> currentExport(String tenantId) {
        List<Row> rows = new ArrayList<>();
        for (Map<String, Object> r : TestDb.query("""
                SELECT employee_id, full_name, email, phone, manager_id, office_code, shift_code, hr_version
                  FROM employees WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY employee_id""", tenantId)) {
            rows.add(new Row((String) r.get("employee_id"), (String) r.get("full_name"), (String) r.get("email"), (String) r.get("phone"),
                    (String) r.get("manager_id"), (String) r.get("office_code"), (String) r.get("shift_code"), ((Number) r.get("hr_version")).longValue()));
        }
        return rows;
    }

    public static String toCsv(List<Row> rows) {
        return HEADER + "\n" + rows.stream()
                .map(r -> String.join(",", esc(r.employeeId()), esc(r.fullName()), esc(r.email()), esc(r.phone()), esc(r.managerId()),
                        esc(r.officeCode()), esc(r.shiftCode()), Long.toString(r.version())))
                .collect(Collectors.joining("\n")) + "\n";
    }

    public static InputStream streamOf(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.contains(",") || value.contains("\"") || value.contains("\n") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
