package com.rostersync;

import static com.rostersync.support.Fixtures.event;
import static com.rostersync.support.Fixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rostersync.messaging.DeactivationConsumer;
import com.rostersync.messaging.DeactivationConsumer.Message;
import com.rostersync.messaging.DeactivationConsumer.Outcome;
import com.rostersync.messaging.EventPublisher.PermanentPublishException;
import com.rostersync.messaging.OutboxRelay;
import com.rostersync.service.IngestService;
import com.rostersync.support.IntegrationTest;
import com.rostersync.support.TestDb;
import com.rostersync.support.TestPublishers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = {"rostersync.outbox.max-attempts=3", "rostersync.stream.reclaim-ms=100"})
class OutboxConsumerIT {

    @Autowired IngestService ingest;
    @Autowired OutboxRelay relay;
    @Autowired DeactivationConsumer consumer;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;

    static final String STREAM = "test.employee-events";
    static final String GROUP = "test-booking-deactivation";

    @BeforeEach
    void reset() {
        TestDb.reset(Map.of("acme", 5));
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    void send(Object... events) {
        ingest.ingest("acme", List.of(events), "test:hr");
    }

    List<Map<String, Object>> outboxRows() {
        return TestDb.query("SELECT event_type, status, attempts FROM outbox ORDER BY id");
    }

    List<Map<String, Object>> bookings(String employeeId) {
        return TestDb.query("SELECT status, cancel_reason FROM bookings WHERE tenant_id = 'acme' AND employee_id = ? ORDER BY ride_date", employeeId);
    }

    int seatsBookedFor(String employeeId) {
        return TestDb.count("SELECT sum(seats_booked)::int FROM trips WHERE id IN (SELECT trip_id FROM bookings WHERE tenant_id = 'acme' AND employee_id = ?)",
                employeeId);
    }

    Message deactivated(String employeeId) throws Exception {
        String eventId = (String) TestDb.one("SELECT event_id::text AS id FROM outbox WHERE event_type = 'EmployeeDeactivated' AND aggregate_id = ?", employeeId).get("id");
        return new Message(eventId, "EmployeeDeactivated", "acme", json.readTree("{\"employeeId\":\"" + employeeId + "\"}"));
    }

    @Test
    void publishesCommittedEventsOnceAndMarksThemSent() {
        send(event("UPDATE", "E1001", 2, snapshot("shiftCode", "NIGHT")), event("DEACTIVATE", "E1002", 2));
        var publisher = new TestPublishers.InMemory();

        int published = relay.drain(publisher);

        assertThat(published).isEqualTo(2);
        assertThat(publisher.messages).extracting(m -> m.type()).containsExactly("EmployeeUpdated", "EmployeeDeactivated");
        assertThat(outboxRows()).containsExactly(
                Map.of("event_type", "EmployeeUpdated", "status", "SENT", "attempts", 1),
                Map.of("event_type", "EmployeeDeactivated", "status", "SENT", "attempts", 1));
    }

    @Test
    void aBrokerOutagePausesPublishingWithoutBurningAttempts_thenDrains() {
        send(event("DEACTIVATE", "E1001", 2), event("DEACTIVATE", "E1002", 2));
        var sink = new TestPublishers.InMemory();
        var flaky = new TestPublishers.Flaky(sink, 3);

        for (int i = 0; i < 3; i++) assertThat(relay.tick(flaky).outage()).isTrue();
        assertThat(outboxRows()).allSatisfy(r -> assertThat(r).containsEntry("status", "PENDING").containsEntry("attempts", 0));

        relay.drain(flaky);
        assertThat(sink.messages).hasSize(2);
        assertThat(outboxRows()).allSatisfy(r -> assertThat(r).containsEntry("status", "SENT"));
    }

    @Test
    void aMessageThatCanNeverBePublishedIsDeadLetteredAfterTheMaxAttempts() {
        send(event("DEACTIVATE", "E1001", 2));
        var poisoned = new TestPublishers.Flaky(new TestPublishers.InMemory(), 99, () -> new PermanentPublishException("payload rejected"));

        for (int i = 0; i < 3; i++) {
            relay.tick(poisoned);
            TestDb.exec("UPDATE outbox SET next_attempt_at = now()"); // skip the backoff wait
        }

        assertThat(outboxRows()).containsExactly(Map.of("event_type", "EmployeeDeactivated", "status", "DEAD", "attempts", 3));
        assertThat(TestDb.query("SELECT event_type, attempts, last_error FROM dead_letter"))
                .containsExactly(Map.of("event_type", "EmployeeDeactivated", "attempts", 3, "last_error", "payload rejected"));
    }

    @Test
    void anExitCancelsFutureBookingsAndFreesSeats_endToEndThroughTheStream() {
        int seatsBefore = seatsBookedFor("E1003");
        send(event("DEACTIVATE", "E1003", 2));
        relay.drain();
        consumer.ensureGroup();

        consumer.pollOnce("test-consumer");

        List<Map<String, Object>> rows = bookings("E1003");
        assertThat(rows).isNotEmpty().allSatisfy(b -> assertThat(b).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "EMPLOYEE_DEACTIVATED"));
        assertThat(seatsBookedFor("E1003")).isEqualTo(seatsBefore - rows.size());
        assertThat(consumer.pendingCount()).isZero();
    }

    @Test
    void aRedeliveredEventIsANoOp_exactlyOnceEffect() throws Exception {
        send(event("DEACTIVATE", "E1003", 2));
        Message message = deactivated("E1003");

        assertThat(consumer.handle(message)).isEqualTo(Outcome.CANCELLED);
        int seatsAfterFirst = seatsBookedFor("E1003");
        assertThat(consumer.handle(message)).isEqualTo(Outcome.DUPLICATE);
        assertThat(seatsBookedFor("E1003")).isEqualTo(seatsAfterFirst);
        assertThat(TestDb.count("SELECT count(*)::int FROM consumed_events WHERE consumer = ?", DeactivationConsumer.NAME)).isEqualTo(1);
    }

    @Test
    void aLateExitEventDoesNotCancelTheBookingsOfSomeoneAlreadyRehired() throws Exception {
        send(event("DEACTIVATE", "E1004", 2), event("CREATE", "E1004", 3, snapshot()));

        assertThat(consumer.handle(deactivated("E1004"))).isEqualTo(Outcome.SKIPPED_REACTIVATED);
        assertThat(bookings("E1004")).allSatisfy(b -> assertThat(b).containsEntry("status", "BOOKED"));
    }

    @Test
    @SuppressWarnings("unchecked") // generic varargs in read(...), as in DeactivationConsumer.pollOnce
    void aMessageReadByAConsumerThatCrashedBeforeAckIsReclaimedByAnother() throws Exception {
        send(event("DEACTIVATE", "E1005", 2));
        relay.drain();
        consumer.ensureGroup();
        // Read without processing: what a consumer that dies right after XREADGROUP leaves behind.
        redis.opsForStream().read(Consumer.from(GROUP, "crashed-consumer"), StreamReadOptions.empty().count(10),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        assertThat(consumer.pendingCount()).isEqualTo(1);

        Thread.sleep(150);
        assertThat(consumer.reclaim("survivor", Duration.ofMillis(100))).isEqualTo(1);

        assertThat(bookings("E1005")).allSatisfy(b -> assertThat(b).containsEntry("status", "CANCELLED"));
        assertThat(consumer.pendingCount()).isZero();
    }
}
