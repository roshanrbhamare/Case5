package com.rostersync.web;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.rostersync.service.RosterPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/** One line per request: method, path without the query string (a search term can be a name), status, latency, tenant and role. */
public class AccessLogFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "rostersync.principal";
    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/health") || path.equals("/metrics");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            RosterPrincipal principal = (RosterPrincipal) request.getAttribute(PRINCIPAL_ATTRIBUTE);
            log.info("request completed {} {} {} {} {} {}", kv("method", request.getMethod()), kv("path", request.getRequestURI()),
                    kv("status", response.getStatus()), kv("ms", (System.nanoTime() - start) / 1_000_000),
                    kv("tenantId", principal == null ? null : principal.tenantId()), kv("role", principal == null ? null : principal.role()));
        }
    }
}
