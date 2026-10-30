package com.rostersync.messaging;

/** Where outbox events go. Implementations: Redis Streams in production, in-memory and flaky doubles in tests. */
public interface EventPublisher {

    /** One published change event. The payload is JSON with ids, versions and field names only. */
    record StreamMessage(String eventId, String type, String tenantId, String aggregateId, String payloadJson, String occurredAt) {}

    void publish(StreamMessage message);

    /** A failure retrying can never fix (the message itself is bad). Counts toward dead-lettering. */
    class PermanentPublishException extends RuntimeException {
        public PermanentPublishException(String message) {
            super(message);
        }
    }
}
