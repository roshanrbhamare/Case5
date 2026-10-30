package com.rostersync.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rostersync.config.RosterMetrics;
import com.rostersync.config.RosterSyncProperties;
import com.rostersync.persistence.SignInCodeRepository;
import com.rostersync.persistence.SignInCodeRepository.Holder;
import com.rostersync.persistence.SignInCodeRepository.OpenCode;
import com.rostersync.persistence.Transactions;
import com.rostersync.service.AuthService.LoginResult;
import com.rostersync.support.AppException.UnauthenticatedException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Employee sign-in with a one-time code sent to the email HR gave us. The HR record is the
 * account: anyone the feed sent can sign in straight away, and an exit (status INACTIVE)
 * stops them at the next sign-in. No passwords exist for employees at all.
 *
 * <p>Rules: a 6-digit code, stored only as an HMAC; valid for a few minutes; a new code
 * replaces the previous one; a fixed number of wrong attempts burns it; it works once. The
 * request answer is the same whether or not the email belongs to anyone, so it can't be
 * used to find out who works where.
 */
@Service
public class SignInCodeService {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CodeRequested(boolean sent, long expiresInSeconds, String demoCode) {}

    private static final Logger log = LoggerFactory.getLogger(SignInCodeService.class);
    private static final String WRONG_CODE = "The code is wrong or has expired. Request a new one.";

    private final Transactions tx;
    private final SignInCodeRepository codes;
    private final AuthService auth;
    private final RosterMetrics metrics;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec hmacKey;
    private final Duration ttl;
    private final int maxAttempts;
    private final boolean showInResponse;

    public SignInCodeService(Transactions tx, SignInCodeRepository codes, AuthService auth, RosterMetrics metrics, RosterSyncProperties props) {
        this.tx = tx;
        this.codes = codes;
        this.auth = auth;
        this.metrics = metrics;
        this.hmacKey = new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.ttl = props.signInCode().ttl();
        this.maxAttempts = props.signInCode().maxAttempts();
        this.showInResponse = props.signInCode().showInResponse();
    }

    public CodeRequested request(String email) {
        Optional<Holder> holder = holderOf(email);
        if (holder.isEmpty()) {
            metrics.signInCode("no_active_employee");
            log.info("Sign-in code not issued: no single active employee has that email");
            return new CodeRequested(true, ttl.toSeconds(), null);
        }
        Holder h = holder.get();
        String code = "%06d".formatted(random.nextInt(1_000_000));
        UUID id = UUID.randomUUID();
        tx.run(() -> codes.replaceOpenCode(h, id, hash(id, code), ttl));
        metrics.signInCode("issued");
        log.info("Sign-in code issued {} {}", kv("tenantId", h.tenantId()), kv("employeeId", h.employeeId()));
        // A real deployment hands the code to an email/SMS sender here.
        return new CodeRequested(true, ttl.toSeconds(), showInResponse ? code : null);
    }

    public LoginResult verify(String email, String code) {
        Holder h = holderOf(email).orElseThrow(() -> rejected(null));
        // Decided inside the transaction, thrown outside it, so a wrong guess is still counted.
        boolean accepted = tx.call(() -> {
            OpenCode open = codes.latestOpenForUpdate(h).orElse(null);
            if (open == null) return false;
            codes.recordAttempt(open.id());
            if (MessageDigest.isEqual(hash(open.id(), code).getBytes(StandardCharsets.UTF_8), open.codeHash().getBytes(StandardCharsets.UTF_8))) {
                codes.close(open.id());
                return true;
            }
            if (open.attempts() + 1 >= maxAttempts) codes.close(open.id());
            return false;
        });
        if (!accepted) throw rejected(h);
        metrics.signInCode("signed_in");
        log.info("Employee signed in with a code {} {}", kv("tenantId", h.tenantId()), kv("employeeId", h.employeeId()));
        return auth.employeeSignIn(h.tenantId(), h.employeeId(), h.fullName());
    }

    /** Exactly one active employee, or nobody: an email shared by two clients is not guessed at. */
    private Optional<Holder> holderOf(String email) {
        List<Holder> holders = tx.call(() -> codes.activeEmployeesByEmail(email.trim().toLowerCase(Locale.ROOT)));
        return holders.size() == 1 ? Optional.of(holders.get(0)) : Optional.empty();
    }

    private UnauthenticatedException rejected(Holder h) {
        metrics.signInCode("rejected");
        if (h != null) log.info("Sign-in code rejected {} {}", kv("tenantId", h.tenantId()), kv("employeeId", h.employeeId()));
        return new UnauthenticatedException(WRONG_CODE);
    }

    private String hash(UUID id, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal((id + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }
}
