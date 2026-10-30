package com.rostersync.web;

import com.rostersync.support.AppException.TooManyRequestsException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fixed one-minute window per client IP for the login endpoint: slows password guessing.
 * In-memory is enough for one API instance; several instances would move this to Redis.
 */
@Component
public class LoginRateLimiter {

    private record Window(long minute, AtomicInteger count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limitPerMinute;

    public LoginRateLimiter(@Value("${rostersync.login-attempts-per-minute:20}") int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    public void check(String clientIp) {
        long minute = System.currentTimeMillis() / 60_000;
        Window window = windows.compute(clientIp, (ip, current) -> current == null || current.minute() != minute ? new Window(minute, new AtomicInteger()) : current);
        if (window.count().incrementAndGet() > limitPerMinute) throw new TooManyRequestsException();
        if (windows.size() > 10_000) windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
    }
}
