package com.rostersync.persistence;

import com.rostersync.config.CacheConfig;
import com.rostersync.config.RosterMetrics;
import java.util.Collection;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Programmatic eviction for loops (one ingest batch touches many employees, which an
 * annotation can't express). Called after the transaction commits. Failures only bump a
 * metric: the TTL still bounds staleness.
 */
@Component
public class CacheEvictor {

    private final CacheManager cacheManager;
    private final RosterMetrics metrics;

    public CacheEvictor(CacheManager cacheManager, RosterMetrics metrics) {
        this.cacheManager = cacheManager;
        this.metrics = metrics;
    }

    public static String employeeKey(String tenantId, String employeeId) {
        return tenantId + ":" + employeeId;
    }

    public void evictEmployee(String tenantId, String employeeId) {
        evict(CacheConfig.EMPLOYEE, employeeKey(tenantId, employeeId));
    }

    public void evictEmployees(String tenantId, Collection<String> employeeIds) {
        employeeIds.forEach(id -> evictEmployee(tenantId, id));
    }

    private void evict(String cacheName, Object key) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) cache.evict(key);
        } catch (RuntimeException e) {
            metrics.cache(cacheName, "evict_error");
        }
    }
}
