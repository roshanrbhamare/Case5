package com.rostersync.web;

import com.rostersync.service.AuthService;
import com.rostersync.service.AuthService.LoginResult;
import com.rostersync.service.RosterPrincipal;
import com.rostersync.service.SignInCodeService;
import com.rostersync.service.SignInCodeService.CodeRequested;
import com.rostersync.support.AppException.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class AuthController {

    public record LoginRequest(String email, String password) {}

    public record CodeRequest(String email) {}

    public record CodeSignIn(String email, String code) {}

    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");

    private final AuthService auth;
    private final SignInCodeService codes;
    private final LoginRateLimiter limiter;

    public AuthController(AuthService auth, SignInCodeService codes, LoginRateLimiter limiter) {
        this.auth = auth;
        this.codes = codes;
        this.limiter = limiter;
    }

    @PostMapping("/auth/login")
    public LoginResult login(@RequestBody(required = false) LoginRequest body, HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        if (body == null || body.email() == null || body.email().length() < 3 || body.email().length() > 254
                || body.password() == null || body.password().isEmpty() || body.password().length() > 200) {
            throw new ValidationException("INVALID_LOGIN", "Enter your email and password.");
        }
        return auth.login(body.email(), body.password());
    }

    /** Employees: send a one-time code to the email HR gave us. Same answer whether or not it exists. */
    @PostMapping("/auth/sign-in-code")
    public CodeRequested requestCode(@RequestBody(required = false) CodeRequest body, HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        if (body == null || !plausibleEmail(body.email())) throw new ValidationException("INVALID_EMAIL", "Enter your work email.");
        return codes.request(body.email());
    }

    @PostMapping("/auth/sign-in-code/verify")
    public LoginResult signInWithCode(@RequestBody(required = false) CodeSignIn body, HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        if (body == null || !plausibleEmail(body.email()) || body.code() == null || !SIX_DIGITS.matcher(body.code().trim()).matches()) {
            throw new ValidationException("INVALID_SIGN_IN", "Enter your work email and the 6-digit code.");
        }
        return codes.verify(body.email(), body.code().trim());
    }

    private static boolean plausibleEmail(String email) {
        return email != null && email.length() >= 3 && email.length() <= 254 && email.contains("@");
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(@AuthenticationPrincipal RosterPrincipal principal) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("userId", principal.userId());
        me.put("tenantId", principal.tenantId());
        me.put("role", principal.role());
        me.put("employeeId", principal.employeeId());
        me.put("displayName", principal.displayName());
        return me;
    }
}
