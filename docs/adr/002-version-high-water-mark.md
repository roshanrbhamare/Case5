# ADR 002: Idempotency through a per-employee version high-water mark

**Status:** accepted

**Context.** Events arrive twice and out of order. A common approach is a `processed_events(event_id)` table checked before applying.

**Decision.** The idempotency key is `(tenant_id, employee_id, source_version)`, and the stored `employees.hr_version` is a high-water mark. Under `SELECT … FOR UPDATE`: incoming `>` stored means apply; `=` means duplicate (a no-op, flagged as a conflict if the content differs); `<` means stale (ignored).

**Consequences:**
- No ever-growing dedupe table, and the check costs nothing extra because we lock and read the row anyway.
- Events must be full HR snapshots (state transfer), not partial patches. That is what makes "v7 before v6" end correctly: v7 already contains the shift as of v7. Documented as an assumption of the HR contract.
- An exit for an unknown employee stores a tombstone with its version, so a late join is recognised as stale.
- The nightly file shares the same version space, so it can never undo a newer live update.
- If the source system re-uses a version number for different content, we keep the first one and count it in the `rostersync_version_conflicts_total` metric (and flag `conflict: true` in the response) instead of guessing.
