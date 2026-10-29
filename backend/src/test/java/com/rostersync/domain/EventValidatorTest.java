package com.rostersync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.rostersync.domain.EventValidator.FieldIssue;
import com.rostersync.domain.EventValidator.Invalid;
import com.rostersync.domain.EventValidator.Valid;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EventValidatorTest {

    static final Set<String> OFFICES = Set.of("BLR-WF", "PNQ-HJ");

    static Map<String, Object> valid(Map<String, Object> dataOverrides) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fullName", "Asha Rao");
        data.put("email", "Asha.Rao@Acme.Example");
        data.put("phone", "+91 98000 00001");
        data.put("managerId", "E0001");
        data.put("officeCode", "blr-wf");
        data.put("shiftCode", "s0900");
        data.putAll(dataOverrides);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "UPDATE");
        event.put("employeeId", "E1001");
        event.put("version", 7);
        event.put("data", data);
        return event;
    }

    static Map<String, Object> valid() {
        return valid(Map.of());
    }

    @Test
    void acceptsAndNormalisesAValidEvent() {
        var result = (Valid) EventValidator.validateEvent(valid(), OFFICES);
        assertThat(result.event().data()).isEqualTo(new HrSnapshot("Asha Rao", "asha.rao@acme.example", "+919800000001", "E0001", "BLR-WF", "S0900"));
        assertThat(result.event().version()).isEqualTo(7);
    }

    @Test
    void acceptsANumericStringVersion() {
        Map<String, Object> event = valid();
        event.put("version", "12");
        assertThat(((Valid) EventValidator.validateEvent(event, OFFICES)).event().version()).isEqualTo(12);
    }

    @Test
    void rejectsAMalformedPhoneWithACodeNeverTheValue() {
        var result = (Invalid) EventValidator.validateEvent(valid(Map.of("phone", "12abc")), OFFICES);
        assertThat(result.errors()).containsExactly(new FieldIssue("INVALID_PHONE", "phone"));
        assertThat(result.toString()).doesNotContain("12abc");
    }

    @Test
    void rejectsAnUnknownOfficeCode() {
        var result = (Invalid) EventValidator.validateEvent(valid(Map.of("officeCode", "MARS-01")), OFFICES);
        assertThat(result.errors()).containsExactly(new FieldIssue("UNKNOWN_OFFICE_CODE", "officeCode"));
        assertThat(result.employeeId()).isEqualTo("E1001");
    }

    @Test
    void reportsMissingRequiredFields() {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("email", "   ");
        overrides.put("fullName", null);
        var result = (Invalid) EventValidator.validateEvent(valid(overrides), OFFICES);
        assertThat(result.errors()).contains(new FieldIssue("MISSING_EMAIL", "email"), new FieldIssue("MISSING_FULL_NAME", "fullName"));
    }

    @Test
    void rejectsABadEnvelope() {
        Map<String, Object> event = Map.of("type", "MERGE", "employeeId", "bad id!", "version", -1);
        var result = (Invalid) EventValidator.validateEvent(event, OFFICES);
        assertThat(result.employeeId()).isNull();
        assertThat(result.errors()).extracting(FieldIssue::code).contains("INVALID_EVENT_TYPE", "INVALID_EMPLOYEE_ID", "INVALID_VERSION");
    }

    @Test
    void deactivateNeedsNoData() {
        assertThat(EventValidator.validateEvent(Map.of("type", "DEACTIVATE", "employeeId", "E1001", "version", 9), OFFICES)).isInstanceOf(Valid.class);
    }

    @Test
    void dropsTransportOwnedFieldsFromAnHrEventAndWarns() {
        var result = (Valid) EventValidator.validateEvent(valid(Map.of("pickupPoint", "Gate 7")), OFFICES);
        assertThat(result.warnings()).containsExactly(new FieldIssue("FIELD_NOT_OWNED_BY_HR", "pickupPoint"));
    }

    @Test
    void rejectsNonObjectRecords() {
        assertThat(((Invalid) EventValidator.validateEvent("nope", OFFICES)).errors()).containsExactly(new FieldIssue("INVALID_RECORD", null));
    }

    @Test
    void validatesANightlyFileRow() {
        Map<String, String> row = new HashMap<>();
        row.put("employee_id", "E2002");
        row.put("full_name", "Ravi Kumar");
        row.put("email", "ravi@acme.example");
        row.put("phone", "9876543210");
        row.put("manager_id", "");
        row.put("office_code", "PNQ-HJ");
        row.put("shift_code", "");
        row.put("version", "3");
        var result = (Valid) EventValidator.validateFileRow(row, OFFICES);
        assertThat(result.event().type()).isEqualTo(HrEvent.Type.CREATE);
        assertThat(result.event().version()).isEqualTo(3);
        assertThat(result.event().data().managerId()).isNull();
    }

    @Test
    void listsMissingFileColumns() {
        assertThat(EventValidator.missingColumns(List.of("employee_id", "full_name"))).contains("version");
    }
}
