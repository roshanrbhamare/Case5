package com.rostersync.bootstrap;

import com.rostersync.domain.Employee;
import com.rostersync.domain.Fields.Status;
import com.rostersync.domain.HrSnapshot;
import com.rostersync.domain.TransportPrefs;
import com.rostersync.service.ApiKeyResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Demo and test fixtures. All credentials here are development-only test values. */
public final class SeedData {

    public static final String DEMO_PASSWORD = "Demo@1234";

    public record TenantSeed(String id, String name, String apiKey) {}

    public record OfficeSeed(String tenantId, String code, String name, String city) {}

    public record UserSeed(String tenantId, String email, String displayName, String role, String employeeId) {}

    public static final List<TenantSeed> TENANTS = List.of(
            new TenantSeed("acme", "Acme Technologies", "acme-hr-dev-key"),
            new TenantSeed("globex", "Globex Systems", "globex-hr-dev-key"));

    public static final List<OfficeSeed> OFFICES = List.of(
            new OfficeSeed("acme", "BLR-WF", "Whitefield Tech Park", "Bengaluru"),
            new OfficeSeed("acme", "BLR-ORR", "Outer Ring Road Campus", "Bengaluru"),
            new OfficeSeed("acme", "PNQ-HJ", "Hinjewadi Phase 2", "Pune"),
            new OfficeSeed("acme", "HYD-HT", "HITEC City Tower B", "Hyderabad"),
            new OfficeSeed("globex", "GGN-CC", "Cyber City Block 9", "Gurugram"),
            new OfficeSeed("globex", "NOI-62", "Sector 62 Campus", "Noida"));

    public static final List<UserSeed> USERS = List.of(
            new UserSeed("acme", "operator@acme.test", "Meera Iyer", "OPERATOR", null),
            new UserSeed("acme", "admin@acme.test", "Rahul Menon", "ADMIN", null),
            new UserSeed("acme", "employee@acme.test", "Ananya Sharma", "EMPLOYEE", "E1001"),
            new UserSeed("globex", "operator@globex.test", "Vikram Singh", "OPERATOR", null),
            new UserSeed("acme", "employee2@acme.test", "Priya Mehta", "EMPLOYEE", "E1002"),
            new UserSeed("globex", "admin@globex.test", "Priya Nair", "ADMIN", null),
            new UserSeed("globex", "employee@globex.test", "Arjun Patel", "EMPLOYEE", "G1003"),
            new UserSeed("globex", "employee2@globex.test", "Kavya Bose", "EMPLOYEE", "G1004"));

    private static final String[] FIRST = {"Ananya", "Rohan", "Priya", "Arjun", "Kavya", "Vikram", "Isha", "Aditya", "Neha", "Karan", "Sneha",
            "Rahul", "Divya", "Siddharth", "Pooja", "Manish", "Aisha", "Varun", "Lakshmi", "Nikhil"};
    private static final String[] LAST = {"Sharma", "Iyer", "Reddy", "Nair", "Gupta", "Menon", "Patel", "Rao", "Kulkarni", "Singh", "Das", "Joshi",
            "Pillai", "Bose", "Mehta"};
    private static final String[] SHIFTS = {"S0800", "S0900", "S1000", "S1400", "NIGHT"};
    private static final String[] LANDMARKS = {"Main Gate", "Gate 2", "Clubhouse", "Bus Stop", "Tower A Lobby", "Metro Exit 3"};
    private static final String[] AREAS = {"Indiranagar", "HSR Layout", "Koramangala", "Baner", "Kondapur", "Marathahalli", "Wakad", "Gachibowli",
            "DLF Phase 3", "Sector 50"};

    private SeedData() {}

    public static Employee demoEmployee(String tenantId, int index) {
        String prefix = tenantId.equals("acme") ? "E" : "G";
        String first = FIRST[index % FIRST.length];
        String last = LAST[(index * 7) % LAST.length];
        List<OfficeSeed> offices = OFFICES.stream().filter(o -> o.tenantId().equals(tenantId)).toList();
        String phoneDigits = String.valueOf(10_000_000 + index * 7919L);
        HrSnapshot hr = new HrSnapshot(
                first + " " + last,
                (first + "." + last + index + "@" + tenantId + ".example").toLowerCase(),
                "+9198" + phoneDigits.substring(phoneDigits.length() - 8),
                index > 5 ? prefix + (1000 + (index % 5) + 1) : null,
                offices.get(index % offices.size()).code(),
                SHIFTS[index % SHIFTS.length]);
        TransportPrefs transport = new TransportPrefs(LANDMARKS[index % LANDMARKS.length] + ", " + AREAS[index % AREAS.length],
                index % 17 == 0 ? "Wheelchair accessible cab" : null);
        return new Employee(tenantId, prefix + (1000 + index), hr, transport, Status.ACTIVE, null, 1, index % 3);
    }

    /** Tenants, offices and users. Idempotent. Runs as the owner role. */
    public static void seedReferenceData(JdbcTemplate admin, PasswordEncoder passwords) {
        String hash = passwords.encode(DEMO_PASSWORD);
        for (TenantSeed t : TENANTS) {
            admin.update("INSERT INTO tenants (id, name, hr_api_key_hash) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING",
                    t.id(), t.name(), ApiKeyResolver.hash(t.apiKey()));
        }
        for (OfficeSeed o : OFFICES) {
            admin.update("INSERT INTO offices (tenant_id, code, name, city) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", o.tenantId(), o.code(), o.name(), o.city());
        }
        for (UserSeed u : USERS) {
            admin.update("""
                    INSERT INTO users (id, tenant_id, email, display_name, password_hash, role, employee_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (email) DO NOTHING""",
                    UUID.randomUUID(), u.tenantId(), u.email(), u.displayName(), hash, u.role(), u.employeeId());
        }
    }

    public static void insertEmployee(JdbcTemplate admin, Employee e) {
        admin.update("""
                INSERT INTO employees (tenant_id, employee_id, full_name, email, phone, manager_id, office_code, shift_code, pickup_point,
                  special_assistance, status, inactive_reason, hr_version, transport_version, hr_hash, bucket)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                e.tenantId(), e.employeeId(), e.hr().fullName(), e.hr().email(), e.hr().phone(), e.hr().managerId(), e.hr().officeCode(),
                e.hr().shiftCode(), e.transport().pickupPoint(), e.transport().specialAssistance(), e.status().name(), null, e.hrVersion(),
                e.transportVersion(), e.hrHash(), e.bucket());
    }

    /** One pickup trip per office and shift per day, and a booking per employee per day. */
    public static void seedTripsAndBookings(JdbcTemplate admin, String tenantId, List<Employee> employees, int days) {
        Map<String, UUID> trips = new HashMap<>();
        for (int d = 0; d < days; d++) {
            for (Employee e : employees) {
                String key = d + ":" + e.hr().officeCode() + ":" + e.hr().shiftCode();
                UUID tripId = trips.get(key);
                if (tripId == null) {
                    tripId = UUID.randomUUID();
                    admin.update("""
                            INSERT INTO trips (id, tenant_id, office_code, ride_date, shift_code, direction, capacity, seats_booked)
                            VALUES (?, ?, ?, current_date + ?, ?, 'PICKUP', 40, 0)""",
                            tripId, tenantId, e.hr().officeCode(), d, e.hr().shiftCode());
                    trips.put(key, tripId);
                }
                admin.update("INSERT INTO bookings (id, tenant_id, employee_id, trip_id, ride_date, status) VALUES (?, ?, ?, ?, current_date + ?, 'BOOKED')",
                        UUID.randomUUID(), tenantId, e.employeeId(), tripId, d);
                admin.update("UPDATE trips SET seats_booked = seats_booked + 1 WHERE id = ?", tripId);
            }
        }
    }
}
