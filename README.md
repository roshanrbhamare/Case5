# RosterSync: HR roster sync that is idempotent, order-proof and self-healing

MoveInSync Backend Case Study 5 (Advanced Track).

Corporate clients push employee changes from their HR system: joins, transfers, shift changes and exits arrive live, and a full export of up to 200,000 employees arrives every night. In practice those changes arrive **twice**, **out of order**, **with bad data**, and **cut short**. RosterSync keeps MoveInSync's employee records correct anyway, and makes sure every change reaches the cab-booking side exactly once in effect.

| | |
| --- | --- |
| **Stack** | **Java 21 · Spring Boot 3.3** (Web, JDBC, Security, Actuator, Cache, Data Redis) · PostgreSQL 16 · Flyway · Redis 7 (cache + Streams) · Micrometer + Prometheus + Grafana · React 18 + Vite · Docker Compose |
| **Tests** | 104 automated tests (JUnit 5): domain unit tests, integration tests on real Postgres and Redis, concurrency tests, crash tests that kill a real JVM mid-write |
| **Nightly diff** | 200,000 rows in **28.8 s** end to end (target < 60 s), file streamed, never held in memory ([results](backend/bench/RESULTS.md)) |
| **Design** | [Design note](docs/design-note.md) (architecture, sequence diagram, failure-mode table) · [ADRs](docs/adr) |

---

## Run it (one command)

```bash
docker compose up --build
```

| What | Where |
| --- | --- |
| Web app | http://localhost:8088 |
| API | http://localhost:3000 (health: `/health/ready`, metrics: `/metrics`) |
| Prometheus (with alert rules) | http://localhost:9090 |
| Grafana on-call dashboard | http://localhost:3001 (anonymous viewer) |

The **backend** is one Spring Boot app:
- On startup, Flyway creates the schema and demo data is seeded into an empty database.
- It serves the API on port 3000.
- It runs three `@Scheduled` background loops: the outbox relay, the booking consumer and the nightly-file runner.

Postgres, Redis, the web app, Prometheus and Grafana come up alongside. Host ports 55432 (Postgres) and 56379 (Redis) avoid clashes with local installs.

**Demo accounts** (local seed data only, password `Demo@1234` for all):

| Email | Role | Can |
| --- | --- | --- |
| `operator@acme.test` | Sync operator | Dashboard, upload nightly files, approve/reject paused runs, test feed. Sees masked contact details |
| `admin@acme.test` | Transport admin | Roster, edit pickup point and special assistance (HR can't) |
| `employee@acme.test` | Employee (E1001) | Own profile and upcoming rides only |
| `operator@globex.test` | Operator, second client | Proves tenant isolation: sees none of Acme's data |

HR system API keys (header `x-api-key`): `acme-hr-dev-key`, `globex-hr-dev-key`.

**Using your own Postgres or Redis.** Copy [`.env.example`](.env.example) to `.env` and set `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` and/or `REDIS_URL`. Docker Compose reads `.env` automatically, and anything left unset uses the local containers. `.env` is git-ignored, so credentials stay out of the repository. Outside Docker, the same names are read as environment variables (see `backend/src/main/resources/application.yml`).

---

## What it does, mapped to the brief

| Requirement | How | Where (Java) | Proven by |
| --- | --- | --- | --- |
| **Ingest** create/update/deactivate with source version | `POST /v1/events`, batches of up to 5,000 | `EventsController`, `IngestService` | `IngestIT` |
| **Validate at the boundary**, per-record error report; a bad record never fails the batch | Validation per record with stable error codes; one short transaction per record; the report stores code and field, never the value | `EventValidator`, `ingest_errors` | `IngestIT.oneBadRecordNeverFailsTheBatch…` |
| **Per-employee order**: stale ignored, duplicate is a no-op | Idempotency key `(tenant, employee, version)`; `hr_version` high-water mark under `SELECT … FOR UPDATE` | `VersionPolicy`, `EmployeeWriter` | v7 before v6, replay twice, 50 versions racing (`ConcurrencyIT`) |
| **Field ownership**: HR never overwrites transport fields; rule written down | Disjoint column sets per owner; non-owned fields dropped with a warning; transport edits carry their own optimistic version | `OwnershipPolicy`, `EmployeeRepository.saveHr/saveTransport` | `EmployeeTest`, `IngestIT`, `ConcurrencyIT` |
| **Efficient diff** of a 200k export with bounded memory | Streamed `COPY` (PG `CopyManager`) into staging, then 256 hashed buckets with per-bucket digests; only differing buckets compared; set-based apply | `ReconcileService`, `ReconcileRepository` | `ReconcileBenchmark` (28.8 s for 200k) |
| **Safety brake**: ≥30% of active employees missing means stop, alert, deactivate nobody until a human approves; threshold configurable | Decided before any write; run `BLOCKED`; `ReconcileRunBlocked` event, metric and Prometheus alert; operator approves or rejects with a note | `SafetyBrake`, `ReconcileService.decide` | `ReconcileIT`, `ThresholdIT` |
| **Outbox**: database update and event can never disagree | Outbox row in the same transaction; relay with `SKIP LOCKED`; at-least-once to a Redis Stream; consumer dedupes by event id | `OutboxRepository`, `OutboxRelay` | **`CrashIT`**: JVM killed between write and outbox, and between publish and mark-sent |
| **Deactivation side effects**: cancel future bookings, free seats | Stream consumer group, idempotent; skips if rehired meanwhile; reclaims messages from crashed consumers | `DeactivationConsumer`, `BookingRepository` | `OutboxConsumerIT` |
| **No personal data in logs or errors** | Logs carry ids and counts; access log drops query strings; driver error details disabled (`logServerErrorDetail=false`); events carry field names only | `logback-spring.xml`, `AccessLogFilter`, `DomainEvents` | `LogRedactionIT` runs every flow and greps the logs |

### Plus points

| Area | What's there |
| --- | --- |
| **Auth & tenant isolation** | Spring Security, stateless: JWT (jjwt, HS256) for people, SHA-256-hashed API keys for HR systems. Roles `EMPLOYEE` / `ADMIN` / `OPERATOR` / `HR_SYSTEM` in one table (`AccessPolicy`), enforced with `@PreAuthorize("@access.allows('…')")`. The tenant always comes from the credential, never from the request, and every query filters by `tenant_id` ([ADR 005](docs/adr/005-tenant-isolation.md)) |
| **Cost estimation** | [Complexity and limits](#complexity-and-where-it-stops-scaling) and [benchmarks](backend/bench/RESULTS.md) |
| **Failure handling** | Crash-safe outbox, leased and resumable nightly runs, retries with backoff for transient DB errors (`Retry`), broker-outage mode, dead letters, consumer reclaim, abandoned-upload sweeper. [Failure-mode table](docs/design-note.md#4-failure-modes) |
| **OOP & layering** | Controllers → services → repositories. Plain-Java domain: `Employee` aggregate with private state, sealed `EventHandler` hierarchy picked polymorphically by event type, `AppException` hierarchy, `EventPublisher` interface (Redis / in-memory / flaky). Constructor injection only ([ADR 001](docs/adr/001-java-and-spring-boot.md)) |
| **Trade-offs** | [Below](#trade-offs) and in the ADRs |
| **Monitoring** | Micrometer → Prometheus: events by outcome, rejections by code, retries, outbox depth and lag, dead letters, consumer backlog, brake trips, phase durations, cache errors, HTTP latency histograms. Structured JSON logs (Logstash encoder) with request ids. Alert rules in `monitoring/alerts.yml`; Grafana on-call dashboard provisioned |
| **Caching** | Spring Cache on Redis (`@Cacheable`) with a TTL per cache and typed serializers; a `CacheErrorHandler` turns Redis failures into misses. See [What can be stale](#caching-what-can-be-stale-and-for-how-long) |
| **Error handling** | `AppException` hierarchy and one `@RestControllerAdvice` produce `{ error: { code, message, requestId } }`; 5xx never leak internals |

---

## Walkthrough

1. `docker compose up --build`, open http://localhost:8088 and sign in as **Meera (operator)**.
2. **Test feed → Out-of-order delivery → Send.** v1 and v7 are applied; v6 comes back *Stale, ignored*.
3. **Same feed twice.** The second delivery is all repeats or late arrivals, and nothing changes.
4. **A bad record in the batch.** Two records are rejected with plain reasons; the rest are applied. *Sync activity* shows the error report: field and code, never the value.
5. **Nightly file, broken export:**
   ```bash
   scripts/demo-export.sh --keep 0.6 --out demo/acme-truncated.csv
   ```
   Upload it on *Nightly file*. **Paused: missing ~40%**: nobody is deactivated, the dashboard banner appears, and `NightlyExportBlocked` fires in Prometheus. Reject it with a note.
6. **Nightly file, normal export** (`scripts/demo-export.sh --change 4 --join 2 --leave 1 --out demo/acme-normal.csv`) is applied, with a per-category plan.
7. **Failure, live: a crash between publishing an event and recording that it was published.**
   ```bash
   CHAOS_CRASH_AT=relay.afterPublish docker compose up -d backend
   ```
   When the backend is back up, go to *Test feed → Someone leaves → Send*. The JVM halts right after publishing (`[chaos] crashing at relay.afterPublish` in `docker compose logs backend`), Docker restarts it, and it publishes again because the outbox row is still `PENDING`. Turn the fault off:
   ```bash
   docker compose up -d backend
   docker compose logs backend | grep -E '"outcome":"(CANCELLED|DUPLICATE)"'
   ```
   One `CANCELLED` and several `DUPLICATE`s: the exit reached the booking side several times, but rides were cancelled and seats freed exactly once.
8. Sign in as **Rahul (admin)** and edit a pickup point. As **Ananya (employee)**, see your own rides. As **Vikram (Globex)**, see none of Acme's data.
9. Open Grafana (http://localhost:3001) to see the on-call dashboard.

### Sample API calls

```bash
# HR system: send events (API key)
curl -s localhost:3000/v1/events -H 'x-api-key: acme-hr-dev-key' -H 'content-type: application/json' -d '{
  "events": [
    {"type":"UPDATE","employeeId":"E1002","version":7,"data":{"fullName":"Priya Mehta","email":"priya@acme.example","phone":"+919810015838","officeCode":"PNQ-HJ","shiftCode":"S1000"}},
    {"type":"UPDATE","employeeId":"E1002","version":6,"data":{"fullName":"Priya Mehta","email":"priya@acme.example","phone":"+919810015838","officeCode":"BLR-WF","shiftCode":"NIGHT"}},
    {"type":"CREATE","employeeId":"E1999","version":1,"data":{"fullName":"Bad Phone","email":"x@acme.example","phone":"12abc","officeCode":"BLR-WF"}}
  ]}'

# HR system: upload the nightly file (streamed)
curl -s localhost:3000/v1/reconcile/runs -H 'x-api-key: acme-hr-dev-key' -H 'content-type: text/csv' --data-binary @demo/acme-truncated.csv

# Operator: sign in, list runs, reject a blocked one
TOKEN=$(curl -s localhost:3000/v1/auth/login -H 'content-type: application/json' -d '{"email":"operator@acme.test","password":"Demo@1234"}' | jq -r .token)
curl -s localhost:3000/v1/reconcile/runs -H "authorization: Bearer $TOKEN"
curl -s -X POST localhost:3000/v1/reconcile/runs/<runId>/reject -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{"note":"Cut off upstream; HR resending"}'
```

A Postman collection with every endpoint is in [`postman/RosterSync.postman_collection.json`](postman/RosterSync.postman_collection.json).

### API

| Method | Path | Who |
| --- | --- | --- |
| POST | `/v1/auth/login` | anyone (rate-limited) |
| GET | `/v1/auth/me` | any signed-in user |
| POST | `/v1/events` | HR system, operator |
| POST | `/v1/reconcile/runs` (body `text/csv`) | HR system, operator |
| GET | `/v1/reconcile/runs`, `/v1/reconcile/runs/{id}` | operator, admin |
| POST | `/v1/reconcile/runs/{id}/approve`, `/reject` (`{note}`) | operator |
| GET | `/v1/employees?status=&office=&search=&after=` | admin, operator (masked) |
| GET | `/v1/employees/{id}`, `/v1/employees/{id}/bookings` | admin, operator |
| PATCH | `/v1/employees/{id}/transport` (`{version, pickupPoint?, specialAssistance?}`) | admin |
| GET | `/v1/offices` | admin, operator |
| GET | `/v1/me` | employee |
| GET | `/v1/batches`, `/v1/batches/{id}/errors` | operator, admin |
| GET | `/v1/ops/summary` | operator, admin |
| GET | `/health/live`, `/health/ready`, `/metrics` | infra |

**Event format.** `type` is `CREATE` | `UPDATE` | `DEACTIVATE`, plus `employeeId`, `version` (positive integer; epoch milliseconds also work), and for CREATE/UPDATE a full HR snapshot in `data`: `fullName`, `email`, `phone`, `officeCode` (required), `managerId`, `shiftCode` (optional).

**Nightly file.** CSV with header `employee_id,full_name,email,phone,manager_id,office_code,shift_code,version`. It lists every active employee.

---

## Tests

Integration tests use real Postgres and Redis. With a local JDK 21 and Maven:

```bash
docker compose up -d postgres redis
cd backend && mvn test
```

Or run Maven in Docker on the compose network, with nothing to install locally:

```bash
docker run --rm --network rostersync_default -v "$PWD/backend":/app -v rostersync-m2:/root/.m2 -w /app -e TEST_DB_URL=jdbc:postgresql://postgres:5432/rostersync_test -e TEST_REDIS_URL=redis://redis:6379/15 maven:3.9.9-eclipse-temurin-21 mvn -B test
```

| Suite | Highlights |
| --- | --- |
| `domain/*Test` (unit) | Version policy, ownership, handlers (rehire, tombstone), validator (no values in errors), safety brake boundaries, reconcile decisions, hashing and bucket spread, access matrix |
| `IngestIT` | v7 before v6; **replay twice changes nothing** (state and outbox identical); bad records; ownership; tombstones; rehire; conflicts |
| `ReconcileIT`, `ThresholdIT` | Identical file skips all 256 buckets; joiners/changes/leavers; **truncated file blocks**; approve/reject; configurable threshold; file never undoes live data; duplicates in file; half-written last row; bad header; one open run per tenant; SQL and domain paths emit identical events |
| `OutboxConsumerIT` | Publish once; broker outage without burning attempts; dead letters; bookings cancelled end to end through Redis; redelivery no-op; rehire not cancelled; reclaim from a crashed consumer |
| `ApiIT` (MockMvc) | Login, tampered token, **tenant isolation through the API** (employees and nightly runs), `@PreAuthorize` role checks, masked views, optimistic transport edits, no personal data echoed, 400/404/415 shapes, metrics |
| `ConcurrencyIT` | 50 versions racing; the same event 20× at once (applied once); HR vs transport race; live events racing a nightly run; two uploads at once |
| `CrashIT` | A child JVM halted with exit 137 (a) between employee write and outbox write, (b) between publish and mark-sent, (c) mid-apply of a nightly run, then resumed by a healthy runner |
| `RedisOutageIT` | Redis unreachable: writes and reads still work, cache errors counted |
| `LogRedactionIT` | Runs every flow with debug logging and asserts names, emails, phones, addresses and passwords never appear |

CI (`.github/workflows/ci.yml`) runs `mvn verify` against Postgres and Redis service containers, and builds the frontend.

---

## Benchmarks

From [`backend/bench/RESULTS.md`](backend/bench/RESULTS.md). Machine: Intel i5-1135G7 (4 cores / 8 threads), 8 GB RAM, Windows 11. Java 21, Postgres 16 and Redis 7 all run in Docker Desktop (WSL2), the slowest realistic setup for disk and network.

**Nightly reconciliation, 200,000 employees** (2,000 changed, 1,000 joiners, 1,000 leavers):

| Phase | Time |
| --- | --- |
| Stage (stream, parse, validate, hash, COPY) | 18.4 s (≈10.9k rows/s) |
| Analyze (dedupe, digests, missing count, classify, safety brake) | 7.7 s |
| Apply (3,990 changes, set-based, with outbox rows) | 6.9 s |
| **Total** | **28.8 s** (target < 60 s) |

The file is streamed and never held in memory; the diff runs in Postgres. JVM heap in use peaked at 164 MB (mostly short-lived garbage between collections). A 120,000-row truncated export is **blocked in 10.6 s** with nothing applied.

**Live feed, `POST /v1/events`** (k6, 20 virtual users for 20 s, measured warm, 0 failed requests):

| Events per request | Events/s | p50 | p90 | p99 |
| --- | --- | --- | --- | --- |
| 1 | 547 | 31 ms | 52 ms | 128 ms |
| 50 | 1,584 | 564 ms | 866 ms | 1.16 s |

Reproduce: `cd backend && mvn test -Dgroups=benchmark -DexcludedTestGroups=none` (nightly diff), and `docker run --rm --network rostersync_default -v "$PWD/backend/bench:/bench" grafana/k6:0.54.0 run /bench/k6-ingest.js` with the stack running (live feed). The load test writes about 5,000 synthetic employees into the Acme demo tenant; run `docker compose down -v && docker compose up -d` afterwards for clean demo data.

---

## Complexity and where it stops scaling

Let **n** be the employees in a tenant, **f** the rows in a file, **c** the changed rows, **B** = 256 buckets.

| Operation | Time | Space (JVM) |
| --- | --- | --- |
| Live event | O(log n) index lookup, O(1) work; one row lock | O(1) |
| Batch of k events | O(k log n), k short transactions | O(k) for the response |
| Stage file | O(f) parse/validate/hash + O(f log f) index build in PG | **O(1)**: streamed, 256 KB COPY chunks |
| Analyze | O(n + f) digests and anti-join + O(c log n) for changed rows | O(c / B × group) per bucket group |
| Apply | O(c log n), set-based statements per bucket group | O(1) ids per group for cache eviction |
| Unchanged night | Only the digests run: every bucket matches and nothing is compared row by row | O(B) |

**Where it stops scaling, and what comes next:**
1. **Live-feed commits.** Each event commits its own transaction (fsync), so throughput is bounded by commit latency. *Next:* group commit per batch (savepoint per record, one commit).
2. **One Postgres primary** for all tenants. *Next:* partition `employees` and `outbox` by tenant hash, then shard tenants across primaries (tenant is already the leading key everywhere).
3. **One nightly run per tenant at a time** (deliberate). Different tenants run in parallel; with several backend replicas, leases spread runs across them.
4. **Redis Streams retention** is trimmed to about 500k entries. *Next:* Kafka if long retention or replay becomes a requirement.
5. **Very large files.** Staging grows linearly in Postgres (about 60 MB per 200k rows). *Next:* a staging partition per run, dropped afterwards.

---

## Caching: what can be stale and for how long

| Cache | TTL | Invalidated | Worst-case staleness |
| --- | --- | --- | --- |
| `employee` (single-employee reads) | 60 s | Evicted after commit on every live, nightly or transport change | 60 s, only if that eviction fails; otherwise none |
| `offices` (validator's office codes) | 300 s | Not invalidated (offices change rarely, by migration) | A new office can be rejected as unknown for up to 5 minutes |
| `apikeys` (key hash → tenant) | 300 s | Not invalidated | A revoked HR key keeps working for up to 5 minutes |

Writes never read from the cache: version checks always lock the row in Postgres. Redis runs with `maxmemory 256mb` and `volatile-lru`, so only TTL'd cache keys are evicted, never the event stream. While Redis is down, Lettuce rejects commands immediately and every lookup is a miss.

---

## Trade-offs

- **Consistency vs availability.** The roster is strongly consistent (ACID, row locks). Downstream effects are eventually consistent (outbox, at-least-once, idempotent consumers). Redis is optional for correctness; Postgres is the single hard dependency.
- **Safety vs freshness at night.** A suspicious file waits for a human rather than being partly applied. A blocked run also holds back the next file for that tenant until someone decides (deliberate).
- **Latency vs simplicity in the relay.** The outbox is polled every 250 ms instead of using CDC: sub-second extra latency, no extra infrastructure.
- **Snapshots over patches.** HR events carry the full HR record, so ordering is a version comparison, not a merge problem.
- **Same version, different content** is flagged (`conflict: true`, metric) and not applied, rather than guessing.
- **`JdbcTemplate` over JPA**, and set-based nightly SQL for speed, guarded by a parity test ([ADR 001](docs/adr/001-java-and-spring-boot.md), [ADR 004](docs/adr/004-nightly-diff-in-postgres.md)).
- **Operators see masked contact details.** Least privilege for the people who run the sync.

### Deliberately left out
- Kafka, CDC (Debezium), Kubernetes manifests: not needed at this scale.
- SSO/OIDC: JWT + BCrypt shows the role model; production would sit behind the client's IdP.
- Re-planning cab routes when an employee's shift or office changes: change events are published for that service, but routing is out of scope. Existing bookings keep their original trip.
- Partial-update events and field-level merge (see "Snapshots over patches").
- Per-tenant safety-brake thresholds: one configurable global threshold; each run records the threshold it used.
- **Postgres row-level security** as a second tenant wall: isolation is enforced in the application (tenant from the credential, `tenant_id` in every query, tested). Row-level security is the next step if a stricter guarantee is needed.
- **A separate worker process:** the background loops run inside the one backend app. If nightly runs ever compete with API latency, the same `@Scheduled` beans can run in their own deployment with `WORKER_ENABLED=false` on the API side.
- **An audit-history table:** every change is already published as an event (with the fields that changed) through the outbox; a history store would consume those events.

### Assumptions
- Source versions are monotonic per employee (an integer counter or epoch milliseconds).
- The nightly file lists every **active** employee; anyone not listed is a leaver, subject to the safety brake.
- Ride dates are local calendar dates for India (`Asia/Kolkata`).
- Office codes are managed by MoveInSync (seeded); unknown codes are rejected.

---

## Project layout

```
backend/                         Spring Boot (Java 21, Maven)
  src/main/java/com/rostersync/
    domain/        Employee, EventHandler (sealed), VersionPolicy, OwnershipPolicy, ReconcilePolicy, SafetyBrake, EventValidator, AccessPolicy
    service/       IngestService, ReconcileService, EmployeeWriter, Transport/Query services, AuthService, OpsService
    persistence/   Transactions (TransactionTemplate), JdbcTemplate repositories, CacheEvictor
    messaging/     OutboxRelay, RedisStreamPublisher, DeactivationConsumer, ReconcileRunner, WorkerScheduler (@Scheduled)
    web/           controllers, Views, GlobalExceptionHandler, filters, security/ (Spring Security, JWT/API-key filter, @access guard)
    config/        typed properties, metrics, Redis, cache
    bootstrap/     demo data seeder (runs at startup when SEED_DEMO_DATA=true)
  src/main/resources/db/migration/   Flyway SQL schema
  src/test/java/                     unit, integration, concurrency, crash tests (+ ReconcileBenchmark)
  bench/                             k6 load script, RESULTS.md
web/              React app (Vite), served by nginx with /api proxied
scripts/          demo-export.sh (normal or broken nightly files from the running DB)
postman/          Postman collection covering every endpoint
docs/             design-note.md, adr/
monitoring/       Prometheus config, alert rules, Grafana dashboard
docker/           Postgres init script (creates the test database)
.github/          CI: backend tests and frontend build
docker-compose.yml
```
