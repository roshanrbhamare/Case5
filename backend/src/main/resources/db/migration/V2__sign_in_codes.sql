-- One-time sign-in codes for employees. Anyone the HR feed sent can sign in with the email HR
-- gave us, so no login accounts or passwords have to be created for them.
-- Only a keyed hash of each code is stored; a code expires, allows a few attempts and works once.
CREATE TABLE sign_in_codes (
  id           uuid PRIMARY KEY,
  tenant_id    text NOT NULL,
  employee_id  text NOT NULL,
  code_hash    text NOT NULL,
  attempts     int NOT NULL DEFAULT 0,
  expires_at   timestamptz NOT NULL,
  used_at      timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, employee_id)
);

CREATE INDEX sign_in_codes_open ON sign_in_codes (tenant_id, employee_id, created_at DESC) WHERE used_at IS NULL;

-- Sign-in looks employees up by the email HR sent (stored lowercase by the validator).
CREATE INDEX employees_active_email ON employees (email) WHERE status = 'ACTIVE';
