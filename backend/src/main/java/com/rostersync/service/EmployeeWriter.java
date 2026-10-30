package com.rostersync.service;

import com.rostersync.domain.Change;
import com.rostersync.domain.DomainEvents;
import com.rostersync.domain.Employee;
import com.rostersync.domain.EventHandler;
import com.rostersync.domain.HrEvent;
import com.rostersync.domain.HrSnapshot;
import com.rostersync.domain.RecordHasher;
import com.rostersync.domain.ReconcilePolicy;
import com.rostersync.domain.ReconcilePolicy.Action;
import com.rostersync.domain.TransportPrefs;
import com.rostersync.domain.VersionPolicy;
import com.rostersync.domain.VersionPolicy.Decision;
import com.rostersync.persistence.EmployeeRepository;
import com.rostersync.persistence.EmployeeRepository.EmployeeRow;
import com.rostersync.persistence.OutboxRepository;
import com.rostersync.persistence.ReconcileRepository.ChangedRow;
import com.rostersync.support.Chaos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The single write path for employee records. Every method runs inside the caller's
 * transaction: the employee row and its outbox event commit together, so the database and
 * the published events can never disagree.
 *
 * <p>{@code source} says where a change came from (HR_EVENT, NIGHTLY_FILE, TRANSPORT_ADMIN)
 * and is carried in the published event.
 */
@Service
public class EmployeeWriter {

    public enum Outcome { APPLIED, STALE, DUPLICATE, REJECTED }

    public record ApplyResult(Outcome outcome, List<String> changedFields, boolean conflict) {
        static ApplyResult applied(List<String> changedFields) {
            return new ApplyResult(Outcome.APPLIED, changedFields, false);
        }
    }

    private final EmployeeRepository employees;
    private final OutboxRepository outbox;
    private final Chaos chaos;
    private final Map<HrEvent.Type, EventHandler> handlers = EventHandler.registry();

    public EmployeeWriter(EmployeeRepository employees, OutboxRepository outbox, Chaos chaos) {
        this.employees = employees;
        this.outbox = outbox;
        this.chaos = chaos;
    }

    /** One validated HR event: lock row, version check, handler, write and outbox. */
    public ApplyResult applyHrEvent(String tenantId, HrEvent event, String source) {
        EventHandler handler = handlers.get(event.type());
        EmployeeRow row = employees.findForUpdate(tenantId, event.employeeId()).orElse(null);

        if (row == null) {
            EventHandler.Creation creation = handler.createFrom(tenantId, event);
            if (employees.insertIfAbsent(creation.employee())) {
                recordChange(creation.employee(), creation.change(), source);
                return ApplyResult.applied(creation.change() == null ? List.of() : creation.change().changedFields());
            }
            // Another transaction created this employee between our read and insert. Its row is
            // committed now: lock it and fall through to the normal version check.
            row = employees.findForUpdate(tenantId, event.employeeId()).orElseThrow();
        }

        Decision decision = VersionPolicy.decide(row.hrVersion(), event.version());
        if (decision == Decision.STALE) return new ApplyResult(Outcome.STALE, List.of(), false);
        if (decision == Decision.DUPLICATE) return new ApplyResult(Outcome.DUPLICATE, List.of(), isConflict(row, event));

        Employee employee = row.toEmployee();
        Change change = handler.applyTo(employee, event);
        employees.saveHr(employee);
        recordChange(employee, change, source);
        return ApplyResult.applied(change == null ? List.of() : change.changedFields());
    }

    /** One changed row from a nightly export, re-read under lock (the live feed may have moved on). */
    public Action applyFileRow(String tenantId, ChangedRow file, String source) {
        return applyFileRow(tenantId, file, source, 1);
    }

    private Action applyFileRow(String tenantId, ChangedRow file, String source, int attempt) {
        EmployeeRow row = employees.findForUpdate(tenantId, file.employeeId()).orElse(null);
        ReconcilePolicy.Stored stored = row == null ? null
                : new ReconcilePolicy.Stored(row.hrVersion(), row.hrHash(), row.status(), row.inactiveReason());
        Action action = ReconcilePolicy.decide(new ReconcilePolicy.FileRow(file.version(), file.hrHash()), stored);
        HrSnapshot snapshot = new HrSnapshot(file.fullName(), file.email(), file.phone(), file.managerId(), file.officeCode(), file.shiftCode());

        if (action == Action.CREATE) {
            Employee employee = Employee.joiner(tenantId, file.employeeId(), snapshot, file.version());
            if (!employees.insertIfAbsent(employee)) {
                return attempt > 1 ? Action.CONFLICT : applyFileRow(tenantId, file, source, attempt + 1);
            }
            recordChange(employee, new Change(Change.Type.CREATED, Change.ALL_HR_AND_STATUS), source);
            return action;
        }

        if (action == Action.UPDATE || action == Action.REACTIVATE) {
            Employee employee = row.toEmployee();
            List<String> changed = employee.applyHrSnapshot(snapshot, file.version());
            boolean reactivated = employee.activate();
            employees.saveHr(employee);
            if (reactivated) {
                List<String> withStatus = new ArrayList<>(changed);
                withStatus.add("status");
                recordChange(employee, new Change(Change.Type.REACTIVATED, withStatus), source);
            } else if (!changed.isEmpty()) {
                recordChange(employee, new Change(Change.Type.UPDATED, changed), source);
            }
        }
        return action;
    }

    /** Transport admin edit: transport columns only, optimistic version check in the aggregate. */
    public List<String> applyTransportPatch(Employee employee, TransportPrefs.Patch patch, long expectedVersion, String source) {
        List<String> changed = employee.applyTransportPatch(patch, expectedVersion);
        if (changed.isEmpty()) return changed;
        employees.saveTransport(employee);
        recordChange(employee, new Change(Change.Type.TRANSPORT_UPDATED, changed), source);
        return changed;
    }

    private void recordChange(Employee employee, Change change, String source) {
        // Failure-injection point: the employee row is written but its event is not.
        // A crash here must leave neither behind (the transaction never commits).
        chaos.maybeCrash(Chaos.INGEST_AFTER_EMPLOYEE_WRITE);
        if (change == null) return;
        outbox.add(DomainEvents.employeeChanged(employee, change, source));
    }

    private boolean isConflict(EmployeeRow row, HrEvent event) {
        if (event.type() == HrEvent.Type.DEACTIVATE) return false;
        return !RecordHasher.hrHash(event.employeeId(), event.data()).equals(row.hrHash());
    }
}
