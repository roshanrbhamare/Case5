# ADR 001: Java 21 and Spring Boot 3

**Status:** accepted

**Context.** This is a Java backend assessment, and the problem is transactional: row locks, savepoints, set-based SQL, streaming bulk loads, a scheduler and a message relay.

**Decision.** Java 21, Spring Boot 3.3, plain SQL through `JdbcTemplate`, Flyway, Spring Security, Spring Data Redis, Micrometer, JUnit 5.

**Why these pieces:**
- **`JdbcTemplate` rather than JPA/Hibernate.** The core of this system is SQL a JPA layer would hide: `SELECT … FOR UPDATE`, `FOR UPDATE SKIP LOCKED`, `INSERT … ON CONFLICT DO NOTHING`, data-modifying CTEs that write the employee and outbox rows in one statement, and `COPY FROM STDIN`. Explicit SQL keeps the locking and the round trips visible and reviewable.
- **`TransactionTemplate` rather than `@Transactional` for the write paths.** The ingest loop needs one short transaction per record inside one method. `@Transactional` on a method called from the same class is bypassed (proxy self-invocation), and nested savepoints for the nightly apply need `PROPAGATION_NESTED` at a precise point. The small `Transactions` helper makes both explicit.
- **One app.** The API and the three `@Scheduled` background loops run in the same Spring Boot process, and Flyway migrates at startup. Simple to run and reason about; the loops can be moved to their own deployment later (`WORKER_ENABLED=false` on the API).
- **Plain domain classes** (`com.rostersync.domain`) with no Spring imports: `Employee` (private state, intention-revealing methods), the sealed `EventHandler` hierarchy (`CreateHandler`, `UpdateHandler`, `DeactivateHandler`) chosen by event type, and `VersionPolicy`, `OwnershipPolicy`, `ReconcilePolicy`, `SafetyBrake`, `EventValidator`. Unit-tested without a container.
- **Constructor injection everywhere** (no field `@Autowired`): dependencies are explicit, final and easy to replace in tests.

**Trade-offs:** more SQL to own than with JPA; the set-based nightly statements duplicate a little domain logic, guarded by a parity test (`ReconcileIT.setBasedApplyPublishesTheSameEventsAsTheDomainPath`).
