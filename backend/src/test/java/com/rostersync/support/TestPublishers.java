package com.rostersync.support;

import com.rostersync.messaging.EventPublisher;
import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.springframework.data.redis.RedisConnectionFailureException;

/** Publisher test doubles. */
public final class TestPublishers {

    private TestPublishers() {}

    public static final class InMemory implements EventPublisher {
        public final List<StreamMessage> messages = new CopyOnWriteArrayList<>();

        @Override
        public void publish(StreamMessage message) {
            messages.add(message);
        }
    }

    /** Fails the first {@code failures} calls with the given error, then delegates. */
    public static final class Flaky implements EventPublisher {
        private final EventPublisher inner;
        private final Supplier<RuntimeException> error;
        private int remaining;

        public Flaky(EventPublisher inner, int failures, Supplier<RuntimeException> error) {
            this.inner = inner;
            this.remaining = failures;
            this.error = error;
        }

        public Flaky(EventPublisher inner, int failures) {
            this(inner, failures, () -> new RedisConnectionFailureException("connection refused", new ConnectException("refused")));
        }

        @Override
        public synchronized void publish(StreamMessage message) {
            if (remaining > 0) {
                remaining--;
                throw error.get();
            }
            inner.publish(message);
        }
    }
}
