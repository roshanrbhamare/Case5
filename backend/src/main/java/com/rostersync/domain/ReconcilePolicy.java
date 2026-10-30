package com.rostersync.domain;

import com.rostersync.domain.Fields.InactiveReason;
import com.rostersync.domain.Fields.Status;

/**
 * Decides what one row of the nightly export means for the stored employee. The live feed
 * and the file share one version space, so the file can never undo a newer live update
 * (STALE), and the same version with different content is surfaced as a CONFLICT for a
 * human instead of guessing which side is right.
 */
public final class ReconcilePolicy {

    public enum Action { CREATE, UPDATE, REACTIVATE, UNCHANGED, STALE, CONFLICT }

    public record FileRow(long version, String hrHash) {}

    public record Stored(long hrVersion, String hrHash, Status status, InactiveReason inactiveReason) {}

    private ReconcilePolicy() {}

    public static Action decide(FileRow file, Stored stored) {
        if (stored == null) return Action.CREATE;
        if (file.version() > stored.hrVersion()) return stored.status() == Status.ACTIVE ? Action.UPDATE : Action.REACTIVATE;
        if (file.version() < stored.hrVersion()) return Action.STALE;
        if (stored.status() == Status.ACTIVE) return file.hrHash().equals(stored.hrHash()) ? Action.UNCHANGED : Action.CONFLICT;
        // Same version, but we deactivated them only because an earlier export left them out.
        if (stored.inactiveReason() == InactiveReason.MISSING_FROM_EXPORT) return Action.REACTIVATE;
        // HR told us they left at this very version, yet the file still lists them.
        return Action.CONFLICT;
    }

    public static boolean isApplicable(Action action) {
        return action == Action.CREATE || action == Action.UPDATE || action == Action.REACTIVATE;
    }
}
