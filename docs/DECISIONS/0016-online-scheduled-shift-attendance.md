# ADR 0016 Online attendance on scheduled shifts: evidence, projection, windows and lock order

## Status

Accepted (Issue #113, architect-approved plan A60A-1 to A60A-12). Extends ADR 0014 (site work
schedules: immutable publication, coverage projections) and ADR 0015 (shift substitutions:
coverage bound to the published assignee or the approved substitute). Pending architecture review
of the PR for MVP-060A.

## Context

An employee assigned to an operational shift must be able to record, online, when they arrive and
leave, and see their own history. The record is evidence for later timesheets and corrections, so
it must be immutable, use the server's clock rather than the browser's, and stay consistent with
schedules and substitutions that change concurrently. Supervisor recording, exceptions,
corrections, offline queueing, geolocation and devices are separate stories (MVP-060B/C, MVP-061,
MVP-062).

## Decision

### Ownership and package

- Attendance on a scheduled shift is part of the bounded scheduling package
  (`com.divalhr.core.people.schedule`, ADR 0014): it reads the slot, the schedule's terminal
  evidence and the slot coverage, and serializes on the same tenant scheduling lock. Nothing
  outside the package depends on it (SchedulePackageBoundaryTest); the new types are
  `Attendance` (domain), `JdbcAttendanceRepository` (internal), `AttendanceService` and
  `AttendanceCommand` (application) and `AttendanceController` (API).

### Effective assignee (A60A-1)

- Only the employee of the caller's current active employee-access link records or reads their own
  attendance. The request carries only the slot (check-in) or the record (check-out, path); tenant,
  employee, employment, schedule, instant and actor are never fields.
- A check-in is accepted for a slot of an operational schedule (no cancellation, no replacement)
  whose current `people.work_schedule_slot_coverage` row is the caller's employee: the published
  assignee, or the substitute of an approved MVP-051 decision. After an approval, the published
  employee's coverage row is gone, so they cannot check in. Unknown, malformed, foreign, cancelled,
  replaced, uncovered and other employees' slots are one indistinguishable
  `404 ATTENDANCE_SHIFT_NOT_FOUND`.
- The employee must have exactly one qualifying employment: covering every local date the shift
  occupies and placed at the schedule's site on its first date (the MVP-051 rule without the
  manager condition). Zero or several is `409 ATTENDANCE_STATE_CONFLICT`; one is never chosen
  arbitrarily.
- Tenant administrators get no recording power from their role (the operations require the
  `employee` role and the caller's own link).

### Windows (A60A-2)

- Named constants (`Attendance.CHECK_IN_OPENS_BEFORE_START` = 2 hours,
  `Attendance.CHECK_OUT_CLOSES_AFTER_END` = 24 hours), mirrored by V28's checks; not
  tenant-configurable. Check-in from the scheduled start minus 2 hours (inclusive) until the
  scheduled end (exclusive); check-out strictly after the check-in, until the scheduled end plus 24
  hours (inclusive). Every instant is the platform clock's (`BusinessCalendar.now()`, microseconds),
  never the browser's.
- A check-out does not depend on the shift still being operational or covered by the caller: a
  cancellation, replacement or substitution after a check-in never rewrites the evidence, and the
  employee can still complete their own open record inside its window.

### One actionable open record (architect decision on Issue #113)

- "At most one open record per employee" taken literally would block every future check-in after
  a missed check-out until MVP-062 provides corrections. The architect chose the actionable rule:
  an employee's open records may not have overlapping ranges `[check-in, scheduled end + 24 hours]`
  (`attendance_record_one_open`, a GiST exclusion over the stored `check_out_until`). The range is
  closed at both ends, like the check-out window: a record can still be checked out at exactly
  `check_out_until`, so another open record at that instant is refused (review R114-1). Within a
  check-out window there is exactly one open record; once it has closed, the record stays `OPEN` as
  evidence (no check-out offered; « Le départ ne peut plus être pointé » / "Check-out is no longer
  possible") and a later shift can be checked in. The missed check-out is a future MVP-062
  correction case.
- The API mirrors it: another open record still inside its window is
  `409 ATTENDANCE_OPEN_RECORD_EXISTS`; the same slot is `409 ATTENDANCE_ALREADY_CHECKED_IN`.

### Evidence and projection (A60A-3, V28)

- `people.attendance_event` is append-only evidence: record, slot, schedule, employee, qualifying
  employment, `CHECKED_IN` or `CHECKED_OUT`, the server instant, the unchanged verified subject and
  a SHA-256 digest of the idempotency reference (operation, subject and key; never the key). It is
  never updated, deleted or truncated. One check-in and at most one check-out per record
  (`attendance_event_record_type_unique`).
- `people.attendance_record` is a guarded projection with one row per slot and employee, bound to
  the immutable slot and its instants, to the employee's employment and to its own `CHECKED_IN`
  event (and, once completed, its `CHECKED_OUT` event) by foreign keys over the event's ID, record,
  type and instant. Its `status` is a generated column (`OPEN` or `COMPLETED`). It is inserted open;
  the only permitted update attaches the single check-out after its event and changes nothing else
  (`attendance_record_guard`); it is never deleted or truncated.
- The check-in guard (`attendance_event_guard`) locks the schedule row and accepts a `CHECKED_IN`
  event only for a slot of an operational schedule whose coverage row is the employee's, inside the
  window, with a qualifying employment; a `CHECKED_OUT` event only for the open record's own slot,
  schedule, employee and employment, strictly after its check-in and inside the window. The
  database is the final authority even if the application is bypassed.
- Deferred (`attendance_exact`, per record, at commit; run immediately by the service before the
  audit record and the outbox event): every event has its record and agrees with it; a
  `CHECKED_OUT` event exists exactly when the record is completed.
- Write order: the event first (the record references it), then the record (check-in) or the
  record's single update (check-out). One transaction; the order is invisible outside it.

### Serialization and replay (A60A-6)

1. idempotency reservation;
2. the tenant's scheduling advisory lock;
3. the covering employee's employments overlapping the shift (check-in) or the record's employment
   (check-out) `FOR SHARE`;
4. the caller's link and membership `FOR SHARE` (identity port);
5. the schedule `FOR UPDATE`, then the slot and coverage rows re-read;
6. the employee's actionable open records and the record `FOR UPDATE`;
7. re-evaluation of the link, operational coverage and window at the server instant;
8. evidence, projection, deferred checks, audit record, outbox event and idempotency completion,
   committed together.

Cancellation, replacement and substitution decisions take the same scheduling lock (after the
manager-graph lock, which attendance never takes) and the schedule row: every race serializes to
one valid outcome. Racing check-ins on a slot yield one record (the others
`409 ATTENDANCE_ALREADY_CHECKED_IN`); racing check-outs one check-out
(`409 ATTENDANCE_ALREADY_CHECKED_OUT`). An exact retry replays the stored receipt only while the
caller is still linked to the record's employee; otherwise `403 EMPLOYEE_LINK_REQUIRED` or
`409 IDEMPOTENCY_KEY_REUSED`, with no stored response or identifier.

### API (A60A-4, A60A-5)

- `POST /me/attendance/check-in` (`201`), `POST /me/attendance/{attendanceRecordId}/check-out`
  (`200`, no body), both with `Idempotency-Key` and a minimal receipt (record, slot, status, server
  instants); `GET /me/attendance` (own history, newest shift first, one pinned `asOfInstant`,
  keyset pages of 1 to 100, default 20, a signed cursor bound to the operation, tenant, subject
  digest, link, employee and page size). `GET /me/work-schedule` adds each shift's `attendance`
  (`NOT_RECORDED`, `OPEN` with the check-in, `COMPLETED` with both) and whether a check-in or a
  check-out is possible at the view's instant. Every response is `private, no-store`.
- Nine stable codes: `ATTENDANCE_SHIFT_NOT_FOUND`, `_CHECK_IN_TOO_EARLY`,
  `_CHECK_IN_WINDOW_CLOSED`, `_ALREADY_CHECKED_IN`, `_OPEN_RECORD_EXISTS`, `_RECORD_NOT_FOUND`,
  `_ALREADY_CHECKED_OUT`, `_CHECK_OUT_WINDOW_CLOSED`, `_STATE_CONFLICT`, never with params.
- Rate limits: `attendance-write` (10 per subject per minute; 600 per tenant per 10 minutes, so a
  shift change can bring many check-ins); the history shares `work-schedule-self-read`.

### Privacy and observability (A60A-7)

- Restricted HR/Operations. Audit actions `attendance.self-list` (fail-closed disclosure),
  `attendance.check-in` and `attendance.check-out` with safe identifiers and the resulting status
  only; the evidence (subject, instants) only as a digest. Outbox events
  `people.attendance.checked-in.v1` and `.checked-out.v1` carry tenant, record, schedule, slot,
  employee and employment IDs, the event type and the instant; never names, numbers, labels,
  tokens, addresses, user agents or subjects.
- Metrics: the shared operation-outcome counter and `divalhr.attendance.command.duration`, tagged
  by operation and a bounded outcome (`success`, `replayed`, `refused`, `failure`). No location,
  biometric, device fingerprint or note is collected.

### Web (A60A-8)

- « Mon horaire » / "My schedule": each shift shows its attendance and only the action the server
  allows now (« Pointer l’arrivée » / "Check in", « Pointer le départ » / "Check out"), each after an
  explicit confirmation in a labelled modal dialog; the confirm button is disabled while sending,
  the idempotency key is stable for a retry, and the list is reloaded from the server afterwards.
- « Ma présence » / "My attendance" under My space and a home card: the caller's own records with
  the shift, site, scheduled local times and zone, check-in, check-out and « En cours » / "Open" or
  « Terminée » / "Completed". An open record still inside its window offers the check-out there too
  (architect decision: a shift leaves « Mon horaire » at its scheduled end, but can be checked out
  until 24 hours later).

## Consequences

- Attendance never changes schedules or coverage; schedules and substitutions never change
  attendance. A slot whose coverage moved after a check-in can carry a record for each employee
  who covered it when they checked in.
- A missed check-out stays visible as an open record until a correction story exists.
- Rollback: `db/rollback/V28__rollback.sql` restores V27 exactly only while no attendance event or
  record exists; it refuses otherwise, with no override, and never edits
  `flyway_schema_history`.

## Amendment: factual attendance exceptions (MVP-060C, Issue #119)

Exceptions are derived, not stored: a query over schedules, terminal evidence, approved
substitutions and attendance records at one server-owned instant (`asOfInstant`), with no V30, no
exception table and no event. This keeps the evidence model of this ADR and ADR 0017 the only
truth and lets a later correction story change records without reconciling a second store.

- **Types and boundaries.** MISSING_CHECK_IN from the scheduled end, for the effective assignee
  at the end (the substitute whose approval was decided before the end, otherwise the published
  employee) when no record of theirs exists at the instant; LATE_CHECK_IN when the check-in is
  strictly more than 5 minutes after the start; EARLY_CHECK_OUT when a check-out recorded by the
  instant is strictly more than 5 minutes before the end; MISSING_CHECK_OUT when the record is still
  open at the instant and the inclusive check-out deadline has passed. Exactly 5 minutes is not an
  exception. The difference is shown in whole minutes, rounded up. A shift can carry several types.
- **Terminal schedules.** A schedule cancelled or replaced at or before the instant creates no
  missing check-in; records already made on it stay reviewable with their own types.
- **Order and paging.** Start descending, slot, employee, then the type order above; signed
  keyset cursors carry the instant, so every page of a review describes the same moment.
- **Query shape.** The employee and manager paths start from the employee's records and slots (the
  approved substitute's slots are a second, small union branch); the tenant path starts from the
  schedules in the date window and reaches slots and records through their existing unique
  indexes. Planner fences (`OFFSET 0`, a `LIMIT 1` scalar check) keep these shapes; plan tests on
  a seeded multi-tenant volume guard them. No index was needed.
