# ADR 0012 Withdrawal of approved leave with preserved approval evidence

## Status

Accepted (Issue #95, architect-approved specification D41F-1 to D41F-8). Extends ADR 0010 and ADR
0011.

## Context

Until MVP-041E, a leave request changed state exactly once, from `PENDING`, and every terminal
state was final (ADR 0010, ADR 0011). An employee whose leave had been approved could not give it
back: the approved dates stayed blocked and the only remedy was outside the product.

Withdrawing approved leave must not erase or reinterpret what was approved: the approval decision
is evidence of who decided what under which route (ADR 0010, ADR 0011), and the withdrawal is a
separate fact by a separate person (the employee), later in time.

## Decision

### One more transition, after an approval (D41F-2, V23)

- A request has a sixth state, `WITHDRAWN` (terminal). The transition guard
  (`leave_request_transition`, replaced) allows the existing moves from `PENDING` to `APPROVED`,
  `REJECTED`, `CANCELLED` or `AMENDED`, and exactly one more: from `APPROVED` to `WITHDRAWN`. Every
  other column stays unchanged on that move too; `WITHDRAWN` never changes again.
- The approval decision row is kept unchanged (it is append-only since V20 and its
  `leave_request_decision_consistent` check runs only when a decision is inserted). A withdrawn
  request therefore carries two pieces of terminal evidence: its original `APPROVED` decision and
  one withdrawal. The exactly-matching terminal-evidence rule (`leave_request_decided`, deferred) is
  extended accordingly; every other state keeps its V22 evidence and has no withdrawal.
- The append-only `people.leave_request_withdrawal` holds one withdrawal per request (the reason in
  its locale, the time and the unchanged verified subject), with the shared reason grammar version
  1. A withdrawal commits only with its request `WITHDRAWN`, its `APPROVED` decision and no
  cancellation or amendment naming the request as the original
  (`leave_request_withdrawal_consistent`, deferred).
- `WITHDRAWN` is outside the overlap exclusion (`PENDING` and `APPROVED`, V20): the dates are
  released in the same commit as the evidence, and a transaction that sees the request before that
  commit cannot use them (the exclusion waits for the withdrawal's outcome).

### Who, when and in which order (D41F-1, D41F-4)

- Only the employee of the caller's current active link withdraws their own request
  (`@EmployeeSelfOperation`); unknown, foreign and another employee's requests are the same `404`.
- Only before the leave starts: the organization's business date (from its configured time zone,
  server-side) must be strictly before the first day, re-checked under the request's row lock. The
  history response carries that date (`asOf`) so the page offers the action only when it can
  succeed; a stale page is refused with `409 LEAVE_REQUEST_WITHDRAWAL_WINDOW_CLOSED` and refreshed.
- Lock order (ADR 0008): idempotency (0); a plain read of the request to discover its employment,
  locked `FOR SHARE` (2); the caller's link and membership `FOR SHARE` (4); the request `FOR UPDATE`,
  bound to the tenant and the linked employee (5); then the state, the internally consistent
  approval and the business date, the transition, the evidence, the deferred checks, audit and
  outbox (6). No employment lock after the request. A cancellation, amendment or decision needs the
  request `PENDING` under the same row lock, so none of them can race past or resurrect a withdrawal.
- Replay re-resolves the current link and requires the stored request to be that employee's and to
  carry its withdrawal before any stored receipt is returned.

### Evidence and privacy (D41F-6)

- Audit `leave-request.withdraw` (identifiers of the withdrawal, request, employee, employment,
  policy and version, and the original decision; the prior and resulting states) and the event
  `people.leave-request.withdrawn.v1` (identifiers and the transition). Existing event contracts are
  unchanged.
- The after-state digest covers the approval evidence, the withdrawal reason and the withdrawing
  actor. Reasons and identities never enter logs, metrics, traces, audit metadata, events, URLs,
  browser storage or idempotency bodies. The employee's history shows the approval and the
  withdrawal with both reasons, never the deciding or withdrawing subject, the authority, a manager
  or an administrator.

### Rollback

`db/rollback/V23__rollback.sql` restores V22 exactly (signature-tested) only while no withdrawal
exists and no request is `WITHDRAWN`; it keeps every V22 decision, cancellation and amendment, has no
override and never edits `flyway_schema_history`.

## Consequences

- ADR 0010's "a request changes once" now reads "a request changes once from `PENDING`, and an
  approved one may be withdrawn once before it starts".
- Approvers never act on a withdrawal: it is not a second approval workflow, and approval inboxes
  never regain a withdrawn request (they list `PENDING` requests only).
- Partial withdrawal, administrator withdrawal, withdrawal after the first day, balances, payroll,
  notifications and delegation (MVP-041G) remain later stories.
