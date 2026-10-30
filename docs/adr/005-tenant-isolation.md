# ADR 005: Tenant isolation

**Status:** accepted

**Decision.**
1. **The tenant never comes from the request.** It comes from the credential: the JWT `tid` claim for people, the API-key lookup for the HR system. A caller cannot ask for another client's data, because there is no parameter to ask with.
2. **Every query filters by `tenant_id`.** Every tenant-owned table carries `tenant_id` as the leading key of its primary key or index, and every repository method takes the tenant and uses it in its `WHERE` clause.
3. **Tested through the API.** An Acme admin asking for a Globex employee gets 404. A Globex operator sees none of Acme's nightly runs, even with the exact run id. An HR API key writes only into its own tenant (`ApiIT.TenantIsolation`).

**Left out on purpose:** Postgres row-level security as a second wall. It needs a separate restricted database role, a per-transaction session setting and owner-privileged functions for cross-tenant worker jobs. That is a lot of moving parts for this scope; it is the next step if a stricter guarantee is required.

**Roles:**
- `EMPLOYEE`: own profile and rides. Signs in with a one-time code for the email HR sent, so the HR record is the account and an exit blocks sign-in.
- `ADMIN` (transport admin): reads the roster and edits transport fields.
- `OPERATOR` (sync operator): uploads files, approves or rejects blocked runs, uses the test feed; sees masked contact details.
- `HR_SYSTEM` (API key): sends events and files only.
