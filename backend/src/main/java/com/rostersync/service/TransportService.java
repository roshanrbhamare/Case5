package com.rostersync.service;

import com.rostersync.domain.Employee;
import com.rostersync.domain.TransportPrefs;
import com.rostersync.persistence.CacheEvictor;
import com.rostersync.persistence.EmployeeRepository;
import com.rostersync.persistence.Transactions;
import com.rostersync.support.AppException.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;

/** Transport admin edits to transport-owned fields. */
@Service
public class TransportService {

    public record Result(List<String> changedFields, long transportVersion) {}

    private final Transactions tx;
    private final EmployeeRepository employees;
    private final EmployeeWriter writer;
    private final CacheEvictor cache;

    public TransportService(Transactions tx, EmployeeRepository employees, EmployeeWriter writer, CacheEvictor cache) {
        this.tx = tx;
        this.employees = employees;
        this.writer = writer;
        this.cache = cache;
    }

    public Result update(String tenantId, String employeeId, TransportPrefs.Patch patch, long expectedVersion) {
        Result result = tx.call(() -> {
            Employee employee = employees.findForUpdate(tenantId, employeeId).orElseThrow(() -> new NotFoundException("Employee")).toEmployee();
            List<String> changed = writer.applyTransportPatch(employee, patch, expectedVersion, "TRANSPORT_ADMIN");
            return new Result(changed, employee.transportVersion());
        });
        cache.evictEmployee(tenantId, employeeId);
        return result;
    }
}
