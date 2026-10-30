package com.rostersync.web.security;

import com.rostersync.service.AuthService;
import com.rostersync.service.RosterPrincipal;
import com.rostersync.support.AppException.UnauthenticatedException;
import com.rostersync.web.AccessLogFilter;
import com.rostersync.web.ErrorResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a credential into a principal: {@code x-api-key} for the HR system, or
 * {@code Authorization: Bearer <jwt>} for people. A present-but-invalid credential is
 * rejected here with 401; a missing one falls through to the entry point for protected routes.
 */
public class ApiAuthenticationFilter extends OncePerRequestFilter {

    private final AuthService auth;
    private final ErrorResponses errors;

    public ApiAuthenticationFilter(AuthService auth, ErrorResponses errors) {
        this.auth = auth;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader("x-api-key");
        String authorization = request.getHeader("Authorization");
        try {
            RosterPrincipal principal = null;
            if (apiKey != null && !apiKey.isBlank()) {
                principal = auth.principalFromApiKey(apiKey);
            } else if (authorization != null && authorization.startsWith("Bearer ")) {
                principal = auth.principalFromToken(authorization.substring(7).trim());
            }
            if (principal != null) {
                var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                request.setAttribute(AccessLogFilter.PRINCIPAL_ATTRIBUTE, principal);
                MDC.put("tenantId", principal.tenantId());
            }
        } catch (UnauthenticatedException e) {
            errors.write(request, response, e);
            return;
        }
        chain.doFilter(request, response);
    }
}
