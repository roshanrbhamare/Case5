package com.rostersync;

import static com.rostersync.support.Fixtures.currentExport;
import static com.rostersync.support.Fixtures.event;
import static com.rostersync.support.Fixtures.snapshot;
import static com.rostersync.support.Fixtures.streamOf;
import static com.rostersync.support.Fixtures.toCsv;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rostersync.messaging.DeactivationConsumer;
import com.rostersync.messaging.OutboxRelay;
import com.rostersync.messaging.ReconcileRunner;
import com.rostersync.service.IngestService;
import com.rostersync.service.ReconcileService;
import com.rostersync.support.Chaos;
import com.rostersync.support.Fixtures.Row;
import com.rostersync.support.IntegrationTest;
import com.rostersync.support.TestDb;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Real crashes: a separate JVM runs the operation and halts at the injection point (exit 137,
 * like kill -9). The test then inspects the database and restarts the work in this JVM.
 */
@IntegrationTest
class CrashIT {

    @Autowired IngestService ingest;
    @Autowired OutboxRelay relay;
    @Autowired DeactivationConsumer consumer;
    @Autowired ReconcileService reconcile;
    @Autowired ReconcileRunner runner;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;

    record ChildResult(int exitCode, String stderr) {}

    static String tail(String text) {
        return text.length() > 3000 ? text.substring(text.length() - 3000) : text;
    }

    @BeforeEach
    void reset() {
        TestDb.reset(Map.of("acme", 40));
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    ChildResult crashIn(String scenario, Map<String, String> env) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), CrashScenario.class.getName(), scenario);
        builder.environment().putAll(env);
        builder.redirectErrorStream(true); // logs and the chaos marker, together
        Process process = builder.start();
        String stderr = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new ChildResult(process.exitValue(), stderr);
    }

    @Test
    void aCrashAfterTheEmployeeWriteButBeforeTheOutboxWriteLeavesNeitherBehind() throws Exception {
        Map<String, Object> transfer = event("UPDATE", "E1001", 2, snapshot("officeCode", "HYD-HT"));
        String officeBefore = (String) TestDb.employee("acme", "E1001").get("office_code");

        ChildResult crash = crashIn("ingest", Map.of("CHAOS_CRASH_AT", Chaos.INGEST_AFTER_EMPLOYEE_WRITE, "EVENT", json.writeValueAsString(transfer)));

        assertThat(crash.exitCode()).as(() -> tail(crash.stderr())).isEqualTo(137);
        assertThat(crash.stderr()).contains("[chaos] crashing at ingest.afterEmployeeWrite");
        assertThat(TestDb.employee("acme", "E1001")).containsEntry("hr_version", 1L).containsEntry("office_code", officeBefore);
        assertThat(TestDb.count("SELECT count(*)::int FROM outbox")).isZero();

        // The HR system retries (it never got a response): applied exactly once.
        var retry = ingest.ingest("acme", List.of(transfer), "test:hr");
        assertThat(retry.results().get(0).outcome().name()).isEqualTo("APPLIED");
        assertThat(TestDb.employee("acme", "E1001")).containsEntry("hr_version", 2L).containsEntry("office_code", "HYD-HT");
        assertThat(TestDb.query("SELECT event_type FROM outbox")).containsExactly(Map.of("event_type", "EmployeeUpdated"));
    }

    @Test
    void aRelayCrashAfterPublishingButBeforeMarkingSentRepublishes_theConsumerStillActsOnce() throws Exception {
        ingest.ingest("acme", List.of(event("DEACTIVATE", "E1003", 2)), "test:hr");
        int seatsBefore = TestDb.count("SELECT coalesce(sum(seats_booked), 0)::int FROM trips WHERE tenant_id = 'acme'");

        ChildResult crash = crashIn("relay", Map.of("CHAOS_CRASH_AT", Chaos.RELAY_AFTER_PUBLISH));

        assertThat(crash.exitCode()).as(() -> tail(crash.stderr())).isEqualTo(137);
        assertThat(redis.opsForStream().size("test.employee-events")).isEqualTo(1);
        assertThat(TestDb.query("SELECT status FROM outbox")).containsExactly(Map.of("status", "PENDING")); // mark-sent never committed

        relay.drain(); // restarted relay publishes again: at-least-once
        assertThat(redis.opsForStream().size("test.employee-events")).isEqualTo(2);
        assertThat(TestDb.query("SELECT status FROM outbox")).containsExactly(Map.of("status", "SENT"));

        consumer.ensureGroup();
        consumer.pollOnce("after-crash");

        assertThat(TestDb.count("SELECT count(*)::int FROM bookings WHERE employee_id = 'E1003' AND status = 'CANCELLED'")).isEqualTo(3);
        assertThat(TestDb.count("SELECT coalesce(sum(seats_booked), 0)::int FROM trips WHERE tenant_id = 'acme'")).isEqualTo(seatsBefore - 3);
        assertThat(consumer.pendingCount()).isZero();
    }

    @Test
    void aNightlyRunKilledMidApplyIsResumedByAnotherWorkerAndConvergesWithoutDoubleApplying() throws Exception {
        List<Row> rows = currentExport("acme");
        List<String> changedIds = rows.subList(0, 10).stream().map(Row::employeeId).toList();
        List<Row> file = new ArrayList<>(rows.stream().map(r -> changedIds.contains(r.employeeId()) ? r.withShift("S2200", 2) : r).toList());
        file.add(new Row("E9999", "Late Joiner", "late@acme.example", "+919822222222", null, "BLR-WF", "S0900", 1));
        var run = reconcile.stageUpload("acme", streamOf(toCsv(file)), "test:hr");

        Map<String, String> env = new HashMap<>();
        env.put("CHAOS_CRASH_AT", Chaos.RECONCILE_MID_APPLY);
        env.put("RUN_ID", run.id().toString());
        ChildResult crash = crashIn("reconcile", env);

        assertThat(crash.exitCode()).as(() -> tail(crash.stderr())).isEqualTo(137);
        assertThat(TestDb.one("SELECT status, lease_owner FROM reconcile_runs WHERE id = ?", run.id()))
                .containsEntry("status", "APPLYING").containsEntry("lease_owner", "doomed-worker");

        // The dead worker's lease runs out; a healthy worker claims the run and finishes it.
        TestDb.exec("UPDATE reconcile_runs SET lease_expires_at = now() - interval '1 second'");
        assertThat(runner.tick("healthy-worker")).isTrue();

        assertThat(TestDb.one("SELECT status FROM reconcile_runs WHERE id = ?", run.id())).containsEntry("status", "COMPLETED");
        assertThat(TestDb.count("SELECT count(*)::int FROM employees WHERE tenant_id = 'acme' AND shift_code = 'S2200' AND hr_version = 2")).isEqualTo(10);
        assertThat(TestDb.employee("acme", "E9999")).containsEntry("status", "ACTIVE");
        // Exactly one event per change across both attempts.
        List<Map<String, Object>> events = TestDb.query("SELECT aggregate_id, count(*)::int AS n FROM outbox GROUP BY aggregate_id");
        assertThat(events).hasSize(11).allSatisfy(e -> assertThat(e).containsEntry("n", 1));
    }
}
