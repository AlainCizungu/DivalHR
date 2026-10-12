# ADR 0017 Supervisor-assisted attendance: dynamic manager authority, evidence and lock order

## Status

Accepted (Issue #115, architect implementation handoff B60B-1 to B60B-12). Extends ADR 0016
(online attendance on scheduled shifts) and relies on ADR 0007 (effective-dated employment
history), ADR 0008 (separation and the global lock order), ADR 0010/0011 (manager routing on the
reporting line), ADR 0013 (leave approval substitutes), ADR 0014 (site work schedules) and ADR 0015
(shift substitutions). Pending architecture review of the PR for MVP-060B.

## Context

Some employees cannot record their own arrival or departure (a broken phone, a closed badge
reader). Their manager must be able to record it for them, with a reason the employee sees, without
weakening what MVP-060A guarantees: immutable server-time evidence, the same windows, one valid
outcome under every race. Authority must follow the reporting line as it is at the moment of the
action, so a manager who loses a report loses the action at once.

## Decision

### Ownership and package

- Part of the bounded scheduling package (`com.divalhr.core.people.schedule`, ADR 0014/0016). New
  types: `TeamAttendanceService` and `TeamAttendanceCommand` (application),
  `TeamAttendanceController`, `TeamAttendanceResponses`, `SupervisorCheckInRequest` and
  `SupervisorCheckOutRequest` (API); `Attendance` and `JdbcAttendanceRepository` gain the method,
  the queue and the supervisor evidence. It reads the module's own employment tables (employments,
  MANAGER lines) through `JdbcAttendanceRepository` and takes the manager-graph lock through the
  existing `ManagerGraphLock` port (MVP-041B). Nothing outside the package depends on it
  (`SchedulePackageBoundaryTest`).

### Authority (B60B-1)

- **Target.** For a check-in, the slot's current operational coverage (ADR 0016); for a
  check-out, the record's employee and employment.
- **Effective manager.** The employee named by the active, non-superseded `MANAGER` line of the
  target's one qualifying employment (for a check-out, the record's employment) on the shift's
  site-local start date. The manager's own employment is the one covering that date (unique:
  employments never overlap, and a MANAGER line names a manager employed during the line).
- Evaluated on every list, command and replay, after the manager-graph lock: a former manager
  loses the queue item, the command and the replay at once. A manager never acts for themselves
  (the target is never the caller's linked employee). Tenant administrators, MVP-041G leave
  approval substitutes and MVP-051 substitution approvals grant nothing.
- Every undisclosable case (not the manager, a former manager, the caller's own shift, another
  tenant, an unknown or malformed slot or record, a target who is no longer the effective
  assignee) is one `404 TEAM_ATTENDANCE_ACTION_NOT_FOUND` with no params. The queue answers
  `403 TEAM_ATTENDANCE_MANAGER_REQUIRED` only to a caller with no MANAGER line naming them from 3
  days before to 1 day after the organization's business date (the window of shifts the queue can
  show); a tenant administrator without an employee link keeps `403`.

### Windows and reason (B60B-2)

- The MVP-060A windows, unchanged and at the same exact boundaries: check-in from 2 hours before
  the scheduled start until the end (exclusive); check-out after the check-in until 24 hours after
  the end (inclusive). One actionable open record per employee. No override.
- The reason follows the decision-reason grammar version 1 (`people.leave_reason_valid`, 2 to 500
  code points after NFC and trimming) with its locale `en` or `fr`; it is stored as submitted.
  Missing or invalid input is `400 VALIDATION_FAILED` naming the fields, never their values.

### Evidence (B60B-3, V29)

- `people.attendance_event.recording_method` (`SELF`, `SUPERVISOR`), immutable with the event.
  Every V28 event becomes `SELF` through a migration-owned default that is dropped at once.
- `people.attendance_supervisor_action`, append-only (update, delete and truncate refused): one
  row per SUPERVISOR event, bound by one composite foreign key to that event's tenant, record,
  schedule, slot, target employee and employment, type, instant, method, subject and idempotency
  digest; the manager employee and the manager's employment (a foreign key to that manager's
  employment), a manager other than the target, the reason locale and the reason.
- A `BEFORE INSERT` guard re-checks the authority in the database: the target employment's
  MANAGER line names the manager on the slot's site-local start date and the manager's employment
  covers that date. Deferred constraint triggers check at commit that a SUPERVISOR event has
  exactly one action and a SELF event none.
- The projection (`people.attendance_record`) is unchanged: it names the target, never the manager
  or the reason. Self-service attendance writes `SELF` explicitly.

### Serialization (B60B-6)

A supervisor command takes, in order:

1. the idempotency reservation;
2. the tenant's manager-graph lock (ADR 0008 step 1);
3. the tenant's scheduling lock (ADR 0014);
4. the target's and the manager's employments `FOR SHARE`, in UUID order;
5. the caller's link and membership `FOR SHARE` (identity port);
6. the schedule `FOR UPDATE`, then the slot and coverage re-read;
7. the target's actionable open records and the record `FOR UPDATE`;
8. the re-evaluation of the authority, the coverage, the state and the window at the server
   instant; then the event, the supervisor action, the projection, the deferred checks, the audit
   record, the outbox event and the idempotency completion, committed together.

**No inverse order.** Each writer that shares a lock with this command takes it in the same
relative order:

- employment changes, cancellations of changes and separations (ADR 0007, 0008): (2) and then their
  employments `FOR UPDATE`; they take no scheduling lock;
- leave decisions, amendments, withdrawals and substitutes (ADR 0010 to 0013): (2) and then leave
  rows; they take no scheduling lock;
- schedule publication, cancellation and replacement (ADR 0014): (3) and then the schedule; they
  take no manager-graph lock;
- substitution requests and decisions (ADR 0015): (2), then (3), then the schedule;
- self-service attendance (ADR 0016): (3), its employments `FOR SHARE`, then the schedule and its
  records; it takes no manager-graph lock.

Every pair therefore meets on the first lock they share, in the same order, and the races
(self-service against supervisor, manager change, separation, cancellation, replacement,
substitution approval) each serialize to one valid outcome (`TeamAttendanceConcurrencyIntegrationTest`).

**Replay.** An exact retry takes the manager-graph lock and replays the stored receipt only while
the caller is still the record's effective manager and not its employee; otherwise the same `404`,
with no stored response or identifier.

### API (B60B-4, B60B-5)

- `GET /me/team-attendance/actions`: the caller's actionable direct-report shifts at one pinned
  `asOfInstant`: check-ins (window open, nothing recorded, no other actionable open record, the
  covering employee's one qualifying employment reporting to the caller) and check-outs (open,
  inside the window, the record's employment reporting to the caller), by deadline, start, slot and
  action; keyset pages of 1 to 100 (default 20) with a signed cursor bound to the operation, the
  tenant, the subject digest, the link, the manager and the page size.
- `POST /me/team-attendance/check-in` (`201`) and
  `POST /me/team-attendance/{attendanceRecordId}/check-out` (`200`) with `Idempotency-Key`, the
  reason and its locale; a minimal receipt for the target (record, slot, employee, status,
  `SUPERVISOR`, server instants), without the reason or the manager.
- `GET /me/attendance` and `GET /me/work-schedule` add each event's recording method and, for a
  supervisor's, the reason and its locale; never the supervisor.
- Two new codes: `TEAM_ATTENDANCE_ACTION_NOT_FOUND` (404) and `TEAM_ATTENDANCE_MANAGER_REQUIRED`
  (403). The MVP-060A window and state codes are reused unchanged; an invalid or missing reason is
  `VALIDATION_FAILED`. Every response is `private, no-store`. Rate limits: the
  `attendance-write` buckets for commands, the subject read bucket for the queue.

### Privacy and observability (B60B-7)

- Audit actions `attendance.team-actions-list` (fail-closed disclosure of the listed records and
  slots), `attendance.supervisor-check-in` and `attendance.supervisor-check-out` with safe
  identifiers (including the manager's employee ID), the event type, the method and the resulting
  status; the reason, the manager's employment and the subject only inside the state digest.
- Outbox events `people.attendance.supervisor-checked-in.v1` and `.supervisor-checked-out.v1`
  carry IDs, the event type, the method and the instant; never reasons, names, numbers, labels or
  subjects. Logs carry operation and outcome only. Metrics reuse the operation-outcome counter and
  `divalhr.attendance.command.duration`.

### Web (B60B-8)

- « Présence de l’équipe » / "Team attendance" under My space: responsive cards with the employee
  and number, the work, the site, the scheduled local times and zone, the required action, the
  deadline and the recorded check-in; « Enregistrer l’arrivée » / "Record check-in" and
  « Enregistrer le départ » / "Record check-out" open a labelled modal dialog with the employee, the
  shift, the site, the reason and its language; one explicit confirmation, the button disabled
  while sending, one idempotency key per reason, and the queue read again afterwards.
- The employee home shows « Présence de l’équipe à enregistrer » / "Team attendance to record"
  only while the caller's queue has an item (no count).
- « Ma présence » and « Mon horaire » mark a supervisor's event « Enregistré par un responsable » /
  "Recorded by a supervisor" with the reason exactly as submitted, in its language; never who.

## Consequences

- Attendance authority follows the reporting line at the moment of the action; a manager change or
  separation takes effect on the next request.
- The manager-graph lock now also serializes supervisor attendance in a tenant, as it does leave
  and substitution decisions; self-service attendance does not take it.
- Out of scope (B60B-12): administrator or delegated recording, unscheduled attendance,
  corrections, approvals, exceptions, breaks, devices, offline sync, notifications, exports, pay.
- Rollback: `db/rollback/V29__rollback.sql` restores V28 exactly only while no supervisor action
  or SUPERVISOR event exists (existing SELF attendance survives unchanged); it refuses otherwise,
  with no override, and never edits `flyway_schema_history`.
