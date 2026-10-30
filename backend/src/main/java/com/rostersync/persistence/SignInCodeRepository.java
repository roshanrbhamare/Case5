package com.rostersync.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * One-time sign-in codes for employees. Employee lookups by email are global on purpose:
 * like a password login, sign-in has to find the tenant from the credential.
 */
@Repository
public class SignInCodeRepository {

    /** The active employee an email belongs to. */
    public record Holder(String tenantId, String employeeId, String fullName) {}

    public record OpenCode(UUID id, String codeHash, int attempts) {}

    private final JdbcTemplate jdbc;

    public SignInCodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every active employee with this email; more than one (two clients, one email) means ambiguous. */
    public List<Holder> activeEmployeesByEmail(String email) {
        return jdbc.query("SELECT tenant_id, employee_id, full_name FROM employees WHERE email = ? AND status = 'ACTIVE' LIMIT 2",
                (rs, i) -> new Holder(rs.getString("tenant_id"), rs.getString("employee_id"), rs.getString("full_name")), email);
    }

    /** A new code replaces any earlier one that is still open. */
    public void replaceOpenCode(Holder holder, UUID id, String codeHash, Duration ttl) {
        jdbc.update("UPDATE sign_in_codes SET used_at = now() WHERE tenant_id = ? AND employee_id = ? AND used_at IS NULL",
                holder.tenantId(), holder.employeeId());
        jdbc.update("""
                INSERT INTO sign_in_codes (id, tenant_id, employee_id, code_hash, expires_at)
                VALUES (?, ?, ?, ?, now() + make_interval(secs => ?))""",
                id, holder.tenantId(), holder.employeeId(), codeHash, (double) ttl.toSeconds());
    }

    /** The latest unexpired, unused code, locked so concurrent guesses are counted one by one. */
    public Optional<OpenCode> latestOpenForUpdate(Holder holder) {
        return jdbc.query("""
                SELECT id, code_hash, attempts FROM sign_in_codes
                 WHERE tenant_id = ? AND employee_id = ? AND used_at IS NULL AND expires_at > now()
                 ORDER BY created_at DESC
                 LIMIT 1
                 FOR UPDATE""",
                (rs, i) -> new OpenCode(rs.getObject("id", UUID.class), rs.getString("code_hash"), rs.getInt("attempts")),
                holder.tenantId(), holder.employeeId()).stream().findFirst();
    }

    public void recordAttempt(UUID id) {
        jdbc.update("UPDATE sign_in_codes SET attempts = attempts + 1 WHERE id = ?", id);
    }

    /** Used, or burned after too many wrong attempts: either way it can't be used again. */
    public void close(UUID id) {
        jdbc.update("UPDATE sign_in_codes SET used_at = now() WHERE id = ?", id);
    }
}
