package com.rostersync;

import static com.rostersync.support.Fixtures.currentExport;
import static com.rostersync.support.Fixtures.event;
import static com.rostersync.support.Fixtures.snapshot;
import static com.rostersync.support.Fixtures.streamOf;
import static com.rostersync.support.Fixtures.toCsv;
import static org.assertj.core.api.Assertions.assertThat;

import com.rostersync.domain.TransportPrefs;
import com.rostersync.service.EmployeeWriter.Outcome;
import com.rostersync.service.IngestService;
import com.rostersync.service.ReconcileService;
import com.rostersync.service.TransportService;
import com.rostersync.support.AppException;
import com.rostersync.support.Fixtures.Row;
import com.rostersync.support.IntegrationTest;
import com.rostersync.support.TestDb;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ConcurrencyIT {

    @Autowired IngestService ingest;
    @Autowired ReconcileService reconcile;
    @Autowired TransportService transport;

    ExecutorService pool = Executors.newFixedThreadPool(25);

    @BeforeEach
    void reset() {
        TestDb.reset(Map.of("acme", 10));
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    Outcome ingestOne(Map<String, Object> e) {
        return ingest.ingest("acme", List.of(e), "test:hr").results().get(0).outcome();
    }

    <T> List<T> all(List<Callable<T>> tasks) throws Exception {
        List<T> out = new ArrayList<>();
        for (Future<T> f : pool.invokeAll(tasks)) out.add(f.get());
        return out;
    }

    static String shiftFor(long v) {
        return String.format("S%04d", v);
    }

    @Test
    void fiftyVersionsOfOneEmployeeDeliveredConcurrentlyInRandomOrderConvergeOnTheNewest() throws Exception {
        List<Integer> versions = new ArrayList<>(IntStream.rangeClosed(1, 50).boxed().toList());
        Collections.shuffle(versions);

        List<Outcome> outcomes = all(versions.stream()
                .<Callable<Outcome>>map(v -> () -> ingestOne(event("UPDATE", "E9100", v, snapshot("shiftCode", shiftFor(v)))))
                .toList());

        assertThat(outcomes).doesNotContain(Outcome.REJECTED);
        assertThat(TestDb.employee("acme", "E9100")).containsEntry("hr_version", 50L).containsEntry("shift_code", shiftFor(50));
        long applied = outcomes.stream().filter(o -> o == Outcome.APPLIED).count();
        assertThat(TestDb.count("SELECT count(*)::int FROM outbox WHERE aggregate_id = 'E9100'")).isEqualTo((int) applied);
    }

    @Test
    void theSameEventDelivered20TimesAtOnceIsAppliedExactlyOnce() throws Exception {
        Map<String, Object> e = event("UPDATE", "E1001", 2, snapshot("officeCode", "HYD-HT"));

        List<Outcome> outcomes = all(IntStream.range(0, 20).<Callable<Outcome>>mapToObj(i -> () -> ingestOne(e)).toList());

        assertThat(outcomes.stream().filter(o -> o == Outcome.APPLIED)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.DUPLICATE)).hasSize(19);
        assertThat(TestDb.count("SELECT count(*)::int FROM outbox WHERE aggregate_id = 'E1001'")).isEqualTo(1);
    }

    @Test
    void hrAndTransportAdminEditsRacingOnTheSameEmployeeBothSurvive() throws Exception {
        long transportVersion = (Long) TestDb.employee("acme", "E1002").get("transport_version");

        all(List.<Callable<Object>>of(
                () -> ingestOne(event("UPDATE", "E1002", 2, snapshot("officeCode", "PNQ-HJ"))),
                () -> transport.update("acme", "E1002", new TransportPrefs.Patch(true, "Gate 9, Embassy Golf Links", false, null), transportVersion)));

        assertThat(TestDb.employee("acme", "E1002")).containsEntry("office_code", "PNQ-HJ").containsEntry("pickup_point", "Gate 9, Embassy Golf Links");
    }

    @Test
    void liveEventsArrivingWhileANightlyRunAppliesAreNeverLostOrOverwrittenByOlderFileData() throws Exception {
        // The file brings every employee to v2; live v5 updates race with it.
        List<Row> rows = currentExport("acme").stream().map(r -> r.withShift("S1400", 2)).toList();
        var run = reconcile.stageUpload("acme", streamOf(toCsv(rows)), "test:hr");

        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> reconcile.processRun("acme", run.id(), "inline"));
        for (Row r : rows) tasks.add(() -> ingestOne(event("UPDATE", r.employeeId(), 5, snapshot("shiftCode", "NIGHT", "email", r.employeeId() + "@acme.example"))));
        all(tasks);

        assertThat(TestDb.query("SELECT hr_version, shift_code FROM employees WHERE tenant_id = 'acme' AND status = 'ACTIVE'"))
                .allSatisfy(r -> assertThat(r).containsEntry("hr_version", 5L).containsEntry("shift_code", "NIGHT"));
    }

    @Test
    void twoUploadsAtTheSameMoment_exactlyOneIsAccepted() throws Exception {
        String csv = toCsv(currentExport("acme"));
        List<Future<Object>> attempts = pool.invokeAll(List.<Callable<Object>>of(
                () -> reconcile.stageUpload("acme", streamOf(csv), "test:hr"),
                () -> reconcile.stageUpload("acme", streamOf(csv), "test:hr")));

        int accepted = 0;
        String rejectedCode = null;
        for (Future<Object> f : attempts) {
            try {
                f.get();
                accepted++;
            } catch (java.util.concurrent.ExecutionException e) {
                rejectedCode = ((AppException) e.getCause()).code();
            }
        }
        assertThat(accepted).isEqualTo(1);
        assertThat(rejectedCode).isEqualTo("RECONCILE_IN_PROGRESS");
    }
}
