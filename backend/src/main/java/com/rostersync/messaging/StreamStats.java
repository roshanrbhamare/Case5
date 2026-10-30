package com.rostersync.messaging;

import com.rostersync.config.RosterSyncProperties;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Stream length and consumer backlog, or null while Redis is unreachable. */
@Component
public class StreamStats {

    public record Snapshot(long streamLength, long pending) {}

    private final StringRedisTemplate redis;
    private final String stream;
    private final String group;

    public StreamStats(StringRedisTemplate redis, RosterSyncProperties props) {
        this.redis = redis;
        this.stream = props.stream().name();
        this.group = props.stream().consumerGroup();
    }

    public Snapshot snapshot() {
        try {
            Long length = redis.opsForStream().size(stream);
            long pending = 0;
            try {
                PendingMessagesSummary summary = redis.opsForStream().pending(stream, group);
                pending = summary == null ? 0 : summary.getTotalPendingMessages();
            } catch (RuntimeException noGroupYet) {
                pending = 0;
            }
            return new Snapshot(length == null ? 0 : length, pending);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
