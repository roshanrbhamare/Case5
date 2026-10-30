package com.rostersync.messaging;

import com.rostersync.config.RosterSyncProperties;
import com.rostersync.persistence.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Background work, three independent loops on a 3-thread scheduler: the outbox relay, the
 * booking consumer and the nightly runner. Also registers the queue-depth and lag gauges.
 * Switched off with rostersync.worker.enabled=false (tests drive these components directly).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "rostersync.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkerScheduler.class);

    private final OutboxRelay relay;
    private final DeactivationConsumer consumer;
    private final ReconcileRunner runner;
    private final OutboxRepository outbox;
    private final StreamStats streamStats;
    private final MeterRegistry registry;
    private final String owner = ManagementFactory.getRuntimeMXBean().getName();

    private volatile boolean groupReady;
    private volatile long relayPausedUntil;
    private volatile long relayBackoffMs;
    private volatile long lastReclaim;
    private final long pollMs;

    public WorkerScheduler(OutboxRelay relay, DeactivationConsumer consumer, ReconcileRunner runner, OutboxRepository outbox, StreamStats streamStats,
                           MeterRegistry registry, RosterSyncProperties props) {
        this.relay = relay;
        this.consumer = consumer;
        this.runner = runner;
        this.outbox = outbox;
        this.streamStats = streamStats;
        this.registry = registry;
        this.pollMs = props.outbox().pollMs();
        this.relayBackoffMs = pollMs;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("rostersync.outbox.pending", outbox, o -> o.stats(null).pending()).description("Outbox events waiting to be published").register(registry);
        Gauge.builder("rostersync.outbox.lag.seconds", outbox, o -> o.stats(null).oldestPendingSeconds())
                .description("Age of the oldest unpublished outbox event").register(registry);
        Gauge.builder("rostersync.outbox.dead", outbox, o -> o.stats(null).dead()).description("Outbox events in the dead-letter state").register(registry);
        Gauge.builder("rostersync.consumer.pending", streamStats, s -> {
            StreamStats.Snapshot snapshot = s.snapshot();
            return snapshot == null ? 0 : snapshot.pending();
        }).description("Stream messages delivered but not yet acknowledged").register(registry);
        log.info("Worker started: outbox relay, deactivation consumer, nightly runner");
    }

    @Scheduled(fixedDelayString = "${rostersync.outbox.poll-ms}")
    void relayOutbox() {
        if (System.currentTimeMillis() < relayPausedUntil) return;
        try {
            OutboxRelay.TickResult result;
            do {
                result = relay.tick();
            } while (!result.outage() && result.claimed() > 0);
            if (result.outage()) pauseRelay();
            else relayBackoffMs = pollMs;
        } catch (RuntimeException e) {
            log.error("Outbox relay tick failed: {}", e.getClass().getSimpleName());
            pauseRelay();
        }
    }

    @Scheduled(fixedDelay = 200)
    void consumeStream() {
        try {
            if (!groupReady) {
                consumer.ensureGroup();
                groupReady = true;
            }
            if (System.currentTimeMillis() - lastReclaim > consumer.reclaimIdle().toMillis() / 2) {
                lastReclaim = System.currentTimeMillis();
                consumer.reclaim(owner, consumer.reclaimIdle());
            }
            consumer.pollOnce(owner);
        } catch (RuntimeException e) {
            groupReady = false;
            log.warn("Consumer poll failed; retrying: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${rostersync.reconcile.poll-ms}")
    void runNightly() {
        try {
            while (runner.tick(owner)) {
                // keep going while there is work
            }
        } catch (RuntimeException e) {
            log.error("Reconcile runner tick failed: {}", e.getClass().getSimpleName());
        }
    }

    private void pauseRelay() {
        relayBackoffMs = Math.min(relayBackoffMs * 2, 30_000);
        relayPausedUntil = System.currentTimeMillis() + relayBackoffMs;
    }
}
