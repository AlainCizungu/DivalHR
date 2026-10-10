# ADR 0010 Leave approval routing, terminal decision evidence and lock order

## Status

Accepted (Issue #89, architect-approved specification D41B-1 to D41B-10).

## Context

MVP-041A lets an employee submit a pending leave request under a policy version whose immutable
`approval_route` is `MANAGER` or `TENANT_ADMIN` (MVP-040A). MVP-041B lets the routed approver
approve or reject it once, with a reason the employee reads. The design had to guarantee that:

- the route and the approver are decided by server-side data only, never by roles in a token, the
  browser or a request field;
- a manager relationship that changes later (a scheduled change, a correction, a separation)
  reroutes a still-pending request, and nobody who lost the relationship can decide it or replay an
  earlier decision;
- exactly one immutable decision moves a request to a terminal state, under concurrency, and an
  approved request keeps blocking overlapping requests while a rejected one releases its dates;
- decisions serialize with employment-history writes and separations without deadlocks.

## Decision

### Route and approver (D41B-1)

- The route is the request's policy version `approval_route`. It never changes (policy versions
  are insert-only) and is never snapshotted or inferred.
- `TENANT_ADMIN`: any verified tenant administrator with exact privileged MFA and an active
  membership (the existing `@TenantAdminOperation` gate).
- `MANAGER`: the employee linked to the caller's own active employee-access link
  (`EmployeeAccessLinks.linkedEmployee`, role `employee`, password assurance) must be the
  `manager_employee_id` of the active, non-superseded `MANAGER` assignment of the request's
  employment that covers the request's `start_date`. No manager realm role exists.
- The relationship is evaluated on every inbox read, decision and idempotent replay. Tenant
  administrators never see `MANAGER` requests and managers never see `TENANT_ADMIN` requests.
- Unknown, foreign-tenant, wrong-route and non-report requests are one answer: `404
  LEAVE_REQUEST_NOT_FOUND` with empty params.
- No fallback: a `MANAGER` request whose employment has no manager on its first day is in no
  inbox and stays pending. Routing exceptions, delegation and administrator override are later
  stories (MVP-041E onward). Superseded by ADR 0011 (MVP-041E): such a request is a routing
  exception that a tenant administrator decides as an explicit override.

### Terminal decision evidence (D41B-2, V20)

- `people.leave_request.state` is `PENDING`, `APPROVED` or `REJECTED`. A transition guard
  (`leave_request_transition`) allows exactly one update, from `PENDING` to a terminal state,
  with every other column unchanged; deletes and truncation stay forbidden.
- `people.leave_request_decision` is append-only, one row per request
  (`leave_request_decision_request_unique`): outcome, route, the deciding manager's employee
  (required for `MANAGER`, absent for `TENANT_ADMIN`), the reason's locale and text (the
  decision-reason grammar version 1: an explicit code point list enforced identically by
  `LeaveReasonGrammar` and `people.leave_reason_valid`; see `DATA-MODEL.md`), the decision time and the verified subject.
- Two deferred constraint triggers bind the pair: a terminal request commits only with its
  matching decision, whether it was inserted terminal or moved there
  (`leave_request_decided`, after insert or update), and a decision only with its request in that state
  under its policy version's route with the exact route/manager shape
  (`leave_request_decision_consistent`). The application runs both `IMMEDIATE` before writing the
  audit record and the outbox event, so a named violation maps safely inside the transaction.
- `leave_request_no_overlap` now covers `PENDING` and `APPROVED` requests. A rejection releases
  the dates in the same commit as its decision row.
- The rollback (`db/rollback/V20__rollback.sql`) restores V19 exactly only while no decision
  exists and every request is pending, with no override, and never edits `flyway_schema_history`.

### Lock order (D41B-5)

Decisions follow ADR 0008's global order:

| Step | Manager decision                                                     | Tenant-administrator decision |
| ---- | -------------------------------------------------------------------- | ----------------------------- |
| 0    | Idempotency reservation                                              | Idempotency reservation       |
| 1    | Manager-graph lock `people.manager-graph:{tenant}`                   | —                             |
| 2    | The request's employment `FOR SHARE` (discovered by an unlocked read) | Same                          |
| 4    | The caller's link and membership `FOR SHARE`                         | —                             |
| 5    | The request `FOR UPDATE`, then route, relationship and state checks  | Same                          |
| 6    | Decision, transition, deferred V20 checks, audit, outbox, response   | Same                          |

- The manager-graph lock is the existing exclusive transaction lock. Leave reaches it through the
  People-owned `ManagerGraphLock`, which delegates to the one key definition in employment
  history, so the key cannot drift. Every manager-history write (change, cancellation,
  separation and the V14/V15 triggers) takes the same lock, so a manager change and a manager
  decision serialize in either order: the decision commits under the old relationship before the
  change, or observes the new relationship and is refused.
- Separations lock the employment `FOR UPDATE` after the manager-graph lock. A decision holding
  the employment `FOR SHARE` makes a separation wait; a decision waiting behind a separation reads
  the separated period. Approval requires the employment to still cover the request's whole
  interval (`409 LEAVE_REQUEST_NOT_ELIGIBLE`, reason `EMPLOYMENT_PERIOD`); rejection does not.
- Racing decisions on one request serialize on the request row: one commits, the others observe
  the terminal state (`409 LEAVE_REQUEST_ALREADY_DECIDED`).

### Replay (D41B-5, R88)

The R88 replay check of `IdempotentOperation` runs after the reservation and before any stored
body is read. A manager replay re-takes the manager-graph lock, re-resolves the current link (`403
EMPLOYEE_LINK_REQUIRED` without one) and re-checks the relationship on the request's first day
(`404` otherwise). A tenant-administrator replay re-checks that the stored decision is the
tenant's and was taken under `TENANT_ADMIN`; the interceptor has already re-checked role,
membership and MFA. A denied replay returns no stored response or identifier.

### Evidence and privacy (D41B-6)

- Audit actions `leave-request.approve` and `leave-request.reject`: metadata holds identifiers,
  the route and the prior and resulting states only; `after_state_sha256` covers the complete
  request and decision, reason and deciding subject included.
- Events `people.leave-request.approved.v1` and `people.leave-request.rejected.v1`: identifiers,
  route and resulting state only.
- Inbox pages are fail-closed disclosures (`leave-approval.manager-list`,
  `leave-approval.admin-list`): one audit record per page with `{schemaVersion, view, page,
  resultCount}` and a digest of the returned request IDs in order; no body without it.
- The reason is Restricted HR: stored only in the decision row, returned only to the requesting
  employee's own history and the approval flow, never in the decision receipt, the idempotency
  response, logs, metrics, traces, audit metadata, events, URLs or browser storage. The employee
  never sees who decided. No retention or deletion period is introduced.

### Employee cancellation (MVP-041C, Issue #91, V21)

- The employee of the caller's current active link may cancel their own `PENDING` request, once,
  with a reason (the decision-reason grammar version 1), before, on or after its first day. No
  administrator or manager cancellation; amendment is MVP-041D and routing exceptions MVP-041E.
- `CANCELLED` is a fourth state and a third terminal transition (`PENDING` to `CANCELLED`). The
  append-only `people.leave_request_cancellation` is its evidence (reason, locale, time, the
  verified subject, never returned).
- Terminal evidence is exactly one matching kind per request (`leave_request_decided`, its V20
  function extended): `PENDING` none; `APPROVED`/`REJECTED` their decision and no cancellation;
  `CANCELLED` its cancellation and no decision. `leave_request_cancellation_consistent` binds a
  cancellation to its cancelled request. The application runs every deferred check `IMMEDIATE`
  before the audit record and the outbox event.
- Lock order: idempotency (0), the caller's link and membership `FOR SHARE` (4), the request `FOR
  UPDATE`, bound to the tenant and the linked employee (5), then the evidence, the transition, the
  checks, audit and outbox (6). No manager-graph or employment lock, and nothing after the request
  row, so it cannot invert a decision's order. Decisions and cancellations of one request serialize
  on its row; the second observes the terminal state (`409 LEAVE_REQUEST_ALREADY_DECIDED` for a
  decision on a cancelled request or a cancellation of a decided one, `409
  LEAVE_REQUEST_ALREADY_CANCELLED` for a second cancellation).
- A cancellation removes the request from both inboxes (they read `PENDING` only) and releases its
  dates in the same commit (the overlap exclusion covers `PENDING` and `APPROVED`).
- Replay: after the reservation and before any stored body is read, the current link is
  re-resolved and the stored cancellation's request must belong to that employee.
- Rollback: `db/rollback/V21__rollback.sql` restores V20 exactly only while no cancellation exists
  and no request is `CANCELLED`.

### Amendment and routing exceptions (MVP-041D/E, Issue #92, V22)

See ADR 0011: amendment by replacement (`AMENDED`, append-only amendment chains), routing
exceptions and the `decision_authority` of every decision (`MANAGER`, `TENANT_ADMIN`,
`TENANT_ADMIN_OVERRIDE`). The lock orders, replay rules and evidence above are unchanged for
manager and tenant-administrator decisions and cancellations.

### Withdrawal of approved leave (MVP-041F, Issue #95, V23)

See ADR 0012: an `APPROVED` request may move once more, to `WITHDRAWN`, before its first day, by
its own employee; the approval decision above is kept unchanged beside the withdrawal evidence.

## Consequences

- One manager decision at a time per tenant, as for every manager-history write. Decisions are
  short transactions, so this is acceptable for the expected volumes.
- A later separation or manager change never decides or cancels a pending request automatically;
  an approved request whose employment later ends is not reconciled. Both belong to later stories.
- No balance, accrual, working-day, holiday, schedule, payroll or legal-entitlement calculation
  is introduced.
