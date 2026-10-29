package com.rostersync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rostersync.domain.EventHandler.CreateHandler;
import com.rostersync.domain.EventHandler.DeactivateHandler;
import com.rostersync.domain.EventHandler.UpdateHandler;
import com.rostersync.domain.Fields.InactiveReason;
import com.rostersync.domain.Fields.Owner;
import com.rostersync.domain.Fields.Status;
import com.rostersync.support.AppException.ConflictException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EmployeeTest {

    static HrSnapshot snapshot() {
        return new HrSnapshot("Asha Rao", "asha.rao@acme.example", "+919800000001", "E0001", "BLR-WF", "S0900");
    }

    static Employee existing() {
        return new Employee("acme", "E1001", snapshot(), new TransportPrefs("Gate 2, Lake View Apartments", "WHEELCHAIR"),
                Status.ACTIVE, null, 5, 3);
    }

    static HrEvent event(HrEvent.Type type, long version, HrSnapshot data) {
        return new HrEvent(null, type, "E1001", version, data);
    }

    static HrSnapshot withShift(String shift) {
        HrSnapshot s = snapshot();
        return new HrSnapshot(s.fullName(), s.email(), s.phone(), s.managerId(), s.officeCode(), shift);
    }

    static HrSnapshot withOffice(String office) {
        HrSnapshot s = snapshot();
        return new HrSnapshot(s.fullName(), s.email(), s.phone(), s.managerId(), office, s.shiftCode());
    }

    @Nested
    class Ownership {

        @Test
        void hrSnapshotNeverOverwritesTransportOwnedFields() {
            Employee employee = existing();
            List<String> changed = employee.applyHrSnapshot(withOffice("PNQ-HJ"), 6);
            assertThat(changed).containsExactly("officeCode");
            assertThat(employee.transport()).isEqualTo(new TransportPrefs("Gate 2, Lake View Apartments", "WHEELCHAIR"));
            assertThat(employee.transportVersion()).isEqualTo(3);
        }

        @Test
        void transportPatchChangesOnlyTransportFieldsAndBumpsItsOwnVersion() {
            Employee employee = existing();
            List<String> changed = employee.applyTransportPatch(new TransportPrefs.Patch(true, "Main Gate, Tech Park", false, null), 3);
            assertThat(changed).containsExactly("pickupPoint");
            assertThat(employee.hr().fullName()).isEqualTo("Asha Rao");
            assertThat(employee.transportVersion()).isEqualTo(4);
            assertThat(employee.hrVersion()).isEqualTo(5);
        }

        @Test
        void transportPatchWithOutdatedVersionIsRejected() {
            assertThatThrownBy(() -> existing().applyTransportPatch(new TransportPrefs.Patch(true, "x", false, null), 2))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Someone else changed");
        }

        @Test
        void transportPatchForInactiveEmployeeIsRejected() {
            Employee employee = existing();
            employee.deactivate(InactiveReason.HR_EXIT);
            assertThatThrownBy(() -> employee.applyTransportPatch(new TransportPrefs.Patch(true, "x", false, null), 3))
                    .hasMessageContaining("inactive");
        }

        @Test
        void ownershipPolicyPartitionsAMixedPatch() {
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("fullName", "A");
            patch.put("pickupPoint", "B");
            patch.put("favouriteColour", "C");
            var result = OwnershipPolicy.partition(Owner.HR, patch);
            assertThat(result.allowed()).containsOnlyKeys("fullName");
            assertThat(result.notOwned()).containsExactly("pickupPoint");
            assertThat(result.unknown()).containsExactly("favouriteColour");
        }

        @Test
        void hashCoversHrContentOnlyNotTransportNotStatus() {
            Employee employee = existing();
            String before = employee.hrHash();
            employee.applyTransportPatch(new TransportPrefs.Patch(true, "Elsewhere", false, null), 3);
            employee.deactivate(InactiveReason.HR_EXIT);
            assertThat(employee.hrHash()).isEqualTo(before);
            employee.applyHrSnapshot(withShift("NIGHT"), 6);
            assertThat(employee.hrHash()).isNotEqualTo(before);
        }
    }

    @Nested
    class Handlers {

        @Test
        void updateReportsExactlyTheFieldsThatChanged() {
            Change change = new UpdateHandler().applyTo(existing(), event(HrEvent.Type.UPDATE, 6, withShift("NIGHT")));
            assertThat(change).isEqualTo(new Change(Change.Type.UPDATED, List.of("shiftCode")));
        }

        @Test
        void updateWithIdenticalDataProducesNoChangeEvent() {
            assertThat(new UpdateHandler().applyTo(existing(), event(HrEvent.Type.UPDATE, 6, snapshot()))).isNull();
        }

        @Test
        void updateDoesNotReactivateSomeoneWhoLeft() {
            Employee employee = existing();
            employee.deactivate(InactiveReason.HR_EXIT);
            new UpdateHandler().applyTo(employee, event(HrEvent.Type.UPDATE, 6, withShift("NIGHT")));
            assertThat(employee.status()).isEqualTo(Status.INACTIVE);
        }

        @Test
        void newerCreateForAnInactiveEmployeeIsARehire() {
            Employee employee = existing();
            employee.deactivate(InactiveReason.HR_EXIT);
            Change change = new CreateHandler().applyTo(employee, event(HrEvent.Type.CREATE, 9, snapshot()));
            assertThat(change.type()).isEqualTo(Change.Type.REACTIVATED);
            assertThat(employee.isActive()).isTrue();
            assertThat(employee.transport().pickupPoint()).isEqualTo("Gate 2, Lake View Apartments");
        }

        @Test
        void deactivateForUnknownEmployeeCreatesTombstoneWithoutChangeEvent() {
            var creation = new DeactivateHandler().createFrom("acme", new HrEvent(null, HrEvent.Type.DEACTIVATE, "E9", 4, null));
            assertThat(creation.employee().status()).isEqualTo(Status.INACTIVE);
            assertThat(creation.employee().hrVersion()).isEqualTo(4);
            assertThat(creation.change()).isNull();
        }

        @Test
        void deactivateOfActiveEmployeeEmitsDeactivated() {
            Change change = new DeactivateHandler().applyTo(existing(), new HrEvent(null, HrEvent.Type.DEACTIVATE, "E1001", 6, null));
            assertThat(change).isEqualTo(new Change(Change.Type.DEACTIVATED, List.of("status")));
        }
    }
}
