package com.rostersync.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness (the process is up) and readiness (it can do useful work). Redis down means slower
 * reads and delayed events, not wrong data, so it degrades readiness but does not fail it.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory redis;

    public HealthController(JdbcTemplate jdbc, RedisConnectionFactory redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "ok");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, String> checks = new LinkedHashMap<>();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            checks.put("database", "ok");
        } catch (RuntimeException e) {
            checks.put("database", "down");
        }
        try (var connection = redis.getConnection()) {
            connection.ping();
            checks.put("redis", "ok");
        } catch (RuntimeException e) {
            checks.put("redis", "degraded");
        }
        boolean ready = "ok".equals(checks.get("database"));
        return ResponseEntity.status(ready ? 200 : 503).body(Map.of("status", ready ? "ready" : "not_ready", "checks", checks));
    }
}
