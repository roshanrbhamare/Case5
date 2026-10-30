package com.rostersync.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reference data: tenants, users, offices, and consumer bookkeeping. Lookups by email or
 * API key hash are global on purpose: login has to find the tenant from the credential.
 */
@Repository
public class DirectoryRepository {

    public record Tenant(String id, String name) {}

    public record User(UUID id, String tenantId, String email, String displayName, String passwordHash, String role, String employeeId) {}

    public record Office(String code, String name, String city) {}

    private final JdbcTemplate jdbc;

    public DirectoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tenant> tenantByApiKeyHash(String hash) {
        return jdbc.query("SELECT id, name FROM tenants WHERE hr_api_key_hash = ?", (rs, i) -> new Tenant(rs.getString("id"), rs.getString("name")), hash)
                .stream().findFirst();
    }

    public Optional<Tenant> tenant(String id) {
        return jdbc.query("SELECT id, name FROM tenants WHERE id = ?", (rs, i) -> new Tenant(rs.getString("id"), rs.getString("name")), id)
                .stream().findFirst();
    }

    public Optional<User> userByEmail(String email) {
        return jdbc.query("SELECT id, tenant_id, email, display_name, password_hash, role, employee_id FROM users WHERE email = ?",
                (rs, i) -> new User(rs.getObject("id", UUID.class), rs.getString("tenant_id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("password_hash"), rs.getString("role"), rs.getString("employee_id")),
                email).stream().findFirst();
    }

    public List<String> officeCodes(String tenantId) {
        return jdbc.queryForList("SELECT code FROM offices WHERE tenant_id = ?", String.class, tenantId);
    }

    public List<Office> offices(String tenantId) {
        return jdbc.query("SELECT code, name, city FROM offices WHERE tenant_id = ? ORDER BY city, code",
                (rs, i) -> new Office(rs.getString("code"), rs.getString("name"), rs.getString("city")), tenantId);
    }

    /** @return true the first time this consumer sees the event */
    public boolean markConsumed(String consumer, UUID eventId) {
        return jdbc.update("INSERT INTO consumed_events (consumer, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING", consumer, eventId) == 1;
    }
}
