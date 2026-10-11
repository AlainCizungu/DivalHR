# ADR 0013 Leave approval substitutes beside the primary manager

## Status

Accepted (Issue #97, architect-approved specification D41G-1 to D41G-10). Extends ADR 0010, ADR
0011 and ADR 0012. Final story of the initial Leave Management phase.

## Context

A `MANAGER`-routed leave request belongs to the employee's active manager on its first day (ADR
0010); when there is none, a tenant administrator may override (ADR 0011). A manager who is away
leaves their reports' requests waiting: nobody else may decide them, and an override is not
available because a qualifying manager exists.

A substitute must not weaken the evidence of who decided under which authority, must never let an
employee approve their own leave, and must not become a delegation framework (chains, calendars,
notifications) in this story.

## Decision

### One effective-dated, append-only appointment per manager (D41G-2, V24)

- `people.leave_approval_substitution` (append-only) names one manager, one different substitute,
  inclusive bounded dates and a reason (the shared reason grammar version 1), with both employees in
  the tenant (tenant-composite keys). There is no status column: the state (`SCHEDULED`, `ACTIVE`,
  `EXPIRED`, `REVOKED`) is derived on every read from the organization's business date and the
  existence of a revocation.
- `people.leave_approval_substitution_revocation` (append-only, one per appointment) revokes it at
  database time, whatever its dates. Prior decisions stay valid.
- At most one **non-revoked** appointment covers a manager on any date (R98-1). The exclusion
  constraint (`leave_approval_substitution_coverage_no_overlap`, btree_gist) sits on an operational
  projection, `people.leave_approval_substitution_coverage`, never on the historical rows: one
  coverage row per unrevoked appointment, keyed to that appointment's exact tenant, manager and
  dates by a composite foreign key. Creation inserts the appointment and its coverage; revocation
  inserts the revocation and deletes the coverage; each in one transaction under the manager-graph
  lock. A revocation therefore frees the remaining dates for a replacement, while the revoked
  appointment keeps its original dates and reason as evidence. The historical rows never gain a
  status column and are never rewritten.
- Database safeguards: coverage is never updated or truncated, is deleted only once its
  appointment is revoked and never inserted for a revoked one
  (`leave_approval_substitution_coverage_guard`); an appointment commits only with its coverage
  (`leave_approval_substitution_covered`, deferred); a revocation commits only once the coverage is
  gone (`leave_approval_substitution_revocation_uncovered`, deferred). The projection is never the
  source of truth for authority: inbox and decision read the appointment and the absence of a
  revocation.

### The substitute's authority (D41G-1, D41G-4)

- A substitute may list and decide a request only when it is `PENDING`, its immutable route is
  `MANAGER`, its employment has a qualifying primary manager on its first day (the ADR 0010 rule),
  that manager has an unrevoked appointment covering the organization's current business date, the
  caller's current active self-link resolves to the appointed substitute, and the request is not the
  substitute's own. Anything else is the established indistinguishable `404`.
- The primary manager's authority is unchanged: both may act while the appointment is effective,
  and the first valid decision wins (one decision per request, under the request's row lock).
- There is no second inbox and no second decision endpoint. The manager inbox merges the caller's
  own reports (`approvalCapacity: PRIMARY`) with the primary manager's reports the caller
  substitutes for (`SUBSTITUTE`), de-duplicated by request, in the established keyset order; the
  cursor is bound to the caller and their current link, and authorization is recomputed on every
  page. The decision body is unchanged: the server, never the client, chooses the authority — the
  caller's own reporting line first, otherwise the effective appointment.
- Routing exceptions are unchanged: a request with a qualifying primary manager is not an exception,
  whether or not a substitute exists, and a substitute (an employee) cannot override.

### Decision evidence (D41G-2, D41G-7)

- `decision_authority` gains `MANAGER_SUBSTITUTE`; the immutable, nullable
  `delegated_from_manager_employee_id` (same-tenant employee key, null for every existing decision)
  names the primary manager. The exact shapes: `MANAGER`/`MANAGER` with the primary manager;
  `MANAGER`/`MANAGER_SUBSTITUTE` with the substitute as `manager_employee_id` and a different
  primary manager as delegated-from; `MANAGER`/`TENANT_ADMIN_OVERRIDE` and
  `TENANT_ADMIN`/`TENANT_ADMIN` with neither. The route stays `MANAGER`.
- The deferred `leave_request_decision_consistent` also refuses a substitute decision unless the
  delegating manager is the request's active line on its first day, an unrevoked appointment of
  that manager names the substitute, and the substitute is not the requester. Whether the
  appointment covers today is the application's rule (the business date comes from the
  organization's time zone), checked under the manager-graph lock.
- Audit actions are unchanged (`leave-request.approve` / `.reject`) with the safe authority and the
  appointment ID in metadata; events are new types, `people.leave-request.substitute-approved.v1`
  and `-rejected.v1`, so the v1 manager and administrator contracts do not change.

### Transactions and locks (D41G-5)

- Appointment and revocation: idempotency (0), the tenant's manager-graph lock (1), every
  employment of the manager and the substitute overlapping the period `FOR SHARE` in UUID order (2),
  then the appointment row (revocation: `FOR UPDATE`), the evidence, audit and outbox.
- Substitute decision: idempotency (0), manager-graph lock (1), the request's employment `FOR SHARE`
  (2), the caller's link and membership `FOR SHARE` (4), the request `FOR UPDATE` (5), then the
  authority, evidence, checks, audit and outbox (6) — the ADR 0010 manager order unchanged.
  Because creation, revocation, manager-line changes, overrides and every manager decision take the
  same lock, each pair settles to exactly one valid outcome in either order.
- Replay of a substitute decision rechecks membership and role (interceptor), the current self-link
  and that the stored decision was taken by that linked employee as a substitute; a later expiry or
  revocation does not invalidate a historical terminal decision.

### Administration (D41G-3, D41G-6, D41G-8)

- `POST /api/v1/leave-approval-substitutions`, `GET` (newest-first keyset list, fail-closed
  `leave-approval-substitution.read` disclosure) and `POST …/{id}/revocation`, all
  `@TenantAdminOperation` with exact MFA, `private, no-store`, sharing the leave-approval
  read/write limits (no new configuration). Creation requires both employees employed on every day
  of the period, the manager to have an active MANAGER line overlapping it, and no overlapping
  appointment. Stable errors, empty params: `LEAVE_APPROVAL_SUBSTITUTION_NOT_FOUND`,
  `_SAME_EMPLOYEE`, `_EMPLOYMENT_PERIOD`, `_NO_REPORTING_LINE`, `_OVERLAP`, `_ALREADY_REVOKED`
  (unknown and foreign appointments are the same `404`; an already-revoked one is its own `409`
  except the exact replay).
- Reasons are Restricted HR, returned only in the administrators' list; never in non-admin
  responses, receipts, logs, metrics, traces, audit metadata, events, URLs or browser storage. The
  employee's history is unchanged: outcome, reason and time, never an approver, capacity or
  appointment.

### Rollback

`db/rollback/V24__rollback.sql` restores V23 exactly (signature-tested) only while no appointment,
coverage row, revocation or `MANAGER_SUBSTITUTE` decision exists; no override; it never edits
`flyway_schema_history`.

## Consequences

- ADR 0010's "a `MANAGER` request belongs to its manager" now reads "to its manager and, while an
  appointment is effective, to that manager's substitute as well".
- A revoked appointment's remaining dates can be covered by a replacement at once; an expired
  appointment keeps its coverage (it was never revoked), so its past dates stay taken.
- No self-delegation, chain, sub-delegation, concurrent substitutes, absence calendar, automatic
  activation, escalation, notification, site/department limits, balances, payroll or AI.
