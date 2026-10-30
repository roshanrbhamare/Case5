package com.rostersync.service;

import com.rostersync.config.CacheConfig;
import com.rostersync.persistence.BookingRepository;
import com.rostersync.persistence.BookingRepository.BookingRow;
import com.rostersync.persistence.EmployeeRepository;
import com.rostersync.persistence.EmployeeRepository.ListFilter;
import com.rostersync.persistence.Transactions;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Reads. Single-employee lookups are cache-aside with a short TTL; writers evict after
 * commit, so a read is stale only if that eviction fails, and then for at most the TTL.
 */
@Service
public class EmployeeQueryService {

    private final Transactions tx;
    private final EmployeeRepository employees;
    private final BookingRepository bookings;

    public EmployeeQueryService(Transactions tx, EmployeeRepository employees, BookingRepository bookings) {
        this.tx = tx;
        this.employees = employees;
        this.bookings = bookings;
    }

    @Cacheable(cacheNames = CacheConfig.EMPLOYEE, key = "#tenantId + ':' + #employeeId", unless = "#result == null")
    public EmployeeRecord get(String tenantId, String employeeId) {
        return tx.call(() -> employees.findById(tenantId, employeeId).map(EmployeeRecord::of).orElse(null));
    }

    public List<EmployeeRecord> list(String tenantId, ListFilter filter) {
        return tx.call(() -> employees.list(tenantId, filter).stream().map(EmployeeRecord::of).toList());
    }

    public List<BookingRow> upcomingBookings(String tenantId, String employeeId) {
        return tx.call(() -> bookings.upcomingForEmployee(tenantId, employeeId, 20));
    }

    public Map<String, Integer> counts(String tenantId) {
        return tx.call(() -> employees.countByStatus(tenantId));
    }
}
