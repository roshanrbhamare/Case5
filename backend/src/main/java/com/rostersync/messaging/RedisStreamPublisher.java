package com.rostersync.messaging;

import com.rostersync.config.RosterSyncProperties;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis Streams: durable, ordered, consumer groups with acknowledgements and redelivery. */
@Component
public class RedisStreamPublisher implements EventPublisher {

    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final StringRedisTemplate redis;
    private final String stream;
    private final long maxLength;
    private final AtomicLong sinceTrim = new AtomicLong();

    public RedisStreamPublisher(StringRedisTemplate redis, RosterSyncProperties props) {
        this.redis = redis;
        this.stream = props.stream().name();
        this.maxLength = props.stream().maxLength();
    }

    @Override
    public void publish(StreamMessage message) {
        if (message.payloadJson().getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new PermanentPublishException("payload exceeds 64KB");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", message.eventId());
        fields.put("type", message.type());
        fields.put("tenantId", message.tenantId());
        fields.put("aggregateId", message.aggregateId());
        fields.put("occurredAt", message.occurredAt());
        fields.put("payload", message.payloadJson());
        MapRecord<String, String, String> record = StreamRecords.newRecord().in(stream).ofMap(fields);
        redis.opsForStream().add(record);
        // Approximate trim now and then keeps the stream bounded without paying for it on every add.
        if (sinceTrim.incrementAndGet() % 1000 == 0) redis.opsForStream().trim(stream, maxLength, true);
    }
}
