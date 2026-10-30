package com.rostersync.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * The counters an on-call engineer needs at 3 AM: event outcomes, rejections, retries,
 * outbox publishing, dead letters, consumer results, nightly runs and brake trips.
 * Exposed by Micrometer at /metrics as rostersync_* series.
 */
@Component
public class RosterMetrics {

    private final MeterRegistry registry;

    public RosterMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public MeterRegistry registry() {
        return registry;
    }

    public void event(String source, String outcome) {
        Counter.builder("rostersync.events").description("HR changes processed, by source and outcome")
                .tags("source", source, "outcome", outcome).register(registry).increment();
    }

    public void events(String source, String outcome, double amount) {
        Counter.builder("rostersync.events").tags("source", source, "outcome", outcome).register(registry).increment(amount);
    }

    public void rejection(String code) {
        Counter.builder("rostersync.rejections").description("Records rejected at the boundary, by error code")
                .tag("code", code).register(registry).increment();
    }

    public void versionConflict() {
        registry.counter("rostersync.version.conflicts").increment();
    }

    public void retry(String operation) {
        Counter.builder("rostersync.retries").description("Transient failures retried").tag("operation", operation).register(registry).increment();
    }

    public Timer ingestTimer() {
        return Timer.builder("rostersync.ingest.record.duration").description("Time to apply one HR event")
                .publishPercentileHistogram().register(registry);
    }

    public void outboxPublished() {
        registry.counter("rostersync.outbox.published").increment();
    }

    public void outboxPublishFailure() {
        registry.counter("rostersync.outbox.publish.failures").increment();
    }

    public void deadLettered() {
        registry.counter("rostersync.outbox.dead.lettered").increment();
    }

    public void consumer(String consumer, String result) {
        Counter.builder("rostersync.consumer.messages").tags("consumer", consumer, "result", result).register(registry).increment();
    }

    public void reconcileRun(String status) {
        Counter.builder("rostersync.reconcile.runs").tag("status", status).register(registry).increment();
    }

    public void safetyBrakeTrip() {
        registry.counter("rostersync.safety.brake.trips").increment();
    }

    public Timer reconcilePhase(String phase) {
        return Timer.builder("rostersync.reconcile.phase.duration").tag("phase", phase).publishPercentileHistogram().register(registry);
    }

    public void cache(String cache, String result) {
        Counter.builder("rostersync.cache.requests").tags("cache", cache, "result", result).register(registry).increment();
    }

    public void signInCode(String result) {
        Counter.builder("rostersync.sign.in.codes").description("Employee sign-in codes, by result").tag("result", result).register(registry).increment();
    }
}
