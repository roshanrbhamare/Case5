package com.rostersync.persistence;

import com.rostersync.domain.Employee;
import com.rostersync.domain.Fields.InactiveReason;
import com.rostersync.domain.Fields.Status;
import com.rostersync.domain.HrSnapshot;
import com.rostersync.domain.TransportPrefs;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Employees table. Never opens transactions itself: callers decide what commits together
 * (see Transactions), and JdbcTemplate joins the caller's transaction.
 */
@Repository
public class EmployeeRepository {

    /** One stored row, including bookkeeping columns the aggregate does not care about. */
    public record EmployeeRow(String tenantId, String employeeId, HrSnapshot hr, TransportPrefs transport, Status status,
                              InactiveReason inactiveReason, long hrVersion, long transportVersion, String hrHash,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        public Employee toEmployee() {
            return new Employee(tenantId, employeeId, hr, transport, status, inactiveReason, hrVersion, transportVersion);
        }
    }

    public record ListFilter(String status, String officeCode, String search, String after, int limit) {}

    private static final String COLUMNS = """
            tenant_id, employee_id, full_name, email, phone, manager_id, office_code, shift_code, pickup_point,
            special_assistance, status, inactive_reason, hr_version, transport_version, hr_hash, created_at, updated_at""";

    private static final RowMapper<EmployeeRow> MAPPER = (rs, i) -> new EmployeeRow(
            rs.getString("tenant_id"),
            rs.getString("employee_id"),
            new HrSnapshot(rs.getString("full_name"), rs.getString("email"), rs.getString("phone"), rs.getString("manager_id"),
                    rs.getString("office_code"), rs.getString("shift_code")),
            new TransportPrefs(rs.getString("pickup_point"), rs.getString("special_assistance")),
            Status.valueOf(rs.getString("status")),
            rs.getString("inactive_reason") == null ? null : InactiveReason.valueOf(rs.getString("inactive_reason")),
            rs.getLong("hr_version"),
            rs.getLong("transport_version"),
            rs.getString("hr_hash"),
            Sql.timestamp(rs, "created_at"),
            Sql.timestamp(rs, "updated_at"));

    private final JdbcTemplate jdbc;

    public EmployeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Row lock serialises all writers for one employee; different employees never wait on each other. */
    public Optional<EmployeeRow> findForUpdate(String tenantId, String employeeId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM employees WHERE tenant_id = ? AND employee_id = ? FOR UPDATE", MAPPER, tenantId, employeeId)
                .stream().findFirst();
    }

    public Optional<EmployeeRow> findById(String tenantId, String employeeId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM employees WHERE tenant_id = ? AND employee_id = ?", MAPPER, tenantId, employeeId)
                .stream().findFirst();
    }

    /** @return false when another transaction created the employee first */
    public boolean insertIfAbsent(Employee e) {
        return jdbc.update("""
                INSERT INTO employees (tenant_id, employee_id, full_name, email, phone, manager_id, office_code, shift_code,
                  pickup_point, special_assistance, status, inactive_reason, hr_version, transport_version, hr_hash, bucket)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, employee_id) DO NOTHING""",
                e.tenantId(), e.employeeId(), e.hr().fullName(), e.hr().email(), e.hr().phone(), e.hr().managerId(), e.hr().officeCode(),
                e.hr().shiftCode(), e.transport().pickupPoint(), e.transport().specialAssistance(), e.status().name(), name(e.inactiveReason()),
                e.hrVersion(), e.transportVersion(), e.hrHash(), e.bucket()) == 1;
    }

    /** Writes HR-owned columns and status only. Transport columns are not in this statement. */
    public void saveHr(Employee e) {
        jdbc.update("""
                UPDATE employees
                   SET full_name = ?, email = ?, phone = ?, manager_id = ?, office_code = ?, shift_code = ?,
                       status = ?, inactive_reason = ?, hr_version = ?, hr_hash = ?, updated_at = now()
                 WHERE tenant_id = ? AND employee_id = ?""",
                e.hr().fullName(), e.hr().email(), e.hr().phone(), e.hr().managerId(), e.hr().officeCode(), e.hr().shiftCode(),
                e.status().name(), name(e.inactiveReason()), e.hrVersion(), e.hrHash(), e.tenantId(), e.employeeId());
    }

    /** Writes transport-owned columns only. HR columns are not in this statement. */
    public void saveTransport(Employee e) {
        jdbc.update("""
                UPDATE employees SET pickup_point = ?, special_assistance = ?, transport_version = ?, updated_at = now()
                 WHERE tenant_id = ? AND employee_id = ?""",
                e.transport().pickupPoint(), e.transport().specialAssistance(), e.transportVersion(), e.tenantId(), e.employeeId());
    }

    /** Keyset pagination by employee id: stable and O(limit) at any depth. */
    public List<EmployeeRow> list(String tenantId, ListFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM employees WHERE tenant_id = ?");
        List<Object> params = new ArrayList<>(List.of(tenantId));
        if (filter.status() != null) {
            sql.append(" AND status = ?");
            params.add(filter.status());
        }
        if (filter.officeCode() != null) {
            sql.append(" AND office_code = ?");
            params.add(filter.officeCode());
        }
        if (filter.search() != null) {
            sql.append(" AND (employee_id ILIKE ? || '%' OR full_name ILIKE '%' || ? || '%')");
            params.add(filter.search());
            params.add(filter.search());
        }
        if (filter.after() != null) {
            sql.append(" AND employee_id > ?");
            params.add(filter.after());
        }
        sql.append(" ORDER BY employee_id LIMIT ?");
        params.add(filter.limit());
        return jdbc.query(sql.toString(), MAPPER, params.toArray());
    }

    public Map<String, Integer> countByStatus(String tenantId) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("SELECT status, count(*)::int AS n FROM employees WHERE tenant_id = ? GROUP BY status",
                rs -> {
                    counts.put(rs.getString("status"), rs.getInt("n"));
                }, tenantId);
        return counts;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
