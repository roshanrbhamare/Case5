package com.rostersync.web;

import com.rostersync.domain.AccessPolicy;
import com.rostersync.domain.AccessPolicy.Role;
import com.rostersync.domain.HrSnapshot;
import com.rostersync.domain.TransportPrefs;
import com.rostersync.persistence.BookingRepository.BookingRow;
import com.rostersync.persistence.IngestBatchRepository.Batch;
import com.rostersync.persistence.ReconcileRepository.RunRow;
import com.rostersync.service.EmployeeRecord;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

/** API response shapes. Role-shaped where it matters: operators see masked contact details. */
public final class Views {

    private Views() {}

    public record EmployeeView(String employeeId, String status, String inactiveReason, long hrVersion, long transportVersion,
                               OffsetDateTime updatedAt, HrSnapshot hr, TransportPrefs transport) {

        public static EmployeeView of(EmployeeRecord r, Role role) {
            if (r == null) return null;
            boolean full = AccessPolicy.seesContactDetails(role);
            HrSnapshot hr = full ? r.hr() : new HrSnapshot(initials(r.hr().fullName()), maskEmail(r.hr().email()), maskPhone(r.hr().phone()),
                    r.hr().managerId(), r.hr().officeCode(), r.hr().shiftCode());
            TransportPrefs transport = full ? r.transport()
                    : new TransportPrefs(r.transport().pickupPoint() == null ? null : "On file", r.transport().specialAssistance() == null ? null : "On file");
            return new EmployeeView(r.employeeId(), r.status(), r.inactiveReason(), r.hrVersion(), r.transportVersion(), r.updatedAt(), hr, transport);
        }
    }

    public record Plan(Integer creates, Integer updates, Integer reactivations, Integer deactivations, Integer unchanged, Integer stale, Integer conflicts) {}

    public record RunView(UUID id, String status, String uploadedBy, double threshold, int fileRows, int rejectedRows, int duplicateRows,
                          Integer activeBefore, Integer missingCount, Double missingRatio, Plan plan, Integer bucketsSkipped, Integer appliedChanges,
                          String failureCode, String decidedBy, String decisionNote, UUID batchId, OffsetDateTime createdAt,
                          OffsetDateTime analyzedAt, OffsetDateTime decidedAt, OffsetDateTime completedAt) {

        public static RunView of(RunRow r) {
            if (r == null) return null;
            return new RunView(r.id(), r.status().name(), r.uploadedBy(), r.threshold(), r.fileRows(), r.rejectedRows(), r.duplicateRows(),
                    r.activeBefore(), r.missingCount(), r.missingRatio(),
                    new Plan(r.creates(), r.updates(), r.reactivations(), r.deactivations(), r.unchanged(), r.stale(), r.conflicts()),
                    r.bucketsSkipped(), r.appliedChanges(), r.failureCode(), r.decidedBy(), r.decisionNote(), r.batchId(), r.createdAt(),
                    r.analyzedAt(), r.decidedAt(), r.completedAt());
        }
    }

    public record BatchView(UUID id, String source, String actor, int received, int applied, int stale, int duplicate, int rejected,
                            OffsetDateTime createdAt) {

        public static BatchView of(Batch b) {
            return new BatchView(b.id(), b.source(), b.actor(), b.received(), b.applied(), b.stale(), b.duplicate(), b.rejected(), b.createdAt());
        }
    }

    public record BookingView(UUID id, LocalDate rideDate, String direction, String shiftCode, String officeCode, String status,
                              String cancelReason, int seatsBooked, int capacity) {

        public static BookingView of(BookingRow b) {
            return new BookingView(b.id(), b.rideDate(), b.direction(), b.shiftCode(), b.officeCode(), b.status(), b.cancelReason(),
                    b.seatsBooked(), b.capacity());
        }
    }

    static String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        return at <= 0 ? "•••" : email.charAt(0) + "•••" + email.substring(at);
    }

    static String maskPhone(String phone) {
        return phone == null ? null : "•••••" + phone.substring(Math.max(0, phone.length() - 4));
    }

    static String initials(String name) {
        if (name == null) return null;
        return Arrays.stream(name.trim().split("\\s+")).filter(p -> !p.isEmpty()).map(p -> p.charAt(0) + ".").collect(Collectors.joining(" "));
    }
}
