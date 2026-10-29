package com.rostersync.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Change events carry ids, versions and field names, not personal data. Consumers that
 * need details read them through the API with their own permissions (claim-check), so the
 * stream never becomes a second copy of everyone's phone number.
 */
public final class DomainEvents {

    /** One row for the outbox table. */
    public record OutboxMessage(String tenantId, String aggregateId, String eventType, Map<String, Object> payload) {}

    private DomainEvents() {}

    public static OutboxMessage employeeChanged(Employee employee, Change change, String source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employeeId", employee.employeeId());
        payload.put("hrVersion", employee.hrVersion());
        payload.put("transportVersion", employee.transportVersion());
        payload.put("status", employee.status().name());
        payload.put("inactiveReason", employee.inactiveReason() == null ? null : employee.inactiveReason().name());
        payload.put("changedFields", change.changedFields());
        payload.put("officeCode", employee.hr().officeCode());
        payload.put("shiftCode", employee.hr().shiftCode());
        payload.put("source", source);
        return new OutboxMessage(employee.tenantId(), employee.employeeId(), change.type().eventName(), payload);
    }

    public static OutboxMessage reconcileBlocked(String tenantId, UUID runId, long activeCount, long missingCount, SafetyBrake.Verdict verdict) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId.toString());
        payload.put("activeCount", activeCount);
        payload.put("missingCount", missingCount);
        payload.put("missingRatio", verdict.missingRatio());
        payload.put("threshold", verdict.threshold());
        return new OutboxMessage(tenantId, runId.toString(), "ReconcileRunBlocked", payload);
    }
}
