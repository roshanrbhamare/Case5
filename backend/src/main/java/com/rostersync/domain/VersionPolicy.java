package com.rostersync.domain;

/**
 * Idempotency key: (tenantId, employeeId, sourceVersion).
 *
 * <p>Versions are monotonic per employee in the source system, so the stored hr_version is a
 * high-water mark: every key at or below it has already been seen. That makes a separate
 * "processed events" table unnecessary (it would grow forever) and makes the check O(1)
 * on a row we lock anyway.
 */
public final class VersionPolicy {

    public enum Decision { APPLY, STALE, DUPLICATE }

    private VersionPolicy() {}

    public static Decision decide(Long storedVersion, long incomingVersion) {
        if (storedVersion == null || incomingVersion > storedVersion) return Decision.APPLY;
        if (incomingVersion == storedVersion) return Decision.DUPLICATE;
        return Decision.STALE;
    }
}
