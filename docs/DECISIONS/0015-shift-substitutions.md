# ADR 0015 Shift substitutions: request, manager decision, coverage binding and lock order

## Status

Accepted (Issue #105, architect-approved plan D51-1 to D51-12). Extends ADR 0014 (site work
schedules: immutable publication, coverage projections) and ADR 0008 (manager graph and lock
order). Pending architecture review of PR for MVP-051.

## Context

An employee assigned to a future published shift sometimes cannot work it. The employee's manager
should be able to give the shift to another of their reports, after checking that this person can
work it, without editing or re-publishing the schedule: the published schedule is evidence that
employees and administrators rely on (ADR 0014). DivalHR has no manager role; managers exist only
through employment history (MANAGER assignments), which changes concurrently, as do placements,
separations and schedules.

## Decision

### Requests, decisions and a pending projection (D51-3)

- V27 adds append-only evidence: `people.work_schedule_substitution_request` (slot, schedule,
  original employee, the single qualifying employment, reason, instant, verified subject) and
  `people.work_schedule_substitution_decision` (outcome, deciding manager, and for an approval the
  substitute and their employment; reason, instant, verified subject). Neither is ever updated,
  deleted or truncated.
- `people.work_schedule_substitution_pending` is a guarded projection with one row per undecided
  request of an operational schedule and at most one per slot. It is inserted with the request and
  removed by the decision or by the schedule's cancellation or replacement.
- The public state is derived at one server instant: `PENDING` (pending, not started), `APPROVED`,
  `REJECTED`, `EXPIRED` (pending, started) or `VOIDED` (neither pending nor decided: the schedule
  was cancelled or replaced). A new request is allowed after a rejection; at most one approval per
  slot (`work_schedule_substitution_decision_one_approval`), so a substituted shift is never
  substituted again.

### Coverage is bound to the original or to the approved substitute (D51-4)

- The slot and schedule tables are never written. `people.work_schedule_slot_coverage` gains
  `decision_id` and a generated `published_employee_id` (the employee when `decision_id` is null).
  Three keys bind every row: to the immutable slot, schedule and instants
  (`work_schedule_slot_coverage_slot`); without a decision, to the slot's published employee
  (`work_schedule_slot_coverage_published`); with one, to that decision's slot, schedule and
  substitute (`work_schedule_slot_coverage_substitution`, which a rejection, having no substitute,
  can never satisfy).
- The coverage guard (`work_schedule_slot_coverage_guard`) never allows an update; allows an
  original row only while the slot has no approval; and allows removing the original row only once
  the slot has an approval, and any row once the schedule is terminal. The V26 exclusion
  `work_schedule_slot_employee_no_overlap` stays the authority for every effective assignment,
  substitutes included.
- Inside one transaction an approval inserts the decision first, then deletes the original row,
  inserts the substitute's row, deletes the pending row and runs the deferred checks immediately,
  before the audit record, the outbox event and the idempotency completion. The decision comes
  first, not last, because the database guards require it: coverage cannot move without its
  evidence. The transaction is atomic, so the order is invisible outside it.
- Deferred consistency (`work_schedule_substitution_exact`, per schedule, on every table involved):
  no request is both pending and decided; an undecided request without pending row belongs to a
  terminal schedule; a terminal schedule has no pending row; every coverage row of an operational
  schedule is bound to its slot's approval, or to nothing when there is none. The V26 count check
  (`work_schedule_coverage_exact`) still holds: one row per assigned slot.
- A cancellation or replacement removes the schedule's pending rows together with its coverage,
  original and substituted alike; request and decision evidence stays, and undecided requests read
  as `VOIDED`. Nothing carries over to a successor. A replacement's evaluation ignores every
  coverage row of the schedule being replaced, substitutes included (ADR 0014), so the substitute
  may be assigned the same instants in the replacement.

### Authority is evaluated, never stored (D51-1)

- A caller is a request's manager when the request's stored employment has an active,
  non-superseded MANAGER assignment naming the caller's linked employee on the shift's site-local
  start date (`start_local`, the schedule's site time zone). It is evaluated on every inbox page,
  candidate search, decision and replay, so an employment-history change reroutes a pending request
  at once. Tenant administrators and MVP-041G leave-approval substitutes have no authority.
- The qualifying employment is the single employment that covers every local date of the shift
  and is placed at the schedule's site on its start date; zero or several fail closed, never
  chosen arbitrarily. A request also needs a manager on that date.
- The database checks the same rules independently: a request names its employee's original slot
  and a qualifying, managed employment (`work_schedule_substitution_request_employment_qualifies`);
  a decision is taken by the request employment's MANAGER on the shift's date
  (`_decision_authority`); an approved substitute has a qualifying employment reporting to the same
  manager and no scheduled or effective separation before the shift ends (`_decision_substitute`);
  the decision's shape excludes the original employee and the manager as substitute
  (`_decision_shape`); the window (operational, not started, still pending) holds for both
  (`_request_window`, `_decision_window`).

### Substitutes (D51-2)

- A candidate differs from the original employee and the manager, reports to the manager on the
  shift's date, has exactly one qualifying employment and it reports to that manager, is not
  separated before the shift ends, and has no overlapping operational assignment at any site
  (half-open instants). These are the MVP-050 eligibility rules made stricter where the
  substitution needs one employment. The candidate search and the decision-time re-evaluation use
  the same SQL (`JdbcWorkScheduleSubstitutionRepository`), so a candidate shown and a substitute
  accepted follow one definition.
- The search is `GET …/candidates?query=` as specified. The query is a name or employee-number
  fragment: it is never logged, audited (only a digest) or echoed, the cursor binds only its digest,
  and both edge proxies already drop query strings from access logs (SEC-001). The established
  `POST /employees/search` convention exists for the administrators' directory; this endpoint
  follows the approved contract and relies on those controls instead.

### Lock order (D51-7)

Every operation and the order of the locks it takes (a number is a position in the global order;
a gap means the step is not taken):

| Operation | 1 manager graph | 2 scheduling | 3 employments `FOR SHARE` / `FOR UPDATE` | 4 link `FOR SHARE` | 5 schedule `FOR UPDATE` | 6 request `FOR UPDATE` |
| --- | --- | --- | --- | --- | --- | --- |
| Substitution request (new) | ✓ | ✓ | share, original's | ✓ | ✓ | |
| Substitution decision | ✓ | ✓ | share, request's and substitute's, UUID order | ✓ | ✓ | ✓ |
| Decision replay | ✓ | | | ✓ | | |
| Publication (MVP-050A) | | ✓ | share | | | |
| Cancellation (MVP-050B) | | ✓ | | | ✓ | |
| Replacement (MVP-050B) | | ✓ | share, after the schedule (see below) | | ✓ | |
| Manager change (MANAGER assignment) | ✓ | | update | | | |
| Other employment change | | | update | | | |
| Separation | ✓ | | update | | | |
| Leave decision (MVP-041B) | ✓ | | share | ✓ | | |

Every row but one acquires its locks in increasing column order. The exception is the MVP-050B
replacement, which locks the original schedule before its employees' employments (ADR 0014); it is
safe because every operation that locks a schedule row `FOR UPDATE` (cancellation, replacement,
substitution request and decision) holds the tenant's scheduling lock first, and that lock is
exclusive: no two of them hold steps 3 and 5 at the same time. The operations without the
scheduling lock (employment changes, separations, leave decisions) never lock a schedule or a
request. Substitution is the only operation holding both the manager-graph and the scheduling lock,
and it takes them in that order; nothing takes them in the other order. The identity module's link
writes (link creation and removal) take only the access-link lock and identity rows, never a lock this table lists, and separations take the manager graph before any of them. So no
cycle of waits can form. The database guards take the schedule row `FOR UPDATE` too (step 5), after
everything the application already holds.
The concurrency tests race decisions against each other, against cancellation and replacement in
both orders (behind the scheduling lock), against manager changes in both orders (behind the
manager-graph lock), and against the substitute's placement change and separation in both orders
(behind the employment row); each settles to one valid outcome.

### Replays (D51-7)

- A request replays only while the caller's current link names the original employee (`403
  EMPLOYEE_LINK_REQUIRED` without a link, `409 IDEMPOTENCY_KEY_REUSED` without params when linked
  to another employee).
- A decision replays only while the caller's current linked employee is still the request's
  effective manager; otherwise it is the same `404 WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND` as an
  unknown request, and no stored response or identifier is returned.

### Privacy, audit and events (D51-8)

- Fail-closed disclosures: `work-schedule-substitution.self-list`, `.manager-list` and
  `.candidate-list` (metadata: view, page, count; IDs and, for the search, the query's digest only
  inside the digest). Writes: `.request`, `.approve` and `.reject` (metadata: IDs, states,
  authority `MANAGER`; the reason and the subject only inside the state digest).
- Events `people.work-schedule-substitution.requested.v1`, `.approved.v1` and `.rejected.v1` carry
  IDs and the state only.
- Reasons appear only in the requester's own history and the deciding manager's inbox. The
  substitute's own schedule shows the shift with `assignmentSource: SUBSTITUTION` and never the
  original employee or a reason; administrators see the published employee, the effective
  employee and the approval's IDs and instant, never a reason.

### Package boundary

- The scheduling package may now also use `ManagerGraphLock` (the shared lock order) and
  `EmployeeSearchKey` (the directory's name normalization) from the people module; the architecture
  test lists them. It still never depends on the leave package.

## Consequences

- An employee's operational schedule (`GET /me/work-schedule`) reads the coverage projection, so a
  given-away shift leaves the original employee's view and appears in the substitute's.
- Rolling back V27 is refused once any request, decision or pending row exists, or when the
  coverage is not exactly the original assignments (`db/rollback/V27__rollback.sql`).
- Out of scope (D51-12): substitute acceptance, employee choice of the substitute, swaps, chains,
  cancellation or amendment of a pending request, administrator approval, leave-substitute
  authority, notifications, recurring or bulk substitutions, changes to the shift itself,
  attendance and payroll effects.
