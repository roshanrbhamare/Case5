-- RosterSync schema (Flyway runs this on startup). Every tenant-owned table carries
-- tenant_id, and every query filters by it: one client can never read another's rows.

CREATE TABLE tenants (
  id               text PRIMARY KEY,
  name             text NOT NULL,
  hr_api_key_hash  text NOT NULL UNIQUE,
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE offices (
  tenant_id  text NOT NULL REFERENCES tenants(id),
  code       text NOT NULL,
  name       text NOT NULL,
  city       text NOT NULL,
  PRIMARY KEY (tenant_id, code)
);

CREATE TABLE users (
  id             uuid PRIMARY KEY,
  tenant_id      text NOT NULL REFERENCES tenants(id),
  email          text NOT NULL UNIQUE,
  display_name   text NOT NULL,
  password_hash  text NOT NULL,
  role           text NOT NULL CHECK (role IN ('EMPLOYEE', 'ADMIN', 'OPERATOR')),
  employee_id    text,
  created_at     timestamptz NOT NULL DEFAULT now()
);

-- HR-owned columns are nullable only to allow tombstones (an exit that arrived before the
-- join); the boundary validator requires them for every CREATE/UPDATE.
CREATE TABLE employees (
  tenant_id           text NOT NULL REFERENCES tenants(id),
  employee_id         text NOT NULL,
  full_name           text,
  email               text,
  phone               text,
  manager_id          text,
  office_code         text,
  shift_code          text,
  pickup_point        text,
  special_assistance  text,
  status              text NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
  inactive_reason     text,
  hr_version          bigint NOT NULL,
  transport_version   bigint NOT NULL DEFAULT 0,
  hr_hash             text NOT NULL,
  bucket              smallint NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, employee_id)
);
CREATE INDEX employees_tenant_status_bucket ON employees (tenant_id, status, bucket);

CREATE TABLE trips (
  id            uuid PRIMARY KEY,
  tenant_id     text NOT NULL REFERENCES tenants(id),
  office_code   text NOT NULL,
  ride_date     date NOT NULL,
  shift_code    text NOT NULL,
  direction     text NOT NULL CHECK (direction IN ('PICKUP', 'DROP')),
  capacity      int NOT NULL CHECK (capacity > 0),
  seats_booked  int NOT NULL DEFAULT 0,
  CHECK (seats_booked >= 0 AND seats_booked <= capacity)
);

CREATE TABLE bookings (
  id             uuid PRIMARY KEY,
  tenant_id      text NOT NULL REFERENCES tenants(id),
  employee_id    text NOT NULL,
  trip_id        uuid NOT NULL REFERENCES trips(id),
  ride_date      date NOT NULL,
  status         text NOT NULL CHECK (status IN ('BOOKED', 'CANCELLED', 'COMPLETED')),
  cancel_reason  text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX bookings_employee_date ON bookings (tenant_id, employee_id, ride_date);

-- Transactional outbox: written in the same transaction as the change it describes.
CREATE TABLE outbox (
  id               bigserial PRIMARY KEY,
  event_id         uuid NOT NULL UNIQUE,
  tenant_id        text NOT NULL,
  aggregate_id     text NOT NULL,
  event_type       text NOT NULL,
  payload          jsonb NOT NULL,
  status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'DEAD')),
  attempts         int NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  last_error       text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  published_at     timestamptz
);
CREATE INDEX outbox_pending ON outbox (next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX outbox_tenant_created ON outbox (tenant_id, created_at);

CREATE TABLE dead_letter (
  id          bigserial PRIMARY KEY,
  outbox_id   bigint NOT NULL,
  event_id    uuid NOT NULL,
  tenant_id   text NOT NULL,
  event_type  text NOT NULL,
  payload     jsonb NOT NULL,
  attempts    int NOT NULL,
  last_error  text,
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- Consumer-side idempotency: an event id is applied at most once per consumer.
CREATE TABLE consumed_events (
  consumer      text NOT NULL,
  event_id      uuid NOT NULL,
  processed_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);

CREATE TABLE ingest_batches (
  id          uuid PRIMARY KEY,
  tenant_id   text NOT NULL REFERENCES tenants(id),
  source      text NOT NULL CHECK (source IN ('API', 'NIGHTLY_FILE')),
  actor       text NOT NULL,
  received    int NOT NULL DEFAULT 0,
  applied     int NOT NULL DEFAULT 0,
  stale       int NOT NULL DEFAULT 0,
  duplicate   int NOT NULL DEFAULT 0,
  rejected    int NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ingest_batches_tenant_created ON ingest_batches (tenant_id, created_at DESC);

-- Error report: codes and field names only, never the rejected value.
CREATE TABLE ingest_errors (
  id            bigserial PRIMARY KEY,
  batch_id      uuid NOT NULL REFERENCES ingest_batches(id) ON DELETE CASCADE,
  tenant_id     text NOT NULL,
  record_index  int NOT NULL,
  employee_id   text,
  code          text NOT NULL,
  field         text
);
CREATE INDEX ingest_errors_batch ON ingest_errors (batch_id, record_index);

CREATE TABLE reconcile_runs (
  id                uuid PRIMARY KEY,
  tenant_id         text NOT NULL REFERENCES tenants(id),
  batch_id          uuid NOT NULL,
  status            text NOT NULL CHECK (status IN ('STAGING', 'QUEUED', 'ANALYZING', 'BLOCKED', 'APPROVED', 'APPLYING', 'COMPLETED', 'REJECTED', 'FAILED')),
  uploaded_by       text NOT NULL,
  threshold         numeric(5, 4) NOT NULL,
  file_rows         int NOT NULL DEFAULT 0,
  rejected_rows     int NOT NULL DEFAULT 0,
  duplicate_rows    int NOT NULL DEFAULT 0,
  active_before     int,
  missing_count     int,
  missing_ratio     numeric(6, 4),
  creates           int,
  updates           int,
  reactivations     int,
  deactivations     int,
  unchanged         int,
  stale             int,
  conflicts         int,
  buckets_skipped   int,
  applied_changes   int,
  failure_code      text,
  decided_by        text,
  decision_note     text,
  lease_owner       text,
  lease_expires_at  timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  analyzed_at       timestamptz,
  decided_at        timestamptz,
  completed_at      timestamptz,
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reconcile_runs_tenant_created ON reconcile_runs (tenant_id, created_at DESC);
-- At most one open run per tenant. A blocked run stays open until an operator decides.
CREATE UNIQUE INDEX reconcile_one_open_run ON reconcile_runs (tenant_id)
  WHERE status IN ('STAGING', 'QUEUED', 'ANALYZING', 'BLOCKED', 'APPROVED', 'APPLYING');

-- Staging for the nightly diff, filled by COPY and only ever read by run id.
CREATE TABLE reconcile_staging (
  run_id       uuid NOT NULL,
  tenant_id    text NOT NULL,
  employee_id  text NOT NULL,
  full_name    text NOT NULL,
  email        text NOT NULL,
  phone        text NOT NULL,
  manager_id   text,
  office_code  text NOT NULL,
  shift_code   text,
  version      bigint NOT NULL,
  hr_hash      text NOT NULL,
  bucket       smallint NOT NULL,
  row_no       int NOT NULL
);
CREATE INDEX reconcile_staging_bucket ON reconcile_staging (run_id, bucket, employee_id);
CREATE INDEX reconcile_staging_employee ON reconcile_staging (run_id, employee_id);
