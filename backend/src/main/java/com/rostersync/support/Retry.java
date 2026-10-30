package com.rostersync.support;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** Retries only transient database failures (serialization, deadlock, lost connection) with backoff and jitter. */
public final class Retry {

    private Retry() {}

    public static boolean isTransient(Throwable error) {
        return error instanceof TransientDataAccessException
                || error instanceof RecoverableDataAccessException
                || error instanceof CannotGetJdbcConnectionException;
    }

    public static <T> T withRetry(Supplier<T> work, int attempts, Runnable onRetry) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (RuntimeException e) {
                if (attempt >= attempts || !isTransient(e)) throw e;
                onRetry.run();
                sleep(25L * (1L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(25));
            }
        }
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
