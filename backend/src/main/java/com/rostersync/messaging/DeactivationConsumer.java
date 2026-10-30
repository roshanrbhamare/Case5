package com.rostersync.messaging;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rostersync.config.RosterMetrics;
import com.rostersync.config.RosterSyncProperties;
import com.rostersync.domain.Change;
import com.rostersync.domain.Fields.Status;
import com.rostersync.persistence.BookingRepository;
import com.rostersync.persistence.BookingRepository.CancelResult;
import com.rostersync.persistence.DirectoryRepository;
import com.rostersync.persistence.EmployeeRepository;
import com.rostersync.persistence.Transactions;
import com.rostersync.support.Retry;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Side effects of an exit: cancel future cab bookings and free the seats.
 *
 * <p>Exactly-once effect on top of at-least-once delivery: the event id is recorded in
 * consumed_events in the same transaction as the cancellation, so a redelivered message finds
 * its id already there and does nothing. A message is ACKed only after that transaction
 * commits; a crash before the ACK leaves it pending, and {@link #reclaim} hands it to a live
 * consumer after the reclaim interval.
 */
@Component
public class DeactivationConsumer {

    public static final String NAME = "booking-deactivation";

    /** One parsed stream entry. */
    public record Message(String eventId, String type, String tenantId, JsonNode payload) {}

    public enum Outcome { IGNORED, DUPLICATE, SKIPPED_REACTIVATED, CANCELLED, NOTHING_TO_CANCEL }

    private static final Logger log = LoggerFactory.getLogger(DeactivationConsumer.class);

    private final Transactions tx;
    private final StringRedisTemplate redis;
    private final BookingRepository bookings;
    private final DirectoryRepository directory;
    private final EmployeeRepository employees;
    private final RosterMetrics metrics;
    private final ObjectMapper json;
    private final String stream;
    private final String group;
    private final Duration reclaimIdle;
    private final String defaultConsumer = ManagementFactory.getRuntimeMXBean().getName();

    public DeactivationConsumer(Transactions tx, StringRedisTemplate redis, BookingRepository bookings, DirectoryRepository directory,
                                EmployeeRepository employees, RosterMetrics metrics, ObjectMapper json, RosterSyncProperties props) {
        this.tx = tx;
        this.redis = redis;
        this.bookings = bookings;
        this.directory = directory;
        this.employees = employees;
        this.metrics = metrics;
        this.json = json;
        this.stream = props.stream().name();
        this.group = props.stream().consumerGroup();
        this.reclaimIdle = Duration.ofMillis(props.stream().reclaimMs());
    }

    public String defaultConsumer() {
        return defaultConsumer;
    }

    public Duration reclaimIdle() {
        return reclaimIdle;
    }

    /** Creates the consumer group (and stream) once; starts at 0 so earlier events are not skipped. */
    public void ensureGroup() {
        try {
            redis.execute((RedisCallback<Object>) connection -> execute(connection, "XGROUP", "CREATE", stream, group, "0", "MKSTREAM"));
        } catch (RedisSystemException e) {
            if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains("BUSYGROUP")) throw e;
        }
    }

    /** Applies one message. Pure database work, so it is testable without Redis. */
    public Outcome handle(Message message) {
        if (!Change.Type.DEACTIVATED.eventName().equals(message.type())) {
            metrics.consumer(NAME, "ignored");
            return Outcome.IGNORED;
        }
        String tenantId = message.tenantId();
        String employeeId = message.payload().path("employeeId").asText();
        Object[] detail = new Object[2];

        Outcome outcome = tx.call(() -> {
            if (!directory.markConsumed(NAME, UUID.fromString(message.eventId()))) return Outcome.DUPLICATE;
            // A rehire may have been applied after this exit; their new bookings must survive.
            boolean active = employees.findById(tenantId, employeeId).map(e -> e.status() == Status.ACTIVE).orElse(false);
            if (active) return Outcome.SKIPPED_REACTIVATED;
            CancelResult result = bookings.cancelFutureForEmployee(tenantId, employeeId, "EMPLOYEE_DEACTIVATED");
            detail[0] = result.cancelled();
            detail[1] = result.tripsFreed();
            return result.cancelled() > 0 ? Outcome.CANCELLED : Outcome.NOTHING_TO_CANCEL;
        });

        metrics.consumer(NAME, outcome.name().toLowerCase());
        log.info("Deactivation side effects handled {} {} {} {} {} {}", kv("tenantId", tenantId), kv("employeeId", employeeId),
                kv("eventId", message.eventId()), kv("outcome", outcome.name()), kv("cancelled", detail[0]), kv("tripsFreed", detail[1]));
        return outcome;
    }

    /** Reads new messages for this consumer (non-blocking) and processes them. */
    @SuppressWarnings("unchecked") // read(...) takes generic varargs (StreamOffset<K>...); the call itself is type-safe
    public int pollOnce(String consumerName) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(Consumer.from(group, consumerName),
                StreamReadOptions.empty().count(50), StreamOffset.create(stream, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) return 0;
        process(records);
        return records.size();
    }

    /** Takes over messages another consumer read but never acknowledged (it crashed). */
    public int reclaim(String consumerName, Duration minIdle) {
        PendingMessages pending = redis.opsForStream().pending(stream, group, Range.unbounded(), 100);
        List<RecordId> stuck = pending.stream()
                .filter(p -> p.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0)
                .map(PendingMessage::getId)
                .toList();
        if (stuck.isEmpty()) return 0;
        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(stream, group, consumerName, minIdle, stuck.toArray(RecordId[]::new));
        process(claimed);
        return claimed.size();
    }

    public long pendingCount() {
        PendingMessagesSummary summary = redis.opsForStream().pending(stream, group);
        return summary == null ? 0 : summary.getTotalPendingMessages();
    }

    private void process(List<MapRecord<String, Object, Object>> records) {
        for (MapRecord<String, Object, Object> record : records) {
            Message message;
            try {
                message = parse(record.getValue());
            } catch (RuntimeException e) {
                metrics.consumer(NAME, "poison");
                log.error("Unparseable message acknowledged and skipped {}", kv("messageId", record.getId().getValue()));
                redis.opsForStream().acknowledge(stream, group, record.getId());
                continue;
            }
            try {
                handle(message);
                redis.opsForStream().acknowledge(stream, group, record.getId());
            } catch (RuntimeException e) {
                // Not acknowledged: stays pending and is retried through reclaim().
                metrics.consumer(NAME, Retry.isTransient(e) ? "retry" : "failed");
                log.error("Deactivation side effects failed; will retry {} {} {}", kv("messageId", record.getId().getValue()),
                        kv("eventId", message.eventId()), kv("error", e.getClass().getSimpleName()));
            }
        }
    }

    private Message parse(Map<Object, Object> fields) {
        try {
            return new Message(String.valueOf(fields.get("eventId")), String.valueOf(fields.get("type")), String.valueOf(fields.get("tenantId")),
                    json.readTree(String.valueOf(fields.get("payload"))));
        } catch (Exception e) {
            throw new IllegalArgumentException("unparseable stream entry", e);
        }
    }

    private static Object execute(RedisConnection connection, String command, String... args) {
        byte[][] bytes = new byte[args.length][];
        for (int i = 0; i < args.length; i++) bytes[i] = args[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return connection.execute(command, bytes);
    }
}
