# ADR 0019 Weekly time submission and approval: one routed manager, append-only actions

## Status

Accepted (MVP-070B/C, Issue #130, T70BC-1 to T70BC-15; PR #131, deployed to hr-dev at
`7050d1748595654f51182d0dbd7e9e51b947654d`). Its finality of approved time is narrowed by ADR 0020
(MVP-070D): a material attendance correction reopens an approval without changing it. Extends ADR
0018 (weekly draft timesheets).
Builds on ADR 0007 (effective-dated employment history and the manager graph), ADR 0008/0009
(employee access links and lock order) and ADR 0015 (manager decisions resolved again at decision
time).

## Context

MVP-070A produces evidence-grade weekly DRAFT timesheets. Time must now be declared by the
employee and decided by the right manager, with the reasons and history kept as evidence, before
reopening (MVP-070D) and export (MVP-070E) build on it. The decision must never approve values
that no longer match their sources, and the right manager can change while a timesheet waits.

## Decision

### Lifecycle (T70BC-1)

- A timesheet's state is `DRAFT`, `SUBMITTED` or `APPROVED`. Transitions: the linked employee
  submits a DRAFT (`SUBMITTED`); the routed manager approves (`APPROVED`) or returns it (`DRAFT`
  again, recorded as an immutable `RETURNED` action with the reason). There is no rejection, void,
  deletion or tenant-administrator decision.
- The state is exactly the state of the latest action: none or `RETURNED` is `DRAFT`, `SUBMITTED`
  is `SUBMITTED`, `APPROVED` is `APPROVED`. A submission opens the next cycle (1, 2, ...); each
  cycle has one submission and at most one terminal decision.

### Submission (T70BC-2, T70BC-4)

- Only the employee of the caller's active employee-access link, for their own timesheet; another
  employee's, a foreign, malformed or unknown timesheet is the same safe 404.
- The request carries the reviewed revision and generation version (both must still be current,
  otherwise `TIMESHEET_DECISION_STALE`), an explicit confirmation and an `Idempotency-Key`. The
  revision must have at least one READY row and no MISSING_ATTENDANCE, OPEN_ATTENDANCE or
  SOURCE_CHANGED row (otherwise `TIMESHEET_SUBMISSION_NOT_ALLOWED`).
- The SUBMITTED action binds the revision with its source digest and exact totals, the generation
  version, the cycle, the employee and the revision's single qualifying employment, the manager
  routed at submission, the verified subject and the instant.

### The route (T70BC-3)

- One manager per weekly timesheet: from the current effective-dated employment history, on the
  site-local Sunday of the week (the last included local date), the bound employment must be
  effective, the employee's employments effective that day must have exactly one manager, that
  manager must not be the employee and must hold an active `employee` membership and link (no
  revocation in effect). Otherwise there is no route (`TIMESHEET_APPROVAL_ROUTE_UNAVAILABLE`), and
  nobody is named.
- The route is never stored as authority. The queue, every manager read and every decision
  resolve it again (as ADR 0015's substitution decisions), so a manager who loses the route loses
  access at once and the new effective manager receives it. A submission records the manager
  routed then; a decision records the deciding manager.
- The employee never sees who approves. The access-link port gains one read,
  `activeEmployee(tenant, employee)`, so the schedule package never reads identity tables.

### Decisions (T70BC-5 to T70BC-7)

- The routed manager's queue lists the currently SUBMITTED timesheets routed to them now, newest
  submission first, with signed keyset pagination and optional site and week filters. It starts
  from the manager's reporting lines (`employment_assignment_reports`), then each report's
  submitted timesheets (`timesheet_submitted`): never a scan of the tenant's timesheets.
- Approval and return carry the reviewed revision, generation version, cycle and submission
  action, a confirmation and an `Idempotency-Key`. A timesheet that is no longer SUBMITTED is
  `TIMESHEET_DECISION_NOT_ALLOWED`; any other difference is `TIMESHEET_DECISION_STALE`.
- **An approval evaluates the timesheet's sources again** (exactly as a refresh would, under the
  scheduling lock with the week's employments `FOR SHARE`) and is refused as stale unless they
  still produce the submitted digest and totals: it never approves time that no longer matches
  its sources. **A return binds the same evidence but does not require unchanged sources**: it is
  how a timesheet whose attendance changed after submission goes back to a DRAFT, so the
  administrators' refresh can apply. Requiring unchanged sources for a return too would leave such
  a timesheet stuck: neither approvable, returnable nor refreshable. (Architecture review: this is
  the one reading of T70BC-6/T70BC-7 made here.)
- A return requires a reason: 10 to 500 characters after trimming, no control or format
  character, markup or link (the V31 explanation grammar), stored as Restricted HR/Operations and
  shown only to the employee, tenant administrators and the routed manager.
- A replay of a decision is answered only while the caller still holds the route; of a
  submission, only while the timesheet is still the caller's.

### Immutability (T70BC-8)

- A generation with any SUBMITTED or APPROVED timesheet is never refreshed (the service refuses
  with `TIMESHEET_GENERATION_NOT_ALLOWED` and V33's generation guard refuses
  `timesheet_generation_locked`). A returned (DRAFT) timesheet refreshes again.
- Approved timesheets, their revisions, rows and actions never change; attendance or employment
  changes after approval never alter them. Reopening is MVP-070D.

### Serialization

Submission and decisions take, in order: the idempotency reservation; the tenant's manager-graph
lock (manager lines and separations cannot change); the scheduling lock (ADR 0014); the caller's
link `FOR SHARE`; the timesheet `FOR UPDATE`; the employee's employments `FOR SHARE`; for an
approval, the week's candidates' employments `FOR SHARE` and the sources. Then the action, the
timesheet's matching state, the deferred checks, the audit record and the outbox event, committed
together. A refresh takes the scheduling lock and the drafts `FOR UPDATE`, so a submission and a
refresh of the same week serialize: either the refresh sees a SUBMITTED timesheet and is refused,
or the submission sees the new generation version and is stale. Concurrent approvals and returns
of one submission give exactly one terminal action.

### Data (T70BC-9, V33)

- `people.timesheet` gains `submission_cycle` (0 before the first submission) and
  `current_action_id`; its status check widens to the three states.
- `people.timesheet_action` (append-only, untruncatable): numbered per timesheet; action, cycle,
  revision, generation version, source digest and totals (a composite key to the revision),
  employee, site, employment, manager (never the employee), actor subject, optional reason and
  instant. A decision carries a composite key to its cycle's submission with identical evidence.
  Partial unique indexes allow one submission and one terminal decision per cycle.
- Triggers: a new action follows the latest one from the timesheet's current state
  (`timesheet_action_transition`); the timesheet changes state only through its matching latest
  action and its revision advances only while it is a DRAFT (`timesheet_guard`); deferred, the
  timesheet's current action is its latest and its state that action's
  (`timesheet_action_current`, `timesheet_current_action`).
- Rollback: `db/rollback/V33__rollback.sql` restores V32 exactly only while no action exists and
  every timesheet is a never-submitted DRAFT; it refuses otherwise with no override and never
  edits `flyway_schema_history`.

### Audit, events and privacy (T70BC-11)

- Audit `timesheet.submit`, `timesheet.approve`, `timesheet.return` with metadata limited to
  `schemaVersion`, `action`, `cycle`, `revision`, `generationVersion`, `rowCount` and
  `workedMinuteBand`; identifiers, exact totals, the source digest, the reason's digest, the
  manager and the subject only inside the state digest. Every read writes a fail-closed disclosure
  audit (`timesheet.approval-list`, `timesheet.approval-read`, and the existing reads).
- Events `people.timesheet.submitted.v1`, `approved.v1`, `returned.v1`: stable IDs, the week's
  boundaries, revision, generation version, cycle, row count, worked minutes, decision and
  instant; never names, labels, the subject or the reason.
- Logs and metrics: the operation and a bounded outcome only.

## Consequences

- Approved weekly time is immutable evidence that MVP-070D can reopen and MVP-070E can export.
- Once any timesheet of a week is submitted or approved, the whole week's generation stops
  refreshing; a later attendance correction for another employee of that week is not reflected
  until MVP-070D. A submitted timesheet whose sources changed can only be returned.
- Out of scope (T70BC-15): delegation or substitution of the approver, several approvers, project,
  grant, task or cost-center routing, comments beyond the return reason, attachments, signatures,
  manual time entry, correction inside approval, reopening, period locks, export, pay rules,
  notifications, bulk decisions, AI and native mobile.
