# ADR 0026 Operational status: a derived, read-only view of today's shifts and approvals

## Status

Accepted in principle on Issue #154 with amendments A100-1 to A100-8; implemented by the MVP-100 pull
request, pending architecture review of the implementation. Extends ADR 0016 (amendment MVP-060C,
factual attendance exceptions), builds on ADR 0014 (site schedules), 0015 (substitutions), 0017
(manager authority), 0019 (timesheet submission) and the leave approval stories (ADR 0010 family).

## Context

Authorized leaders need one screen answering "what is happening today within my scope": which
scheduled shifts are covered, who has checked in, who is late or has not checked in, and which
approvals are waiting too long. Every underlying fact already exists as immutable evidence. The view
must not create a second source of truth, must never conclude absence, leave, pay or discipline, and
must not disclose more than the screens it links to.

## Decision

- **Derived, read-only, nothing stored, inside `people.schedule`.** One request computes every figure at one server-owned
  `asOf` instant from schedules, terminal evidence, approved substitutions, attendance records,
  pending leave requests and submitted timesheets. No migration, no table, no stored copy, no cursor
  (fixed aggregates and capped details only).
- **Two audience endpoints, one response shape.**
  `GET /api/v1/operations/status` (tenant administrator, exact MFA; optional `siteId`,
  `legalEntityId`) and `GET /api/v1/me/team-operations/status` (the caller as effective manager: the
  employees whose qualifying employment names the caller, evaluated on the shift's site-local start
  date; never the caller). The manager response carries no `coverage` (`null`: an uncovered shift has no employee,
hence no manager). Tenant and actor are never request fields. The code lives in `people.schedule`
because the ArchUnit boundaries forbid a new package depending on schedules and leave.
- **Counting unit (A100-1).** Workforce values count effective assigned **shift occurrences** (one
  slot with an effective assignee), not people. UI label: "Scheduled shifts" / "Quarts planifiés".
  `scheduled` is the total and `expected + notCheckedIn + present + completed = scheduled` is a
  mutually exclusive partition. `late` is a subset indicator (checked in more than 5 minutes after
  start) that may overlap `present` and `completed`. Uncovered slots (no effective assignee) are
  counted apart in `coverage.uncovered`; `coverage.slots = scheduled + uncovered`.
- **Shifts of today.** Each site evaluates "today" in its stored time zone from the single `asOf`:
  slots whose site-local start date is that date, plus slots still in progress at `asOf` that began
  the previous local date (overnight). Totals are the sum of the per-site results.
- **Effective assignee and operational slots (A100-2).** The approved substitute whose approval was
  decided before the slot end and at or before `asOf`, otherwise the published assignee (the rule of
  MVP-060C). Slots of a schedule cancelled or replaced at or before `asOf` are excluded.
- **Shared attendance rules (A100-5).** The tolerance (5 minutes, `AttendanceExceptions.TOLERANCE`),
  the effective-assignee and terminal-schedule SQL, the candidate-slot windows, the manager
  reporting predicate and the timesheet routing predicate are defined once and used by both MVP-060C
  and this view. New constant: `NOT_CHECKED_IN_GRACE` = 30 minutes.
- **Per-occurrence classification** (a record = the effective assignee's attendance record for the
  slot checked in at or before `asOf`):
  - record with a check-out recorded at or before `asOf`: **completed**;
  - record without such a check-out: **present**;
  - no record and elapsed time since the scheduled start is **not greater than 30 minutes**
    (including before the start and exactly 30 minutes): **expected**;
  - no record and elapsed time **greater than 30 minutes**: **not checked in**;
  - **late** (subset): record checked in strictly more than 5 minutes after the start; exactly 5
    minutes is not late.
- **Approvals (C).** Elapsed age is measured in exact time, not calendar days. Pending leave
  requests (`state = 'PENDING'`) are **overdue only when elapsed age is greater than 48 hours** since
  submission. Submitted timesheets (`status = 'SUBMITTED'`) are **overdue only when elapsed age is
  greater than 72 hours** since the submission action. The exact threshold instant is not overdue.
  The screen shows both thresholds and the words "within threshold" / "overdue".
- **Scopes.** Administrator: the whole organization, optionally one site and/or one legal entity;
  leave and timesheet counts include every pending request or submitted timesheet of the
  organization in the filter (the administrator is not shown as the routed approver and no new
  administrator approval queue exists; the destination is the existing read-only area). Manager:
  shifts and timesheets of the employees the manager manages (the MVP-060C team rule and the
  MVP-070B routed-manager rule), leave requests routed to `MANAGER` that report to the caller on
  their first day; never the caller's own. A manager who manages nobody receives zeros.
- **Filters (A100-4, E).** `siteId` and `legalEntityId` are accepted only as identifiers of the
  caller's tenant's site and legal entity. A malformed, unknown, foreign or wrong-kind identifier
  gives one `404 OPERATIONAL_STATUS_SCOPE_NOT_FOUND` without params. The legal-entity filter uses the
  effective-dated placement of the effective assignee (or the employee/employment of the leave
  request or timesheet) on the shift's, request's or week's reference date. It is never silently
  dropped; if its plan cannot stay bounded, the story stops with evidence.
- **Bounded details (A100-3).** Capped lists of at most 20 rows each, deterministically ordered,
  with an explicit `truncated` flag; aggregates are returned independently. Details carry names only
  where the caller already has equivalent disclosure authority: shifts not checked in (administrator
  and manager, as the attendance exception review) and, for the manager only, overdue leave and
  timesheets of their own reports. The administrator receives overdue approval counts and no person
  details. Uncovered shifts carry shift facts only.
- **Wording (A).** English "Not checked in", French "Aucun pointage d'arrivée". The words "absent"
  and "absence" are never a conclusion; no leave, pay or disciplinary inference.
- **Audit and privacy.** Each response is a fail-closed disclosure (audit action
  `operations.status-admin-view` / `operations.status-team-view`): metadata holds counts, whether
  each filter exists, and a digest of scope, instant and returned identifiers; never a name, number,
  label, date or site. Logs and metrics carry the operation, a bounded outcome and counts only.
  `Cache-Control: private, no-store`. Shares the work-schedule-read limit.
- **Drill-down.** The response names route identifiers, never URLs with personal data.

## Consequences

- No history or trend: a future story would need stored snapshots.
- Figures agree with the source screens because they share their rules; a consistency integration
  test compares them with the leave and timesheet queues and the attendance exceptions.
- The administrator's leave count includes requests routed to managers that the administrator's own
  leave screen does not list; the screen states the count is organization-wide.
- Query cost is bounded by the tenant/day window and verified by a plan test on the seeded
  multi-tenant volume.
- MVP-101 (grounded summary) may use this endpoint's metrics as its governed source.

## Rollback

No migration exists. Reverting the pull request removes two read-only endpoints, one page and their
translations; no data is changed.
