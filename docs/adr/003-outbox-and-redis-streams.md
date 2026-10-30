# ADR 003: Transactional outbox, polled, published to Redis Streams

**Status:** accepted

**Context.** Every applied change must produce an event, and the database and the events must never disagree. Publishing to a broker inside the request either loses events (crash after commit) or emits events for rolled-back changes (crash before commit).

**Decision.**
- Write the outbox row in the same transaction as the employee change.
- A relay in the worker claims pending rows with `FOR UPDATE SKIP LOCKED`, publishes them with `XADD`, and marks them `SENT` in that same transaction.
- Consumers use a Redis Streams consumer group, record `event_id` in `consumed_events` in the same transaction as their side effect, and `XACK` only after commit.

**Why Redis Streams rather than Kafka:** at this scale (thousands of events per night per client) Streams give durable append-only logs, consumer groups, acknowledgements and redelivery (`XPENDING` + `XCLAIM` for messages a crashed consumer never acknowledged), and the stack stays a single `docker compose up`. Kafka becomes worthwhile with many independent consumer teams, long retention or replay needs.

**Why polling rather than CDC (Debezium):** fewer moving parts. The cost is up to `OUTBOX_POLL_MS` (250 ms) of added latency, which is irrelevant for roster changes.

**Failure handling:** a broker outage stops the batch without counting attempts, so a long outage never dead-letters valid events; lag and queue depth alert instead. Only permanent failures (a message that can't be published) count toward dead-lettering.

**Event payloads** carry ids, versions and changed field names, never personal data (claim-check). Downstream services read details through the API with their own permissions.
