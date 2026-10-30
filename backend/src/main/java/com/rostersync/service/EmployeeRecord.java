package com.rostersync.service;

import com.rostersync.domain.HrSnapshot;
import com.rostersync.domain.TransportPrefs;
import com.rostersync.persistence.EmployeeRepository.EmployeeRow;
import java.time.OffsetDateTime;

/** Read model of one employee: what the API returns (before role-based masking) and what is cached. */
public record EmployeeRecord(String employeeId, String status, String inactiveReason, HrSnapshot hr, TransportPrefs transport,
                             long hrVersion, long transportVersion, OffsetDateTime updatedAt) {

    public static EmployeeRecord of(EmployeeRow row) {
        return new EmployeeRecord(row.employeeId(), row.status().name(), row.inactiveReason() == null ? null : row.inactiveReason().name(),
                row.hr(), row.transport(), row.hrVersion(), row.transportVersion(), row.updatedAt());
    }
}
