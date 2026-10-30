package com.rostersync.service;

import com.rostersync.config.RosterSyncProperties;
import com.rostersync.domain.AccessPolicy.Role;
import com.rostersync.persistence.DirectoryRepository;
import com.rostersync.persistence.DirectoryRepository.Tenant;
import com.rostersync.persistence.DirectoryRepository.User;
import com.rostersync.support.AppException.UnauthenticatedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * People sign in with email and password and get a short-lived JWT carrying their tenant and
 * role. The HR system authenticates with a per-tenant API key, stored only as a SHA-256 hash.
 * The tenant always comes from the credential, never from the request body or URL, which
 * is what makes cross-tenant reads impossible to ask for.
 */
@Service
public class AuthService {

    public record LoginUser(UUID id, String displayName, String role, String tenantId, String tenantName, String employeeId) {}

    public record LoginResult(String token, LoginUser user) {}

    private static final String ISSUER = "rostersync";

    private final DirectoryRepository directory;
    private final ApiKeyResolver apiKeys;
    private final PasswordEncoder passwords;
    private final SecretKey key;
    private final Duration ttl;
    private final String dummyHash;

    public AuthService(DirectoryRepository directory, ApiKeyResolver apiKeys, PasswordEncoder passwords, RosterSyncProperties props) {
        this.directory = directory;
        this.apiKeys = apiKeys;
        this.passwords = passwords;
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.ttl = props.jwtTtl();
        // Compared against for unknown emails, so response time doesn't reveal which accounts exist.
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    public LoginResult login(String email, String password) {
        User user = directory.userByEmail(email.trim().toLowerCase(Locale.ROOT)).orElse(null);
        boolean valid = passwords.matches(password, user == null ? dummyHash : user.passwordHash());
        if (user == null || !valid) throw new UnauthenticatedException("Email or password is incorrect.");

        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(user.id().toString())
                .issuer(ISSUER)
                .claim("tid", user.tenantId())
                .claim("role", user.role())
                .claim("name", user.displayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (user.employeeId() != null) builder.claim("eid", user.employeeId());
        String token = builder.signWith(key).compact();

        String tenantName = directory.tenant(user.tenantId()).map(Tenant::name).orElse(null);
        return new LoginResult(token, new LoginUser(user.id(), user.displayName(), user.role(), user.tenantId(), tenantName, user.employeeId()));
    }

    public RosterPrincipal principalFromToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build().parseSignedClaims(token).getPayload();
            return new RosterPrincipal(claims.getSubject(), claims.get("tid", String.class), Role.valueOf(claims.get("role", String.class)),
                    claims.get("eid", String.class), claims.get("name", String.class), "user:" + claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthenticatedException("Your session has expired. Please sign in again.");
        }
    }

    public RosterPrincipal principalFromApiKey(String apiKey) {
        String tenantId = apiKeys.tenantFor(ApiKeyResolver.hash(apiKey));
        if (tenantId == null) throw new UnauthenticatedException("The API key is not valid.");
        return new RosterPrincipal(null, tenantId, Role.HR_SYSTEM, null, "HR system", "hr-system:" + tenantId);
    }
}
