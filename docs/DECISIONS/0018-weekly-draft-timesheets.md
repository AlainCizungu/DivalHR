# ADR 0018 Weekly draft timesheets: site-local weeks, effective sources, append-only revisions

## Status

Accepted (Issue #128, architect implementation instructions T70A-1 to T70A-15). Extended by ADR
0019 (MVP-070B/C): a timesheet's state is now DRAFT, SUBMITTED or APPROVED, and a generation with
submitted or approved time is never refreshed. Builds on ADR 0007 (effective-dated employment
history), ADR 0014 (site work schedules and the scheduling lock), ADR 0015 (shift substitutions and
effective assignees), ADR 0016 (online attendance) and the MVP-062 attendance corrections (V31).

## Context

Time approval (MVP-070) starts from a weekly timesheet per employee and site. It must reflect what
was scheduled and what was effectively recorded, including administrators' corrections, without
guessing missing attendance, and it must stay reproducible when the sources change after it was
generated. Submission and approval (MVP-070B/C, ADR 0019), reopening (MVP-070D) and export
(MVP-070E) come later; pay rules and project, grant or task allocation are not part of this
sequence. This story only produces evidence-grade drafts.

## Decision

### Ownership and package

- Part of the bounded scheduling package (`com.divalhr.core.people.schedule`, ADR 0014): the
  timesheet reads schedules, coverage, employments and attendance that the package already owns,
  and nothing outside the package reads its tables (`SchedulePackageBoundaryTest`). New types:
  `Timesheet` (domain rules), `TimesheetService` and `TimesheetCommand` (application),
  `TimesheetController`, `TimesheetResponses` and three request bodies (API),
  `JdbcTimesheetRepository` (internal). A later extraction into a time module moves the package
  together with its V32 tables.
- As in ADR 0014, the generation's site is a database-level foreign key to `tenant.site`: the
  documented integrity exception for a site-scoped artefact.

### The week (T70A-2)

- A site-local Monday 00:00 to the next Monday 00:00 in the site's IANA time zone, as two exact
  instants (a daylight-saving change gives 167 or 169 hours). The site's zone comes from the
  organization directory at generation and is stored with the generation; later zone changes do
  not move an existing week.
- Only the site's current week or a past week (the server's clock, at the site): a future Monday,
  a non-Monday or a date before 2000-01-03 is `400 VALIDATION_FAILED` (`weekStart`).

### Inclusion (T70A-3)

A slot is included when, at the moment of the command:

1. its schedule is operational at the site (`work_schedule_site_coverage`, so cancelled and
   replaced schedules are excluded) and overlaps the week's local dates;
2. the slot starts in `[periodStart, periodEnd)` (a night shift belongs to the week it starts in);
3. its effective assignee (`work_schedule_slot_coverage`: the published employee or the approved
   substitute) has exactly one qualifying employment: employed over the slot's local dates with a
   current placement at the site on its first local date. None or several: excluded, never chosen
   arbitrarily.

One draft per employee with at least one included slot, one row per slot. The placement on the
slot's first local date gives the row's cost center (or none). Limits: at most 500 drafts and 5,000
rows per command, refused with `422 TIMESHEET_GENERATION_LIMIT_EXCEEDED` before any write (the
candidate read is bounded at 5,001 rows); the test-only properties
`divalhr.timesheets.max-drafts` and `max-rows` can lower, never raise, them.

### Rows, states and duration (T70A-4, T70A-5)

- A row is a snapshot: the slot, schedule, employee, employment, site, cost center, both labels,
  the scheduled instants (a composite foreign key to the slot's immutable instants), and the
  effective attendance projection (`attendance_record`: the effective check-in and check-out after
  MVP-062 corrections, never the original events) with its correction version.
- States: `READY` (completed attendance), `MISSING_ATTENDANCE` (no record), `OPEN_ATTENDANCE`
  (no effective check-out) and `SOURCE_CHANGED` (refresh only, below). Only `READY` has worked
  minutes: the exact elapsed whole minutes between the effective instants, truncated (never
  rounded; no break, overtime, premium or pay rule). The database checks the shape and the
  truncation (`timesheet_row_shape`).
- Totals per revision: rows, ready, missing, open, changed, blocked (= missing + open + changed)
  and worked minutes; the draft carries exactly its current revision's totals.

### Generation (T70A-6)

- `POST /timesheets/generation-preview` shows the week, the existing generation (if any) and what
  a generation would create; it writes only its disclosure audit.
- `POST /timesheets/generations` with `Idempotency-Key` and `confirm: true`: creates the generation
  (version 1), the drafts at revision 1 and their rows, or returns the week's existing generation
  unchanged (`outcome: EXISTING`); an exact retry replays. A week without any included slot is
  `409 TIMESHEET_GENERATION_NOT_ALLOWED`. Both answer `200` (the idempotency framework's fixed
  status per operation).

### Refresh (T70A-7)

- `POST /timesheets/generations/{id}/refresh-preview` evaluates every draft against the current
  sources: new drafts (newly included employees), drafts whose rows changed, unchanged drafts, the
  changed rows and the totals, with a digest over the tenant, the subject, the generation and its
  version, and each employee's next rows.
- `POST /timesheets/generations/{id}/refreshes` with `Idempotency-Key`, the reviewed `version` and
  `previewDigest` and `confirm: true` recomputes under the locks and refuses anything but the
  identical preview with `409 TIMESHEET_REFRESH_PREVIEW_STALE` (another administrator's preview is
  stale too). It increments the generation version, appends revision n + 1 to each changed draft
  (with the generation version that produced it) and creates new drafts at revision 1; unchanged
  drafts keep their revision. Earlier revisions and rows are never changed.
- **A slot that was in the previous revision and is no longer included** (cancelled, replaced,
  substituted to someone else, or its employment no longer qualifies) is carried into the next
  revision as `SOURCE_CHANGED`: its schedule facts as captured, no attendance, no minutes, and it
  blocks. It is never dropped silently; resolving it belongs to the submission story (070B/C).
- **An empty refresh** (nothing changed) still records a new generation version, with no revision,
  so the administrator's explicit refresh is audited.

### Serialization

Generation and refresh take, in order: the idempotency reservation; the tenant's scheduling lock
(ADR 0014); for a refresh, the generation and its drafts `FOR UPDATE`; the candidates; their
employments `FOR SHARE` (ADR 0015's `shareEmployments`, in UUID order); the candidates again; then
the writes, the deferred checks (`SET CONSTRAINTS … IMMEDIATE`), the audit record and the outbox
event, committed together. Schedule publication, cancellation and replacement, substitution
decisions, self-service and supervisor attendance and corrections all take the scheduling lock
before their rows, and employment changes take employments `FOR UPDATE` without it, so every pair
meets on its first shared lock in the same order (`TimesheetConcurrencyIntegrationTest`). The
unique `(tenant, site, week)` key makes concurrent generations one generation; the version check
and the digest make concurrent refreshes one revision.

### Data (T70A-8, V32)

- `people.timesheet_generation` (unique per tenant, site and week; version; the only update is a
  refresh: version + 1 with its instant and subject), `people.timesheet` (unique per tenant,
  employee, site and week; DRAFT only; the only update moves to the next revision with that
  revision's totals, a deferred composite foreign key), `people.timesheet_revision` and
  `people.timesheet_row` (append-only: update, delete and truncate refused; revisions contiguous;
  deferred checks that a header's totals are exactly its rows' and that a draft shows its latest
  revision).
- Rollback: `db/rollback/V32__rollback.sql` restores V31 exactly only while no generation, draft,
  revision or row exists, refuses otherwise with no override, and never edits
  `flyway_schema_history`.

### API, authority and privacy (T70A-9, T70A-13)

- Administrators (`tenant-admin` with exact MFA, tenant only from the verified token): the two
  previews, the two commands, `GET /timesheets/generations/{id}` and `GET /timesheets/{id}` (rows
  of the current revision and every revision's header). Employees (an active employee link):
  `GET /me/timesheets` (own drafts, newest week first, signed keyset cursor) and
  `GET /me/timesheets/{id}`, without internal identifiers, cost centers or other employees.
- An unknown, malformed or foreign site, generation or draft, and another employee's draft, are
  one `404 TIMESHEET_TARGET_NOT_FOUND` with no params. Every response is `private, no-store`.
  Rate limits reuse the work-schedule subject and tenant buckets and the self-read bucket.
- Audit `timesheet.generate` and `timesheet.refresh` with metadata limited to `schemaVersion`,
  `week`, `draftCount`, `rowCount`, `readyCount`, `blockedCount` and `revisionCount`; identifiers,
  boundaries, digests, worked minutes and the subject only inside the state digest. Every read
  writes a fail-closed disclosure audit with the view and the count.
- Outbox `people.timesheet.generated.v1` and `people.timesheet.refreshed.v1`: the generation, site,
  week and boundaries, version and counts; never names, labels, minutes per employee or subjects.
  Logs carry the operation, the outcome and count bands only.

### Web (T70A-10, T70A-11)

- « Feuilles de temps » / "Timesheets" under Operations: legal entity, site and Monday; the
  generation preview; an explicit confirmation before generating; an existing week opens its
  drafts; the drafts table (employee, revision, shifts, ready, blocked, worked time); one draft's
  rows (shift, work, state, check-in, check-out with « Corrigé » / "Corrected", cost center,
  worked time) and revisions; the refresh preview and its confirmation, a stale preview refused
  and previewed again.
- « Mes feuilles de temps » / "My timesheets" under My space: the employee's own drafts and rows
  with a neutral notice that they are drafts, not submitted, approved or a pay statement.
- Nothing is kept in browser storage or put in a URL; the server computes every state and total.

## Consequences

- Drafts are reproducible evidence: a refresh never rewrites what an earlier revision showed.
- `SOURCE_CHANGED` rows keep a draft blocked until a later story resolves them; an administrator
  sees them in the draft's rows and totals.
- Out of scope (T70A-14): submission, approval, rejection, comments, pay calculations, breaks,
  overtime, rounding policies, exports, project, grant or task allocation, notifications, deletion
  of drafts, unscheduled time and AI.
