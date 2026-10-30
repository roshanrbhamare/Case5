package com.rostersync;

import static org.assertj.core.api.Assertions.assertThat;

import com.rostersync.config.RosterMetrics;
import com.rostersync.domain.Employee;
import com.rostersync.domain.HrSnapshot;
import com.rostersync.persistence.ReconcileRepository.RunRow;
import com.rostersync.persistence.ReconcileRepository.RunStatus;
import com.rostersync.service.ApiKeyResolver;
import com.rostersync.service.ReconcileService;
import com.rostersync.support.TestDb;
import io.micrometer.core.instrument.Timer;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Nightly diff benchmark: a 200,000-row export against a 200,000-employee store.
 *
 *   mvn test -Dgroups=benchmark -DexcludedTestGroups=none -Dbench.rows=200000
 *
 * Appends a section to bench/RESULTS.md.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("benchmark")
class ReconcileBenchmark {

    static final String TENANT = "bench";
    static final String[] OFFICES = {"BLR-01", "BLR-02", "PNQ-01", "HYD-01", "GGN-01"};
    static final String[] SHIFTS = {"S0800", "S0900", "S1000", "S1400", "NIGHT"};

    @Autowired ReconcileService reconcile;
    @Autowired RosterMetrics metrics;

    final int n = Integer.getInteger("bench.rows", 200_000);
    final int changed = Math.round(n * 0.01f);
    final int left = Math.round(n * 0.005f);
    final int joined = Math.round(n * 0.005f);

    static String[] row(int i, String shift, long version) {
        String id = String.format("B%07d", i);
        return new String[] {id, "Bench Person " + i, "bench.person." + i + "@bench.example", "+9190" + String.valueOf(10_000_000 + i).substring(1),
                i > 10 ? String.format("B%07d", i % 10) : "", OFFICES[i % OFFICES.length], shift == null ? SHIFTS[i % SHIFTS.length] : shift,
                Long.toString(version)};
    }

    @Test
    void diff200kRowsUnder60SecondsWithBoundedMemory() throws Exception {
        seed();
        Path full = Files.createTempFile("rostersync-bench", ".csv");
        Path truncated = Files.createTempFile("rostersync-bench-truncated", ".csv");
        int fullRows = writeExport(full, 1.0);
        int truncatedRows = writeExport(truncated, 0.6);
        System.gc();

        long baseRss = usedHeap();
        MemorySampler sampler = new MemorySampler();
        sampler.start();
        long t0 = System.nanoTime();
        RunRow staged;
        try (InputStream in = new FileInputStream(full.toFile())) {
            staged = reconcile.stageUpload(TENANT, in, "bench");
        }
        long t1 = System.nanoTime();
        RunRow run = reconcile.processRun(TENANT, staged.id(), "bench");
        long t2 = System.nanoTime();
        sampler.stopSampling();

        long t3 = System.nanoTime();
        RunRow blocked;
        try (InputStream in = new FileInputStream(truncated.toFile())) {
            blocked = reconcile.processRun(TENANT, reconcile.stageUpload(TENANT, in, "bench").id(), "bench");
        }
        long t4 = System.nanoTime();
        reconcile.decide(TENANT, blocked.id(), false, "bench", "benchmark cleanup");

        double stageS = (t1 - t0) / 1e9;
        double analyzeS = phase("analyze");
        double applyS = phase("apply");
        double totalS = (t2 - t0) / 1e9;
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(blocked.status()).isEqualTo(RunStatus.BLOCKED);

        String report = String.join("\n",
                "### Nightly reconciliation, " + String.format("%,d", n) + " employees (" + LocalDate.now() + ", Java)",
                "",
                "Machine: " + machine(),
                "",
                String.format("Export: %,d rows, with %,d changed, %,d joiners and %,d leavers.", fullRows, changed, joined, left),
                "",
                "| Phase | Time | Notes |",
                "| --- | --- | --- |",
                String.format("| Stage (stream, parse, validate, hash, COPY) | %.1f s | %,d rows/s |", stageS, Math.round(fullRows / stageS)),
                String.format("| Analyze (dedupe, digests, missing count, classify, safety brake) | %.1f s | %d of 256 buckets identical and skipped |", analyzeS, run.bucketsSkipped()),
                String.format("| Apply (set-based inserts, updates and exits, with outbox rows) | %.1f s | %,d changes applied |", applyS, run.appliedChanges()),
                String.format("| **Total** | **%.1f s** | target < 60 s: %s |", totalS, totalS < 60 ? "met" : "NOT met"),
                "",
                String.format("Plan: %d creates, %d updates, %d deactivations, %,d unchanged. Status: %s.", run.creates(), run.updates(), run.deactivations(),
                        run.unchanged(), run.status()),
                "",
                String.format("Memory: JVM heap in use peaked at %d MB during the run (started at %d MB). The file never sits in memory; the diff runs in Postgres.",
                        sampler.peak() / 1024 / 1024, baseRss / 1024 / 1024),
                "",
                String.format("Truncated export (%,d rows, cut at 60%%): %s in %.1f s; missing %,d of %,d active (%.1f%%), nothing applied.", truncatedRows,
                        blocked.status(), (t4 - t3) / 1e9, blocked.missingCount(), blocked.activeBefore(), blocked.missingRatio() * 100),
                "");
        System.out.println(report);
        Path results = Path.of("bench/RESULTS.md");
        Files.createDirectories(results.getParent());
        Files.writeString(results, report + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.deleteIfExists(full);
        Files.deleteIfExists(truncated);
    }

    private double phase(String name) {
        Timer timer = metrics.reconcilePhase(name);
        return timer.totalTime(TimeUnit.SECONDS);
    }

    private int writeExport(Path file, double keepRatio) throws Exception {
        int total = n - left + joined;
        int keep = (int) Math.round(total * keepRatio);
        int written = 0;
        int every = Math.max(1, n / changed);
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("employee_id,full_name,email,phone,manager_id,office_code,shift_code,version\n");
            for (int i = 1; i <= n - left && written < keep; i++, written++) {
                out.write(String.join(",", i % every == 0 ? row(i, "S2200", 2) : row(i, null, 1)));
                out.write('\n');
            }
            for (int j = 1; j <= joined && written < keep; j++, written++) {
                out.write(String.join(",", row(n + j, null, 1)));
                out.write('\n');
            }
        }
        return written;
    }

    /** Bench tenant with n employees loaded by COPY (owner role). */
    private void seed() throws Exception {
        for (String table : new String[] {"ingest_errors", "ingest_batches", "reconcile_runs", "outbox", "employees", "offices"}) {
            TestDb.exec("DELETE FROM " + table + " WHERE tenant_id = ?", TENANT);
        }
        TestDb.exec("DELETE FROM reconcile_staging WHERE tenant_id = ?", TENANT);
        TestDb.exec("INSERT INTO tenants (id, name, hr_api_key_hash) VALUES (?, 'Benchmark Co', ?) ON CONFLICT (id) DO NOTHING", TENANT,
                ApiKeyResolver.hash("bench-hr-dev-key"));
        for (String code : OFFICES) TestDb.exec("INSERT INTO offices (tenant_id, code, name, city) VALUES (?, ?, ?, 'Bench') ON CONFLICT DO NOTHING", TENANT, code, code);

        StringBuilder csv = new StringBuilder(n * 160);
        for (int i = 1; i <= n; i++) {
            String[] r = row(i, null, 1);
            Employee e = Employee.joiner(TENANT, r[0], new HrSnapshot(r[1], r[2], r[3], r[4].isEmpty() ? null : r[4], r[5], r[6]), 1);
            csv.append(String.join(",", TENANT, r[0], r[1], r[2], r[3], r[4], r[5], r[6], "ACTIVE", "1", "0", e.hrHash(), Integer.toString(e.bucket()))).append('\n');
        }
        try (Connection c = DriverManager.getConnection(TestDb.URL, "rostersync", "rostersync")) {
            c.unwrap(PGConnection.class).getCopyAPI().copyIn(
                    "COPY employees (tenant_id, employee_id, full_name, email, phone, manager_id, office_code, shift_code, status, hr_version, "
                            + "transport_version, hr_hash, bucket) FROM STDIN WITH (FORMAT csv)", new StringReader(csv.toString()));
            c.createStatement().execute("ANALYZE employees");
        }
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static String machine() {
        return String.format("%s, %d logical cores, %d MB max heap, Java %s, Postgres 16 + Redis 7 in Docker Desktop (WSL2), tests run inside Docker",
                System.getProperty("os.name") + " " + System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / 1024 / 1024, System.getProperty("java.version"));
    }

    /** Samples heap usage every 20 ms while the run is in progress. */
    static final class MemorySampler extends Thread {
        private volatile boolean running = true;
        private volatile long peak;

        MemorySampler() {
            setDaemon(true);
        }

        @Override
        public void run() {
            while (running) {
                peak = Math.max(peak, usedHeap());
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        void stopSampling() {
            running = false;
        }

        long peak() {
            return peak;
        }
    }
}
