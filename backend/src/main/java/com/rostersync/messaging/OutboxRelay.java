package com.rostersync.messaging;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.rostersync.config.RosterMetrics;
import com.rostersync.config.RosterSyncProperties;
import com.rostersync.messaging.EventPublisher.PermanentPublishException;
import com.rostersync.messaging.EventPublisher.StreamMessage;
import com.rostersync.persistence.OutboxRepository;
import com.rostersync.persistence.OutboxRepository.OutboxRow;
import com.rostersync.persistence.Transactions;
import com.rostersync.support.Chaos;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Moves committed outbox rows to the stream. Delivery is at-least-once: a crash after
 * publishing but before marking the row SENT republishes it, and consumers dedupe by event
 * id. Rows are claimed with SKIP LOCKED, so several relays can run side by side.
 *
 * <p>Two kinds of failure are handled differently:
 * <ul>
 *   <li>broker outage (connection refused, timeout): stop the batch and back off; attempts are
 *       not counted, so a long Redis outage never dead-letters anything. Queue depth and lag
 *       metrics show the backlog instead.</li>
 *   <li>permanent failure (this message can't be published): count the attempt, retry with
 *       backoff, dead-letter after OUTBOX_MAX_ATTEMPTS.</li>
 * </ul>
 */
@Component
public class OutboxRelay {

    public record TickResult(int claimed, int published, boolean outage) {}

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final Transactions tx;
    private final OutboxRepository outbox;
    private final EventPublisher defaultPublisher;
    private final RosterMetrics metrics;
    private final Chaos chaos;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(Transactions tx, OutboxRepository outbox, EventPublisher defaultPublisher, RosterMetrics metrics, Chaos chaos,
                       RosterSyncProperties props) {
        this.tx = tx;
        this.outbox = outbox;
        this.defaultPublisher = defaultPublisher;
        this.metrics = metrics;
        this.chaos = chaos;
        this.batchSize = props.outbox().batchSize();
        this.maxAttempts = props.outbox().maxAttempts();
    }

    public TickResult tick() {
        return tick(defaultPublisher);
    }

    public TickResult tick(EventPublisher publisher) {
        return tx.call(() -> {
            List<OutboxRow> rows = outbox.claimBatch(batchSize);
            List<Long> sent = new ArrayList<>();
            boolean outage = false;

            for (OutboxRow row : rows) {
                try {
                    publisher.publish(new StreamMessage(row.eventId().toString(), row.eventType(), row.tenantId(), row.aggregateId(),
                            row.payloadJson(), row.createdAt().toInstant().toString()));
                    sent.add(row.id());
                    metrics.outboxPublished();
                    chaos.maybeCrash(Chaos.RELAY_AFTER_PUBLISH);
                } catch (PermanentPublishException e) {
                    metrics.outboxPublishFailure();
                    int attempts = row.attempts() + 1;
                    String reason = truncate(e.getMessage());
                    if (attempts >= maxAttempts) {
                        outbox.moveToDeadLetter(row, reason);
                        metrics.deadLettered();
                        log.error("Outbox event dead-lettered {} {} {}", kv("eventId", row.eventId()), kv("eventType", row.eventType()), kv("attempts", attempts));
                    } else {
                        outbox.scheduleRetry(row.id(), reason, retryDelayMs(attempts));
                    }
                } catch (RuntimeException e) {
                    metrics.outboxPublishFailure();
                    outage = true;
                    log.warn("Event stream unavailable; outbox will retry {} {}", kv("eventId", row.eventId()), kv("error", e.getClass().getSimpleName()));
                    break;
                }
            }

            if (!sent.isEmpty()) outbox.markSent(sent);
            return new TickResult(rows.size(), sent.size(), outage);
        });
    }

    /** Publishes until the outbox is empty or the broker is down. Used by tests and the worker loop. */
    public int drain(EventPublisher publisher) {
        int published = 0;
        for (int i = 0; i < 10_000; i++) {
            TickResult result = tick(publisher);
            published += result.published();
            if (result.claimed() == 0 || result.outage()) break;
        }
        return published;
    }

    public int drain() {
        return drain(defaultPublisher);
    }

    private static long retryDelayMs(int attempt) {
        return Math.min(60_000, 500L * (1L << (attempt - 1))) + ThreadLocalRandom.current().nextLong(250);
    }

    private static String truncate(String message) {
        String m = message == null ? "unknown" : message;
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
