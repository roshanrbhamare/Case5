package com.rostersync;

import static com.rostersync.support.Fixtures.event;
import static com.rostersync.support.Fixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import com.rostersync.domain.EventValidator.FieldIssue;
import com.rostersync.service.EmployeeWriter.Outcome;
import com.rostersync.service.IngestService;
import com.rostersync.service.IngestService.IngestResult;
import com.rostersync.support.IntegrationTest;
import com.rostersync.support.TestDb;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class IngestIT {

    @Autowired IngestService ingest;

    @BeforeEach
    void reset() {
        TestDb.reset(Map.of("acme", 3));
    }

    IngestResult send(Object... events) {
        return ingest.ingest("acme", List.of(events), "test:hr");
    }

    Map<String, Object> employee(String id) {
        return TestDb.employee("acme", id);
    }

    @Test
    void outOfOrderTransferV7BeforeShiftChangeV6_v6IsIgnoredAsStale() {
        send(event("CREATE", "E5001", 1, snapshot()));
        IngestResult result = send(event("UPDATE", "E5001", 7, snapshot("officeCode", "PNQ-HJ", "shiftCode", "S1000")),
                event("UPDATE", "E5001", 6, snapshot("shiftCode", "NIGHT")));

        assertThat(result.results()).extracting(IngestService.RecordResult::outcome).containsExactly(Outcome.APPLIED, Outcome.STALE);
        assertThat(employee("E5001")).containsEntry("office_code", "PNQ-HJ").containsEntry("shift_code", "S1000").containsEntry("hr_version", 7L);
    }

    @Test
    void replayingTheSameFeedTwiceChangesNothingTheSecondTime() {
        Object[] feed = {
            event("CREATE", "E5001", 1, snapshot()),
            event("CREATE", "E5002", 1, snapshot("email", "b@acme.example")),
            event("UPDATE", "E5001", 2, snapshot("shiftCode", "NIGHT")),
            event("DEACTIVATE", "E5002", 3),
            event("UPDATE", "E1001", 2, snapshot("fullName", "Renamed Person")),
        };
        String state = "SELECT employee_id, hr_version, hr_hash, status, pickup_point, updated_at FROM employees ORDER BY employee_id";

        assertThat(send(feed).summary().applied()).isEqualTo(5);
        List<Map<String, Object>> stateAfterFirst = TestDb.query(state);
        int outboxAfterFirst = TestDb.count("SELECT count(*)::int FROM outbox");

        IngestResult second = send(feed);

        assertThat(second.summary().applied()).isZero();
        assertThat(second.summary().duplicate() + second.summary().stale()).isEqualTo(5);
        assertThat(TestDb.query(state)).isEqualTo(stateAfterFirst);
        assertThat(TestDb.count("SELECT count(*)::int FROM outbox")).isEqualTo(outboxAfterFirst);
    }

    @Test
    void oneBadRecordNeverFailsTheBatch_errorReportHasCodesNotValues() {
        List<Object> events = new ArrayList<>();
        for (int i = 0; i < 100; i++) events.add(event("CREATE", String.format("E6%03d", i), 1, snapshot("email", "p" + i + "@acme.example")));
        events.set(40, event("CREATE", "E6040", 1, snapshot("phone", "12abc")));
        events.set(41, event("CREATE", "E6041", 1, snapshot("officeCode", "MARS-01")));
        events.set(42, "not even an object");

        IngestResult result = ingest.ingest("acme", events, "test:hr");

        assertThat(result.summary().received()).isEqualTo(100);
        assertThat(result.summary().applied()).isEqualTo(97);
        assertThat(result.summary().rejected()).isEqualTo(3);
        assertThat(TestDb.query("SELECT record_index, employee_id, code, field FROM ingest_errors ORDER BY record_index")).containsExactly(
                Map.of("record_index", 40, "employee_id", "E6040", "code", "INVALID_PHONE", "field", "phone"),
                Map.of("record_index", 41, "employee_id", "E6041", "code", "UNKNOWN_OFFICE_CODE", "field", "officeCode"),
                nullable(42));
        assertThat(result.toString()).doesNotContain("12abc");
    }

    private static Map<String, Object> nullable(int index) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("record_index", index);
        row.put("employee_id", null);
        row.put("code", "INVALID_RECORD");
        row.put("field", null);
        return row;
    }

    @Test
    void anHrUpdateNeverOverwritesTransportOwnedFields() {
        Map<String, Object> before = employee("E1001");
        assertThat(before.get("pickup_point")).isNotNull();
        Map<String, Object> data = snapshot("officeCode", "HYD-HT");
        data.put("pickupPoint", "HR thinks it is here");
        data.put("specialAssistance", null);

        IngestResult result = send(event("UPDATE", "E1001", 2, data));

        assertThat(result.results().get(0).warnings()).containsExactly(
                new FieldIssue("FIELD_NOT_OWNED_BY_HR", "pickupPoint"), new FieldIssue("FIELD_NOT_OWNED_BY_HR", "specialAssistance"));
        Map<String, Object> after = employee("E1001");
        assertThat(after.get("office_code")).isEqualTo("HYD-HT");
        assertThat(after.get("pickup_point")).isEqualTo(before.get("pickup_point"));
        assertThat(after.get("special_assistance")).isEqualTo(before.get("special_assistance"));
        assertThat(after.get("transport_version")).isEqualTo(before.get("transport_version"));
    }

    @Test
    void anExitBeforeTheJoinLeavesATombstone_soTheLateJoinIsStale() {
        IngestResult exit = send(event("DEACTIVATE", "E7000", 5));
        IngestResult lateJoin = send(event("CREATE", "E7000", 1, snapshot()));

        assertThat(exit.results().get(0).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(lateJoin.results().get(0).outcome()).isEqualTo(Outcome.STALE);
        assertThat(employee("E7000")).containsEntry("status", "INACTIVE").containsEntry("hr_version", 5L).containsEntry("inactive_reason", "HR_EXIT");
    }

    @Test
    void aNewerCreateAfterAnExitIsARehire() {
        send(event("CREATE", "E5001", 1, snapshot()), event("DEACTIVATE", "E5001", 2), event("CREATE", "E5001", 3, snapshot("officeCode", "BLR-ORR")));

        assertThat(employee("E5001")).containsEntry("status", "ACTIVE").containsEntry("office_code", "BLR-ORR").containsEntry("hr_version", 3L);
        assertThat(TestDb.query("SELECT event_type FROM outbox ORDER BY id")).extracting(r -> r.get("event_type"))
                .containsExactly("EmployeeCreated", "EmployeeDeactivated", "EmployeeReactivated");
    }

    @Test
    void everyAppliedChangeHasExactlyOneOutboxEvent_andEventsCarryNoPersonalData() {
        send(event("CREATE", "E5001", 1, snapshot()), event("UPDATE", "E5001", 2, snapshot("shiftCode", "NIGHT")),
                event("UPDATE", "E5001", 3, snapshot("shiftCode", "NIGHT")));

        List<Map<String, Object>> events = TestDb.query("SELECT event_type, payload::text AS payload FROM outbox ORDER BY id");
        // v3 had identical data: the version advances, nothing observable changed, nothing published.
        assertThat(events).extracting(r -> r.get("event_type")).containsExactly("EmployeeCreated", "EmployeeUpdated");
        assertThat((String) events.get(1).get("payload")).contains("\"hrVersion\": 2").contains("\"changedFields\": [\"shiftCode\"]");
        assertThat(events.toString()).doesNotContainIgnoringCase("asha").doesNotContain("919800000001");
        assertThat(employee("E5001")).containsEntry("hr_version", 3L);
    }

    @Test
    void sameVersionWithDifferentContentIsFlaggedAsAConflictAndNotApplied() {
        send(event("CREATE", "E5001", 4, snapshot()));
        IngestResult result = send(event("UPDATE", "E5001", 4, snapshot("shiftCode", "NIGHT")));

        assertThat(result.results().get(0).outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(result.results().get(0).conflict()).isTrue();
        assertThat(employee("E5001")).containsEntry("shift_code", "S0900");
    }
}
