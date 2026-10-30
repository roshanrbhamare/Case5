package com.rostersync.web;

import com.rostersync.service.AuthService;
import com.rostersync.service.AuthService.LoginResult;
import com.rostersync.service.RosterPrincipal;
import com.rostersync.support.AppException.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
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

    private final AuthService auth;
    private final LoginRateLimiter limiter;

    public AuthController(AuthService auth, LoginRateLimiter limiter) {
        this.auth = auth;
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
