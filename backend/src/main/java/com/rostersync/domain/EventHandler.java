package com.rostersync.domain;

import com.rostersync.domain.Fields.InactiveReason;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One handler per HR event type. The writer picks a handler by type and never branches on
 * type itself, so adding an event type means adding a subclass, not editing the write path.
 *
 * <p>Both methods return a {@link Change}, or null when nothing observable changed (for
 * example a version bump with identical data). Only changes are published.
 */
public abstract sealed class EventHandler permits EventHandler.SnapshotHandler, EventHandler.DeactivateHandler {

    /** Result of creating an employee we have never seen. */
    public record Creation(Employee employee, Change change) {}

    /** Builds the employee for an event about someone we have never seen. */
    public abstract Creation createFrom(String tenantId, HrEvent event);

    /** Mutates an existing employee for a newer version. */
    public abstract Change applyTo(Employee employee, HrEvent event);

    /** CREATE and UPDATE carry a full HR snapshot, which is what makes out-of-order delivery safe. */
    public abstract static sealed class SnapshotHandler extends EventHandler permits CreateHandler, UpdateHandler {
        @Override
        public Creation createFrom(String tenantId, HrEvent event) {
            Employee employee = Employee.joiner(tenantId, event.employeeId(), event.data(), event.version());
            return new Creation(employee, new Change(Change.Type.CREATED, Change.ALL_HR_AND_STATUS));
        }
    }

    public static final class CreateHandler extends SnapshotHandler {
        /** A newer CREATE for a known employee is a rehire: it also reactivates. */
        @Override
        public Change applyTo(Employee employee, HrEvent event) {
            List<String> changed = employee.applyHrSnapshot(event.data(), event.version());
            if (employee.activate()) {
                List<String> withStatus = new ArrayList<>(changed);
                withStatus.add("status");
                return new Change(Change.Type.REACTIVATED, withStatus);
            }
            return changed.isEmpty() ? null : new Change(Change.Type.UPDATED, changed);
        }
    }

    public static final class UpdateHandler extends SnapshotHandler {
        /** UPDATE never changes status: an update for someone who left keeps them inactive. */
        @Override
        public Change applyTo(Employee employee, HrEvent event) {
            List<String> changed = employee.applyHrSnapshot(event.data(), event.version());
            return changed.isEmpty() ? null : new Change(Change.Type.UPDATED, changed);
        }
    }

    public static final class DeactivateHandler extends EventHandler {
        /** Exit for someone we never saw (their CREATE is late): store a tombstone, publish nothing. */
        @Override
        public Creation createFrom(String tenantId, HrEvent event) {
            return new Creation(Employee.tombstone(tenantId, event.employeeId(), event.version()), null);
        }

        @Override
        public Change applyTo(Employee employee, HrEvent event) {
            employee.advanceHrVersion(event.version());
            return employee.deactivate(InactiveReason.HR_EXIT) ? new Change(Change.Type.DEACTIVATED, List.of("status")) : null;
        }
    }

    /** Polymorphic dispatch table: event type to handler. */
    public static Map<HrEvent.Type, EventHandler> registry() {
        Map<HrEvent.Type, EventHandler> handlers = new EnumMap<>(HrEvent.Type.class);
        handlers.put(HrEvent.Type.CREATE, new CreateHandler());
        handlers.put(HrEvent.Type.UPDATE, new UpdateHandler());
        handlers.put(HrEvent.Type.DEACTIVATE, new DeactivateHandler());
        return handlers;
    }
}
