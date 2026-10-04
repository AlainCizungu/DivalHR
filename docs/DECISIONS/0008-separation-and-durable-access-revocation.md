# ADR 0008 Separation and durable access revocation

## Status

Accepted (Issue #49, architect approval with amendments A22-1 to A22-6).

## Context

MVP-022 lets a tenant administrator separate an employee: the employment ends after an inclusive
last day, direct reports move to another manager or lose their reporting line, follow-up reminders
are created, and the person's DivalHR access ends. Three modules are involved: `people` owns the
employment history (ADR 0007), `identity` owns memberships and the identity-provider client, and
Keycloak holds the sign-in. Before MVP-022 nothing linked an employee record to a membership,
memberships were immutable (V8/V10), and the only Keycloak operations were the narrow
provisioning calls of ADR 0006.

The design had to guarantee that:

- access to DivalHR data ends at an exact instant that does not depend on Keycloak being reachable;
- Keycloak is eventually told, durably and idempotently, without ever disabling the wrong identity;
- the separation, its report changes, its reminders and the revocation commit or fail together;
- the employment history stays append-only and reversible while the separation is only scheduled.

## Decision

### Separation in the employment history

- A separation is a `SEPARATION` change in `people.employment_change` with its details in
  `people.employment_separation` (last day D, closed reason, access timing, report plan, counts,
  state `SCHEDULED` → `EFFECTIVE` or `CANCELLED`). The employee's rows after D are superseded
  through the ADR 0007 lineage, and `people.employment.effective_to` is set to D by a guarded
  update (`employment_end_governed`): it changes only together with a matching SEPARATION change
  or its exact cancellation.
- Database backstops: assignments stay within the employment, a manager row requires the manager
  to be employed over the whole row (`employment_manager_employed`, deferred, under the
  manager-graph lock), and the change-shape trigger has branches for a separation, a
  separation-bound report change (`MANAGER_SEPARATED`) and the exact reversal of either.
- Future effects on the employee's own timeline after D block the separation until cancelled
  through MVP-021. Blockers come from the active timeline and its lineage, so corrections and
  restorations of future rows are found too (A22-3).
- Direct reports: every contiguous interval naming the separated manager after D is rewritten by
  one lineage-backed change per affected row, bound to the separation; later intervals naming
  another manager are kept. The limit (200) counts intervals; the preview digest covers every
  interval's row IDs and the count (A22-2). Cancelling a scheduled separation reverses the
  separation change and every bound change atomically.

### Access: a link, a revocation and the membership gate

- `identity.employee_access_link` links an employee to one membership (one active link per
  employee and per membership). It is created by an exact-address lookup (POST body only) and
  cannot be removed while a separation holds it.
- `identity.access_revocation` records when the linked membership's access ends (`effective_at`,
  the start of D+1 in the organization's time zone, or the commit instant for `IMMEDIATELY`).
  `IMMEDIATELY` is allowed only when D is today or earlier and always needs the
  `IMMEDIATE_ACCESS_REMOVAL` acknowledgement; a retroactive separation removes access at once
  (A22-1). A composite key and `CHECK (membership_role = 'employee')` make a tenant administrator
  impossible to target.
- `tenant_membership` stays immutable. The membership gate's single predicate becomes "no
  non-cancelled revocation of the same tenant and membership with
  `effective_at <= statement_timestamp()`", shared by the gate, the session endpoint and the access
  review, so they never disagree. The predicate correlates the tenant explicitly (R22-1): tenant
  isolation never depends on membership IDs being globally unique. Denial is exact and independent
  of any job.
- `AccessRevocationJobs` (identity) moves due revocations to `IDP_PENDING`, then calls the
  extension's new operation `PUT …/tenants/{tenantId}/identities/{subject}/access-revocation`
  (disable, remove online and offline sessions, set not-before). Retries back off from 1 to 60
  minutes over 10 attempts, then `MANUAL_INTERVENTION`, from which an administrator can retry.
- **Revalidation before every call (A22-5).** Under the revocation row lock the worker rechecks
  that the revocation is due, in the expected state and version, that its original link still
  binds the same employee and membership, and that the membership is still an employee
  membership of the same tenant. The subject is read from that membership at that moment. If any
  check fails, Keycloak is not called and the row moves to `MANUAL_INTERVENTION` with the closed
  code `STALE_LINK`, `MEMBERSHIP_CHANGED` or `TENANT_MISMATCH`. Independently, the extension
  refuses (`409 REVOCATION_REFUSED`, unchanged) any identity that is not an employee identity it
  created for the path tenant.

### Transaction boundary and module ownership (A22-4)

- `EmployeeSeparationService` (people) is the only transaction coordinator for separation commits
  and cancellations. The identity ports it calls (`EmployeeAccessLinks`, in
  `platform.access`) are `@Transactional(propagation = MANDATORY)`: they join that transaction and
  fail without one. `AccessPortArchitectureTest` enforces the rule. Identity's own jobs and link
  endpoints open their own transactions and are never called from `people`.
- **Integrity exception.** V15 adds two cross-schema foreign keys, all from `identity` to
  `people`: `employee_access_link_employee` (to `people.employee`) and
  `access_revocation_separation` (to `people.employment_separation`); no `people` table references
  `identity`. They are a deliberate
  modular-monolith integrity exception: the database may reference another module's keys so
  that a dangling link or revocation is impossible, but application code reaches the other module
  only through its ports and never reads or writes its tables. A test scans every repository's SQL
  for the other module's schema names. Extracting a module later replaces each such key with an
  event-driven consistency check.

### Global lock order (A22-4)

Every path takes locks in this order and never takes an earlier lock after a later one:

| Step | Lock                                                                                         |
| ---- | -------------------------------------------------------------------------------------------- |
| 0    | Idempotency reservation                                                                      |
| 1    | Manager-graph advisory lock `people.manager-graph:{tenant}`                                  |
| 2    | Employment rows, ascending ID (the employee's and every affected report's)                   |
| 3    | Access-link advisory lock `identity.access-link:{tenant}`                                    |
| 4    | Link row `FOR UPDATE`, membership `FOR SHARE`, then the revocation row                       |
| 5    | Separation row, then task rows                                                               |
| 6    | Deferred constraint checks, then audit and outbox inserts                                    |

The revocation worker is the one exception at step 4: it holds no step 1–3 lock, locks the
revocation row first and then reads the link and membership with `FOR SHARE NOWAIT`; a conflict
releases the lease for the next run. The separation cancellation takes the revocation row with
`FOR UPDATE NOWAIT`; a conflict is the retryable `503 EMPLOYMENT_CHANGE_TIMEOUT`. Neither can wait
in a cycle. `SeparationConcurrencyIntegrationTest` runs the combinations on real PostgreSQL and
asserts no deadlock and all-or-nothing state.

### Evidence

Audit metadata holds only allow-listed counts, versions, closed state transitions, attempts and
outcome codes; the integrity hash (the confirmed preview or cancellation digest, or a digest of
identifiers) goes to `after_state_sha256` (A22-6). Outbox data is identifiers only. Keycloak
outcomes are recorded on the revocation row and in the audit, never in the outbox.

## Consequences

- Access to DivalHR data ends exactly on time even when Keycloak is down; the remaining window is
  a signed-in shell whose every API call is refused until the job completes.
- A Keycloak disablement is never undone by SQL or by the V15 rollback script; reactivating a
  person is an identity-administration act outside MVP-022.
- Tenant administrators cannot be separated through this flow; their offboarding is a later
  governance story.
- Changing an organization's time zone (not possible today) would have to recompute scheduled
  revocation instants.
- Corrections of a separation after it takes effect, rehires and an HR role are out of scope.
