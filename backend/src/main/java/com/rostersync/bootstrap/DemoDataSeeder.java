package com.rostersync.bootstrap;

import com.rostersync.domain.Employee;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills an empty database with demo tenants, users, employees and bookings at startup
 * (rostersync.seed-demo-data=true, set in docker-compose). Runs after Flyway; a tenant that
 * already has employees is left alone, so restarts are safe.
 */
@Component
@ConditionalOnProperty(name = "rostersync.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final Map<String, Integer> PER_TENANT = Map.of("acme", 60, "globex", 30);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwords;

    public DemoDataSeeder(JdbcTemplate jdbc, TransactionTemplate tx, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.passwords = passwords;
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(status -> {
            SeedData.seedReferenceData(jdbc, passwords);
            PER_TENANT.forEach((tenantId, count) -> {
                Integer existing = jdbc.queryForObject("SELECT count(*)::int FROM employees WHERE tenant_id = ?", Integer.class, tenantId);
                if (existing != null && existing > 0) return;
                List<Employee> employees = IntStream.rangeClosed(1, count).mapToObj(i -> SeedData.demoEmployee(tenantId, i)).toList();
                employees.forEach(e -> SeedData.insertEmployee(jdbc, e));
                SeedData.seedTripsAndBookings(jdbc, tenantId, employees, 5);
                log.info("Seeded {} demo employees with bookings for {}", count, tenantId);
            });
        });
    }
}
