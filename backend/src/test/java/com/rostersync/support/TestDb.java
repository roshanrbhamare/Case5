package com.rostersync.support;

import com.rostersync.bootstrap.SeedData;
import com.rostersync.domain.Employee;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Owner-role access to the test database for resets and assertions that must see every tenant. */
public final class TestDb {

    public static final String URL = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:55432/rostersync_test");

    private static final JdbcTemplate ADMIN = new JdbcTemplate(new SingleConnectionDataSource(URL, "rostersync", "rostersync", true));
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final String TABLES = "ingest_errors, ingest_batches, reconcile_staging, reconcile_runs, bookings, trips, "
            + "outbox, dead_letter, consumed_events, sign_in_codes, employees, users, offices, tenants";

    static {
        // Same session zone as the app (see application.yml), so seeded ride dates agree with it.
        ADMIN.execute("SET TIME ZONE 'Asia/Kolkata'");
    }

    private TestDb() {}

    /** Fresh database with tenants, offices and users; optionally employees with three days of bookings. */
    public static synchronized void reset(Map<String, Integer> employees) {
        ADMIN.execute("TRUNCATE " + TABLES + " RESTART IDENTITY CASCADE");
        SeedData.seedReferenceData(ADMIN, ENCODER);
        employees.forEach((tenantId, count) -> {
            List<Employee> list = IntStream.rangeClosed(1, count).mapToObj(i -> SeedData.demoEmployee(tenantId, i)).toList();
            list.forEach(e -> SeedData.insertEmployee(ADMIN, e));
            SeedData.seedTripsAndBookings(ADMIN, tenantId, list, 3);
        });
    }

    public static void reset() {
        reset(Map.of());
    }

    public static synchronized List<Map<String, Object>> query(String sql, Object... args) {
        return ADMIN.queryForList(sql, args);
    }

    public static synchronized Map<String, Object> one(String sql, Object... args) {
        return ADMIN.queryForMap(sql, args);
    }

    public static synchronized int count(String sql, Object... args) {
        Integer n = ADMIN.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    public static synchronized void exec(String sql, Object... args) {
        ADMIN.update(sql, args);
    }

    public static Map<String, Object> employee(String tenantId, String employeeId) {
        return one("SELECT * FROM employees WHERE tenant_id = ? AND employee_id = ?", tenantId, employeeId);
    }
}
