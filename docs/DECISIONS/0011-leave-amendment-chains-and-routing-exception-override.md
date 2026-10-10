# ADR 0011 Leave amendment chains and routing-exception override authority

## Status

Accepted (Issue #92, architect-approved specification D41DE-1 to D41DE-10). Extends ADR 0010.

## Context

MVP-041A to MVP-041C let an employee submit, and cancel, a pending leave request that its routed
approver decides once (ADR 0010). Two gaps remained:

- an employee who needs other dates, another amount or another policy had to cancel and submit
  again, losing the link between both requests;
- a `MANAGER`-routed request whose employment has no manager on its first day was in no inbox and
  stayed pending for ever (ADR 0010, "No fallback").

Both had to be solved without deleting or rewriting any request, decision or policy route, under
concurrency with decisions, cancellations, manager changes and separations.

## Decision

### Amendment by replacement (MVP-041D, D41DE-1 to D41DE-3, V22)

- Only the employee of the caller's current active link may amend their own `PENDING` request.
  Amending means replacing: in one transaction the original moves to the fifth state, `AMENDED`
  (terminal), and a new `PENDING` replacement is inserted with the append-only
  `people.leave_request_amendment` row (both request IDs, the reason in its locale, the time and
  the verified subject).
- The replacement passes every current rule of a new request on the organization's business date
  (policy coverage, dates, maximum interval, employment coverage, minimum service, amount and
  overlap); its policy may change, and with it its route. Only the original is excluded from the
  overlap: it leaves `PENDING` before the replacement is inserted, so the exclusion constraint
  (`PENDING` and `APPROVED`) never sees both; any failure rolls both back.
- A replacement is an ordinary pending request: it may be decided, cancelled or amended again, so
  amendments form chains. One amendment per original and one per replacement
  (`leave_request_amendment_original_unique`, `_replacement_unique`); no row of a chain is ever
  rewritten.
- Terminal evidence stays exactly one matching kind per request (`leave_request_decided`, its
  function extended): `AMENDED` requires the one amendment naming the request as the original, and
  no decision or cancellation. `leave_request_amendment_consistent` (deferred) requires, at the
  amendment's own commit, the original `AMENDED`, the replacement `PENDING` and both the same
  employee's.
- Lock order (ADR 0008 global order): idempotency (0); an unlocked read of the original to learn
  its employee, then that employee's employments touched by the original or the replacement
  interval, `FOR SHARE` in UUID order (2); the caller's link and membership `FOR SHARE` (4); the
  original `FOR UPDATE`, bound to the tenant and the linked employee (5); then the rules, the
  transition, the replacement, the evidence, the deferred checks, audit and outbox (6). No
  employment lock is taken after the request lock. An amendment and a decision or cancellation of
  the same request serialize on its row; the second observes the terminal state.
- Replay re-resolves the current link and requires the stored original to be that employee's,
  carrying its amendment, before any stored receipt is read.

### Routing exceptions and the override authority (MVP-041E, D41DE-1, D41DE-2, D41DE-4)

- A routing exception is derived, never stored: a `PENDING` request whose immutable policy route
  is `MANAGER` and whose employment no active, non-superseded `MANAGER` line covers on its first
  day (the same effective-dated rule as the manager inbox, for any manager). It is re-evaluated on
  every list and every first decision; there is no flag to set or clear. A manager assigned,
  corrected or restored for that day takes the request out of the queue and into that manager's
  inbox at once.
- Only a verified tenant administrator with exact MFA and an active membership lists and decides
  exceptions (`@TenantAdminOperation`). Unknown, foreign, non-`MANAGER` and currently managed
  requests are the same `404 LEAVE_REQUEST_NOT_FOUND`; a terminal `MANAGER` request is its own
  `409` (already decided, cancelled or amended).
- A decision records who decided under the route: `people.leave_request_decision.decision_authority`
  (`MANAGER`, `TENANT_ADMIN`, `TENANT_ADMIN_OVERRIDE`), immutable, backfilled from the route for
  every existing decision. Exactly three shapes exist (`leave_request_decision_authority_shape`):
  route `MANAGER` with authority `MANAGER` and the deciding manager; route `TENANT_ADMIN` with
  authority `TENANT_ADMIN` and no manager; route `MANAGER` with authority `TENANT_ADMIN_OVERRIDE`
  and no manager. The policy version and the route are never rewritten.
- Lock order: idempotency (0); the tenant's exclusive manager-graph lock (1), so no manager can be
  added or restored meanwhile; the request's employment `FOR SHARE` (2); the request `FOR UPDATE`
  (5); then route, state and manager absence, and the decision (6). No self-link lock. A manager
  change and an override, or a manager decision and an override, serialize on the manager-graph
  lock in either order with exactly one valid outcome.
- Replay re-checks role, membership and MFA (the interceptor) and that the stored decision is this
  tenant's override; the current manager graph is not re-evaluated, so a manager assigned after the
  historical override does not invalidate its exact replay.

### Evidence and privacy (D41DE-7)

- Audit `leave-request.amend` (identifiers of the amendment, both requests, the employee, both
  employments and policy versions, and the states) and events `people.leave-request.amended.v1`
  (identifiers and the state transition).
- Audit `leave-request.routing-exception.approve` / `.reject` (the decision identifiers, the
  original route, the authority and the states) and events
  `people.leave-request.routing-exception-approved.v1` / `-rejected.v1`. The fields of the existing
  approved/rejected v1 events are unchanged.
- The full after-state digests include the reason and the actor; the reason, its locale, names,
  employee number, dates, amount, subject and link or manager identity never enter logs, metrics,
  traces, audit metadata, events, URLs or browser storage. The employee's history shows amendment
  chains (both IDs, the reason, the time) and override outcomes exactly like other decisions; never
  the amending subject, the deciding subject, the decision authority or the administrator.

### Rollback

`db/rollback/V22__rollback.sql` restores V21 exactly (signature-tested) only while no amendment
exists, no request is `AMENDED` and no decision has the `TENANT_ADMIN_OVERRIDE` authority; it keeps
every decision and cancellation, has no override and never edits `flyway_schema_history`.

## Consequences

- ADR 0010's "No fallback" ends: a manager-less `MANAGER` request is resolved by a tenant
  administrator's explicit, audited override, never automatically.
- Overrides take the tenant's manager-graph lock like every manager decision and manager-history
  write: one at a time per tenant, short transactions.
- Withdrawal of approved leave (MVP-041F) and delegation or substitute approvers (MVP-041G) remain
  later stories; there is no escalation timer, automatic decision or notification.
