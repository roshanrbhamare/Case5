package com.rostersync.domain;

import com.rostersync.domain.Fields.InactiveReason;
import com.rostersync.domain.Fields.Status;
import com.rostersync.support.AppException.ConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregate root. State is private; the only way to change it is through methods that
 * enforce ownership (HR snapshot vs transport patch) and report which fields changed.
 */
public final class Employee {

    private final String tenantId;
    private final String employeeId;
    private HrSnapshot hr;
    private TransportPrefs transport;
    private Status status;
    private InactiveReason inactiveReason;
    private long hrVersion;
    private long transportVersion;

    public Employee(String tenantId, String employeeId, HrSnapshot hr, TransportPrefs transport, Status status,
                    InactiveReason inactiveReason, long hrVersion, long transportVersion) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.employeeId = Objects.requireNonNull(employeeId);
        this.hr = hr == null ? HrSnapshot.EMPTY : hr;
        this.transport = transport == null ? TransportPrefs.NONE : transport;
        this.status = status;
        this.inactiveReason = status == Status.INACTIVE ? inactiveReason : null;
        this.hrVersion = hrVersion;
        this.transportVersion = transportVersion;
    }

    public static Employee joiner(String tenantId, String employeeId, HrSnapshot hr, long version) {
        return new Employee(tenantId, employeeId, hr, TransportPrefs.NONE, Status.ACTIVE, null, version, 0);
    }

    /** An exit for someone we never saw: remembers the version so a late join is recognised as stale. */
    public static Employee tombstone(String tenantId, String employeeId, long version) {
        return new Employee(tenantId, employeeId, HrSnapshot.EMPTY, TransportPrefs.NONE, Status.INACTIVE, InactiveReason.HR_EXIT, version, 0);
    }

    public String tenantId() {
        return tenantId;
    }

    public String employeeId() {
        return employeeId;
    }

    public HrSnapshot hr() {
        return hr;
    }

    public TransportPrefs transport() {
        return transport;
    }

    public Status status() {
        return status;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public InactiveReason inactiveReason() {
        return inactiveReason;
    }

    public long hrVersion() {
        return hrVersion;
    }

    public long transportVersion() {
        return transportVersion;
    }

    public String hrHash() {
        return RecordHasher.hrHash(employeeId, hr);
    }

    public int bucket() {
        return RecordHasher.bucketOf(employeeId);
    }

    /**
     * Applies a full HR snapshot (state-transfer event). Only HR-owned fields exist on the
     * snapshot type, so this can never touch pickup point or special-assistance needs.
     * @return names of the fields whose value changed
     */
    public List<String> applyHrSnapshot(HrSnapshot next, long version) {
        List<String> changed = new ArrayList<>();
        for (String field : Fields.HR) {
            if (!Objects.equals(hr.get(field), next.get(field))) changed.add(field);
        }
        hr = next;
        advanceHrVersion(version);
        return changed;
    }

    public void advanceHrVersion(long version) {
        if (version > hrVersion) hrVersion = version;
    }

    /** @return true when the employee was inactive and is now active */
    public boolean activate() {
        if (isActive()) return false;
        status = Status.ACTIVE;
        inactiveReason = null;
        return true;
    }

    /** @return true when the employee was active and is now inactive */
    public boolean deactivate(InactiveReason reason) {
        boolean wasActive = isActive();
        status = Status.INACTIVE;
        inactiveReason = reason;
        return wasActive;
    }

    /**
     * Transport admin edits use optimistic concurrency: the caller states which version it
     * edited, so two admins can't silently overwrite each other.
     */
    public List<String> applyTransportPatch(TransportPrefs.Patch patch, long expectedVersion) {
        if (!isActive()) {
            throw new ConflictException("EMPLOYEE_INACTIVE", "Transport preferences cannot be changed for an inactive employee.");
        }
        if (expectedVersion != transportVersion) {
            throw new ConflictException("STALE_TRANSPORT_VERSION", "Someone else changed these preferences. Reload and try again.",
                    Map.of("currentVersion", transportVersion));
        }
        List<String> changed = new ArrayList<>();
        String pickup = transport.pickupPoint();
        String assistance = transport.specialAssistance();
        if (patch.hasPickupPoint() && !Objects.equals(pickup, patch.pickupPoint())) {
            pickup = patch.pickupPoint();
            changed.add("pickupPoint");
        }
        if (patch.hasSpecialAssistance() && !Objects.equals(assistance, patch.specialAssistance())) {
            assistance = patch.specialAssistance();
            changed.add("specialAssistance");
        }
        if (!changed.isEmpty()) {
            transport = new TransportPrefs(pickup, assistance);
            transportVersion += 1;
        }
        return changed;
    }
}
