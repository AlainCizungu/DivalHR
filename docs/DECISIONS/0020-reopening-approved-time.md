# ADR 0020 Reopening approved time: a correction supersedes an approval, never rewrites it

## Status

Accepted (MVP-070D, Issue #132, T70D-1 to T70D-12; PR #133, deployed to hr-dev at
`87b26481af2f5cb9ea77672c8dcd507905d92c55`). Only the current approval of a timesheet is ever
exported (ADR 0021, MVP-070E). Narrow successor of ADR 0019 (weekly time submission and approval),
which stays in force for submission, routing, decisions and their evidence. Builds on ADR 0016
(attendance corrections, MVP-062) and ADR 0018 (weekly draft timesheets and their refresh).

## Context

MVP-070B/C made an APPROVED timesheet final. An authorized attendance correction can still change
the attendance an approval was based on. Silently keeping a stale approval, or rewriting it, are
both unacceptable: the first approves time that no longer matches its sources, the second destroys
evidence of what a manager decided. The organization must see the change, refresh the week, and
the employee and manager must declare and decide the new revision through the existing workflow,
while every earlier approval stays visible.

## Decision

### Lifecycle (T70D-1)

- A timesheet's state is `DRAFT`, `SUBMITTED`, `APPROVED` or `REOPENED`. The only new path is
  `APPROVED → REOPENED → DRAFT → SUBMITTED → APPROVED`.
- `APPROVED → REOPENED` is an immutable `REOPENED` action, appended by the committing correction.
  `REOPENED → DRAFT` is the organization's refresh: the timesheet's next revision (no action). The
  next submission opens the next cycle (cycle + 1) and is routed and decided exactly as in ADR 0019.
- Nothing earlier changes: actions, revisions, rows, approvals, return reasons, totals, actors and
  instants are append-only (V33 triggers, unchanged).

### The reopening trigger (T70D-2)

- Only a committed attendance correction reopens, inside its own transaction and under its own
  locks (the tenant's scheduling lock first, as every timesheet command). After the correction's
  writes, every APPROVED timesheet of the corrected employee whose week contains the corrected
  shift is locked `FOR UPDATE` in ID order and its sources are evaluated again exactly as a refresh
  would (the week's candidates' employments `FOR SHARE`). Only a timesheet whose source digest now
  differs from its approval's is reopened; any other is left approved.
- A correction that changes nothing material reopens nothing; a refused, stale or rolled-back
  correction leaves no reopening. DRAFT timesheets keep the MVP-070A refresh; a SUBMITTED timesheet
  keeps the MVP-070B/C rule (its approval is refused as stale and its manager returns it); there is
  no second path for either.
- The correction preview warns when approved time covers the shift (`APPROVED_TIME_MAY_REOPEN`);
  that fact is part of the preview digest, so a commit after an approval or reopening in between is
  stale and must be previewed again. The receipt counts the reopened timesheets.

### Reopening evidence (T70D-3)

- One `REOPENED` action per affected timesheet: the next sequence, the superseded approval's cycle,
  revision, generation version, source digest, totals, employee, employment and manager (bound by
  a composite key to that approval), the source digest after the correction (always different),
  the correction (bound to the same employee), the correcting administrator's verified subject and
  the instant. At most one reopening per cycle and per approval and resulting digest.
- The employee sees « Rouverte » / "Reopened", why another review is needed and the whole history;
  digests, subjects and correction identifiers never reach ordinary responses.

### Refresh (T70D-4)

- A refresh is refused while any timesheet of the week is SUBMITTED.
- An APPROVED timesheet is never given a revision: it stays locked and unchanged (counted as
  `lockedTimesheets`). This holds even when its sources changed by another path than a correction
  (a schedule change after approval): the approval stands as the record of what was approved, and
  reopening without a correction is out of scope.
- A REOPENED timesheet always receives its next revision and becomes a DRAFT (counted as
  `reopenedTimesheets`), even if a later correction restored its sources: the employee must still
  declare it again. The preview digest includes each timesheet's state, so a reopening between
  preview and commit makes the commit stale.

### Serialization (T70D-4, T70D-11 item 10)

Corrections, refreshes, submissions and decisions all take the tenant's scheduling lock before any
timesheet row (decisions take the manager-graph lock before it; corrections never wait on it while
holding the scheduling lock). So every race has one valid outcome: a correction against a refresh
reopens once and the refresh either ran first (the approval stayed locked) or is stale; a correction
against an approval leaves either a stale approval or a stale correction preview, never an approval
of changed time and never a reopening; concurrent corrections reopen once.

### Data (T70D-8, V34)

V34 is additive to V33: the `REOPENED` state and action, the reopening columns
(`superseded_action_id`, `after_source_digest`, `correction_id`), `timesheet_action_superseded`,
`timesheet_action_correction` (with `attendance_correction_employee_key`), the shape check, the
partial unique indexes, and the four V33 functions extended (transition, deferred current state,
timesheet guard, generation guard). `db/rollback/V34__rollback.sql` restores V33 exactly only while
no reopening exists, no timesheet is REOPENED and no submitted or approved timesheet belongs to a
generation refreshed after its latest action; it never edits `flyway_schema_history` and has no
override.

### Audit, events and privacy (T70D-10)

- Audit `timesheet.reopen` (actor: the correcting administrator) with the ADR 0019 metadata set
  (`schemaVersion`, `action`, `cycle`, `revision`, `generationVersion`, `rowCount`,
  `workedMinuteBand`); identifiers, both digests, the correction and the subject only in the state
  digest. The refresh audit adds `reopenedCount`.
- Event `people.timesheet.reopened.v1`: stable IDs (timesheet, action, superseded approval,
  correction, generation, employee, site), the week's boundaries, revision, generation version,
  cycle, totals, state and instant. The refresh event adds `reopenedCount`. Existing events are
  unchanged.
- An audit or outbox failure rolls the whole correction and reopening back.

## Consequences

- Approved time is never silently rewritten; a correction's effect on it is visible to the
  employee, the administrators and, once resubmitted, the manager, with the earlier approval kept.
- Weeks with approved time can be refreshed again; approved timesheets in them stay locked.
- Payroll export (MVP-070E) must export the latest approval of a cycle that is the timesheet's
  current state, never a superseded one. Period locking (MVP-071) will decide whether corrections
  inside a locked period may reopen anything.
- Not included: bulk approval, delegated approvers, notifications, exports, payroll locking,
  manual timesheet editing, reopening without a correction, deleting or voiding history.
