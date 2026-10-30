package com.rostersync;

import static com.rostersync.support.Fixtures.currentExport;
import static com.rostersync.support.Fixtures.event;
import static com.rostersync.support.Fixtures.snapshot;
import static com.rostersync.support.Fixtures.streamOf;
import static com.rostersync.support.Fixtures.toCsv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rostersync.persistence.ReconcileRepository.RunRow;
import com.rostersync.persistence.ReconcileRepository.RunStatus;
import com.rostersync.service.IngestService;
import com.rostersync.service.ReconcileService;
import com.rostersync.support.AppException;
import com.rostersync.support.Fixtures.Row;
import com.rostersync.support.IntegrationTest;
import com.rostersync.support.TestDb;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ReconcileIT {

    @Autowired ReconcileService reconcile;
    @Autowired IngestService ingest;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        TestDb.reset(Map.of("acme", 40));
    }

    RunRow stage(String csv) {
        return reconcile.stageUpload("acme", streamOf(csv), "test:hr");
    }

    RunRow runFile(String csv) {
        return reconcile.processRun("acme", stage(csv).id(), "inline");
    }

    Map<String, Object> employee(String id) {
        return TestDb.employee("acme", id);
    }

    int activeCount() {
        return TestDb.count("SELECT count(*)::int FROM employees WHERE tenant_id = 'acme' AND status = 'ACTIVE'");
    }

    @Test
    void anExportIdenticalToTheStoreChangesNothingAndSkipsEveryBucket() {
        RunRow run = runFile(toCsv(currentExport("acme")));

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.appliedChanges()).isZero();
        assertThat(run.missingCount()).isZero();
        assertThat(run.bucketsSkipped()).isEqualTo(256);
        assertThat(run.unchanged()).isEqualTo(40);
        assertThat(TestDb.count("SELECT count(*)::int FROM outbox")).isZero();
    }

    @Test
    void appliesJoinersChangesAndLeaversFoundInTheExport() {
        List<Row> rows = new ArrayList<>();
        for (Row r : currentExport("acme")) {
            if (r.employeeId().equals("E1010")) continue;
            rows.add(r.employeeId().equals("E1005") ? r.withShift("S2200", 2) : r);
        }
        rows.add(new Row("E9001", "New Joiner", "new.joiner@acme.example", "+919811111111", "E1001", "PNQ-HJ", "S0900", 1));

        RunRow run = runFile(toCsv(rows));

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.creates()).isEqualTo(1);
        assertThat(run.updates()).isEqualTo(1);
        assertThat(run.deactivations()).isEqualTo(1);
        assertThat(run.appliedChanges()).isEqualTo(3);
        assertThat(employee("E1005")).containsEntry("shift_code", "S2200").containsEntry("hr_version", 2L);
        assertThat(employee("E9001")).containsEntry("status", "ACTIVE").containsEntry("office_code", "PNQ-HJ");
        assertThat(employee("E1010")).containsEntry("status", "INACTIVE").containsEntry("inactive_reason", "MISSING_FROM_EXPORT");
        assertThat(TestDb.query("SELECT aggregate_id, event_type FROM outbox ORDER BY aggregate_id")).containsExactly(
                Map.of("aggregate_id", "E1005", "event_type", "EmployeeUpdated"),
                Map.of("aggregate_id", "E1010", "event_type", "EmployeeDeactivated"),
                Map.of("aggregate_id", "E9001", "event_type", "EmployeeCreated"));
    }

    @Test
    void aTruncatedExportTripsTheSafetyBrake_nobodyIsDeactivated_andTheOperatorSeesTheGap() throws Exception {
        RunRow run = runFile(toCsv(currentExport("acme").subList(0, 24))); // 40% of active employees missing

        assertThat(run.status()).isEqualTo(RunStatus.BLOCKED);
        assertThat(run.activeBefore()).isEqualTo(40);
        assertThat(run.missingCount()).isEqualTo(16);
        assertThat(run.missingRatio()).isEqualTo(0.4);
        assertThat(run.appliedChanges()).isNull();
        assertThat(activeCount()).isEqualTo(40);
        List<Map<String, Object>> events = TestDb.query("SELECT event_type, payload::text AS payload FROM outbox");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("event_type")).isEqualTo("ReconcileRunBlocked");
        JsonNode payload = json.readTree((String) events.get(0).get("payload"));
        assertThat(payload.get("missingCount").asInt()).isEqualTo(16);
        assertThat(payload.get("activeCount").asInt()).isEqualTo(40);
    }

    @Test
    void operatorApprovalAppliesABlockedRun() {
        RunRow blocked = runFile(toCsv(currentExport("acme").subList(0, 24)));

        reconcile.decide("acme", blocked.id(), true, "user:operator", "Confirmed with HR: a business unit was divested.");
        RunRow run = reconcile.processRun("acme", blocked.id(), "inline");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.appliedChanges()).isEqualTo(16);
        assertThat(run.decidedBy()).isEqualTo("user:operator");
        assertThat(activeCount()).isEqualTo(24);
    }

    @Test
    void operatorRejectionDiscardsABlockedRunAndFreesTheSlot() {
        RunRow blocked = runFile(toCsv(currentExport("acme").subList(0, 24)));

        RunRow rejected = reconcile.decide("acme", blocked.id(), false, "user:operator", "Broken export, HR will resend.");

        assertThat(rejected.status()).isEqualTo(RunStatus.REJECTED);
        assertThat(activeCount()).isEqualTo(40);
        assertThat(TestDb.count("SELECT count(*)::int FROM reconcile_staging")).isZero();
        assertThat(runFile(toCsv(currentExport("acme"))).status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void aDecisionOnARunThatIsNotBlockedIsRefused() {
        RunRow done = runFile(toCsv(currentExport("acme")));
        assertThatThrownBy(() -> reconcile.decide("acme", done.id(), true, "user:operator", "approve anyway"))
                .isInstanceOf(AppException.ConflictException.class).hasMessageContaining("not waiting for a decision");
    }

    @Test
    void theFileNeverUndoesANewerLiveUpdate() {
        List<Row> exportBeforeLiveUpdate = currentExport("acme");
        ingest.ingest("acme", List.of(event("UPDATE", "E1003", 5, snapshot("officeCode", "HYD-HT"))), "test:hr");

        RunRow run = runFile(toCsv(exportBeforeLiveUpdate));

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.stale()).isEqualTo(1);
        assertThat(run.appliedChanges()).isZero();
        assertThat(employee("E1003")).containsEntry("office_code", "HYD-HT").containsEntry("hr_version", 5L);
    }

    @Test
    void setBasedApplyPublishesTheSameEventsAsTheDomainPath() throws Exception {
        List<Row> rows = currentExport("acme");
        Row e5 = rows.stream().filter(r -> r.employeeId().equals("E1005")).findFirst().orElseThrow();
        Map<String, Object> data = snapshot("fullName", e5.fullName(), "email", e5.email(), "phone", e5.phone(), "managerId", e5.managerId(),
                "officeCode", "HYD-HT", "shiftCode", "S2200");

        // Per-record domain path (live feed): one transfer, one exit.
        ingest.ingest("acme", List.of(event("UPDATE", "E1005", 2, data), event("DEACTIVATE", "E1010", 2)), "test:hr");
        List<JsonNode> viaDomain = payloads();

        // Set-based SQL path (nightly file): the same transfer, and E1010 left out of the file.
        TestDb.reset(Map.of("acme", 40));
        List<Row> file = new ArrayList<>();
        for (Row r : rows) {
            if (r.employeeId().equals("E1010")) continue;
            file.add(r.employeeId().equals("E1005") ? r.withOffice("HYD-HT", "S2200", 2) : r);
        }
        runFile(toCsv(file));
        List<JsonNode> viaSql = payloads();

        assertThat(viaSql.get(0)).isEqualTo(viaDomain.get(0)); // identical update event
        // Exits differ only by reason and version (no version comes with "missing from file").
        assertThat(fieldNames(viaSql.get(1))).isEqualTo(fieldNames(viaDomain.get(1)));
        assertThat(viaSql.get(1).get("payload").get("inactiveReason").asText()).isEqualTo("MISSING_FROM_EXPORT");
    }

    private List<JsonNode> payloads() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (Map<String, Object> r : TestDb.query("SELECT event_type, payload::text AS payload FROM outbox ORDER BY aggregate_id")) {
            var node = json.createObjectNode();
            node.put("event_type", (String) r.get("event_type"));
            var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree((String) r.get("payload"));
            payload.remove("source");
            node.set("payload", payload);
            out.add(node);
        }
        return out;
    }

    private static List<String> fieldNames(JsonNode event) {
        List<String> names = new ArrayList<>();
        event.get("payload").fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        return names;
    }

    @Test
    void aNewerVersionWithIdenticalContentAdvancesTheStoredVersionWithoutPublishing() {
        List<Row> rows = currentExport("acme").stream().map(r -> r.employeeId().equals("E1007") ? r.withVersion(4) : r).toList();

        RunRow run = runFile(toCsv(rows));

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.updates()).isEqualTo(1);
        assertThat(employee("E1007")).containsEntry("hr_version", 4L);
        assertThat(TestDb.count("SELECT count(*)::int FROM outbox")).isZero();
        var late = ingest.ingest("acme", List.of(event("UPDATE", "E1007", 3, snapshot())), "test:hr");
        assertThat(late.results().get(0).outcome().name()).isEqualTo("STALE");
    }

    @Test
    void badRowsAreReportedWithoutFailingTheFile_duplicatesKeepTheHighestVersion() {
        List<Row> rows = new ArrayList<>(currentExport("acme"));
        rows.set(3, rows.get(3).withPhone("not-a-phone"));
        rows.add(rows.get(5).withShift("NIGHT", 3)); // same employee twice, newer copy wins
        if (rows.get(5).shiftCode().equals("NIGHT")) rows.set(rows.size() - 1, rows.get(5).withShift("S2200", 3));
        String expectedShift = rows.get(rows.size() - 1).shiftCode();

        RunRow run = runFile(toCsv(rows));

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.rejectedRows()).isEqualTo(1);
        assertThat(run.duplicateRows()).isEqualTo(1);
        assertThat(run.updates()).isEqualTo(1);
        assertThat(employee(rows.get(5).employeeId())).containsEntry("shift_code", expectedShift).containsEntry("hr_version", 3L);
        assertThat(TestDb.query("SELECT code, field FROM ingest_errors ORDER BY code")).containsExactly(
                Map.of("code", "DUPLICATE_IN_FILE", "field", "employeeId"),
                Map.of("code", "INVALID_PHONE", "field", "phone"));
    }

    @Test
    void aFileCutOffMidRowRejectsThePartialRowAndStillBrakes() {
        String csv = toCsv(currentExport("acme").subList(0, 20));
        RunRow run = runFile(csv.substring(0, csv.length() - 30)); // last row loses its tail

        assertThat(run.status()).isEqualTo(RunStatus.BLOCKED);
        assertThat(run.rejectedRows()).isEqualTo(1);
    }

    @Test
    void aFileWithMissingColumnsIsRefusedAndDoesNotBlockTheNextUpload() {
        assertThatThrownBy(() -> stage("employee_id,full_name\nE1,Someone\n"))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.code()).isEqualTo("INVALID_FILE_HEADER"));
        assertThat(TestDb.one("SELECT status, failure_code FROM reconcile_runs")).containsEntry("status", "FAILED").containsEntry("failure_code", "INVALID_FILE_HEADER");
        assertThat(runFile(toCsv(currentExport("acme"))).status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void onlyOneOpenRunPerTenant() {
        stage(toCsv(currentExport("acme")));
        assertThatThrownBy(() -> stage(toCsv(currentExport("acme"))))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.code()).isEqualTo("RECONCILE_IN_PROGRESS"));
    }

    @Test
    void theBrakeThresholdIsConfigurable() {
        // Exercised end to end in ThresholdIT (needs its own application context); here the default applies.
        assertThat(runFile(toCsv(currentExport("acme").subList(0, 24))).threshold()).isEqualTo(0.3);
    }

    @Test
    void aFirstTimeLoadOfAnEmptyTenantDoesNotTripTheBrake() {
        TestDb.reset();
        List<Row> rows = IntStream.range(0, 500)
                .mapToObj(i -> new Row("N" + i, "Person " + i, "p" + i + "@acme.example", "+9198" + (10_000_000 + i), null, "BLR-WF", "S0900", 1))
                .toList();

        RunRow run = runFile(toCsv(rows));

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.creates()).isEqualTo(500);
        assertThat(run.appliedChanges()).isEqualTo(500);
        assertThat(TestDb.count("SELECT count(*)::int FROM outbox WHERE event_type = 'EmployeeCreated'")).isEqualTo(500);
    }
}
