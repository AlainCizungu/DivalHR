# ADR 0014 Site work schedules: immutable publication, time zone conversion, conflicts and lock order

## Status

Accepted (Issue #99, architect-approved plan D50A-1 to D50A-12, amended by architecture reviews
R100-1 and R100-2 on PR #100: the site, not the organization, is the clock, and the employee's
default view is instant-based). First scheduling increment
(MVP-050A); extends ADR 0007 (employment history) and ADR 0008 (separations and lock order).
Extended by MVP-050B (Issue #101, architect-approved plan D50B-1 to D50B-12): cancellation and full
replacement of an entirely future published schedule, below.

## Context

A tenant administrator needs to tell employees when and where they work, and to see which staffing
slots nobody covers. Schedules touch employment, placement and separation facts that change
concurrently; local times cross midnight and, in some time zones, clock changes; and a published
schedule is something employees rely on, so it must be evidence, not an editable document. This
increment is deliberately narrow: no draft, amendment, cancellation, recurrence, break, pay,
availability, notification or manager view.

## Decision

### Publish through a server preview; nothing mutable is stored (D50A-2)

- The browser holds the proposal in memory only. `POST /work-schedules/preview` evaluates the whole
  proposal against the current facts and returns the site's business date (`asOf`) and the site's
  time zone (`siteTimezone`), the normalized slots with their instants, the assigned employees' current directory
  names, one issue per slot when it cannot be published, derived totals, warnings and a
  domain-separated digest (`DIVALHR-WORK-SCHEDULE-PREVIEW`, version 1). The preview writes only its
  disclosure audit record.
- The digest binds the site's business date and time zone, the site and its period, every normalized slot
  (position, client reference, local times, instants, employee, issue, both labels) and every fact
  the eligibility read: each assigned employee's employments with their versions, active placements
  and scheduled or effective separations.
- `POST /work-schedules` repeats the proposal with the digest and `confirm: true` under an
  `Idempotency-Key`. Under the lock order below the server evaluates it again: a slot issue refuses
  with its stable code, position and reason; any other difference is `409
  WORK_SCHEDULE_PREVIEW_STALE`. A plan is never recomputed and published silently.

### One site, a bounded period, one required worker per slot (D50A-3, D50A-4)

- One site of the tenant in effect on every date; inclusive dates from the site's business date on,
  at most 31 days; 1 to 500 slots. The site is resolved through the tenant module's
  `OrganizationPlacementDirectory` port; unknown, foreign and inactive sites are the same `422
  WORK_SCHEDULE_SITE_NOT_ACTIVE`.
- A slot has local start and end (minute precision), English and French labels and an optional
  employee. It starts on a schedule date, ends after it, at most on the next date and at most 24
  hours later (instants). An unassigned slot is one uncovered position; totals are always derived.
- Labels follow a closed plain-text grammar (version 1, identical in Java, SQL and the browser):
  Latin letters with accents, digits, single spaces and `. , ' ’ - ( ) / & # °`, 2 to 60 characters,
  no dot before a letter; markup, links and e-mail addresses cannot be written.

### The site is the clock; conversion fails closed (D50A-3, D50A-4, R100-1)

- A schedule runs on the selected site's IANA time zone, never the organization's: organizations
  span time zones (Kinshasa and Goma are one hour apart), and a Goma schedule must not inherit a
  Kinshasa default. The server resolves the site's time zone through the tenant module's
  `OrganizationPlacementDirectory` port (its unit view carries a site's `timezone`); the client never
  chooses it. It is stored with the schedule as the immutable `site_timezone` snapshot and returned
  as `siteTimezone` by preview, list, detail and the employee's own view.
- The site's business date (`asOf`, today in the site's zone from the injectable platform clock)
  decides "today or later": near midnight a date can already be past at one site and still open at
  another. A site whose time zone is missing or unknown to the platform fails closed as
  `WORK_SCHEDULE_SITE_NOT_ACTIVE`; there is no fallback clock.
- The site's time zone and business date are bound into the preview digest, so a site time zone
  changed between preview and publication (no API changes it today: a site is created with its zone)
  or a date that turned past is `WORK_SCHEDULE_PREVIEW_STALE` (or the period error), never a silent
  republication. The administrators' list takes explicit date filters and binds them, not a time
  zone, into its cursor; its `asOf` is the organization's business date and is informational.
- **The employee's default view is instant-based (R100-2).** No single calendar date means "now"
  across schedules of sites in different time zones: with an organization in Goma (UTC+2) already
  on October 11 and a site in Kinshasa (UTC+1) still on October 10, a date-based default would hide
  that evening's Kinshasa shift. Without `from`, `GET /me/work-schedule` therefore captures one
  server-owned instant from the injectable platform clock and returns the shifts with
  `end_at > instant`: in progress and to come, never ended. The instant is returned as
  `asOfInstant` and pinned in the signed cursor (inside the HMAC-protected keyset position, with
  the view's mode in the bound scope), so every later page reuses it and the clock moving between
  pages neither adds nor drops a shift. With explicit `from` (and optional `to`), the view keeps the
  local-start-date semantics, each shift's local start date in its own schedule's site zone; `to`
  alone is refused. A cursor of one view is refused in the other.
- The employee's own view returns every shift in the stored snapshot of its own schedule: shifts at
  sites in different zones are shown as published, never converted to one zone.
- A local time converts only when it maps to exactly one instant (`ZoneRules.getValidOffsets` has one
  offset). A time in a spring-forward gap or repeated in a fall-back overlap is
  `WORK_SCHEDULE_SLOT_INVALID` with reason `LOCAL_TIME_INVALID`; it is never guessed. The database
  checks the same rule independently in the schedule's snapshot (`people.work_schedule_local_unique`,
  used by `work_schedule_slot_consistent`), so a slot's stored instants are exactly its local times
  at the site.

### Eligibility and conflicts (D50A-5)

- An assigned employee must be an employee of the tenant (unknown and foreign alike: `NOT_FOUND`),
  have no scheduled or effective separation taking effect before the slot ends (`SEPARATED`), be
  employed on every local date the slot occupies (`NOT_EMPLOYED`; a slot ending at midnight occupies
  only its start date), and be placed at the site on the slot's start date (`NOT_AT_SITE`).
- An employee never has two overlapping assigned slots (half-open instants) in this proposal
  (`IN_SCHEDULE`, on the later slot) or in any published schedule, any site (`PUBLISHED`).
- Slot evidence stores the employee ID and the publication facts, never a copied name. Names and
  numbers shown by preview and read come from the directory at read time.

### Lock order (D50A-6)

Publication: idempotency (step 0); the tenant's scheduling lock
`pg_advisory_xact_lock(hashtextextended('people.work-schedule:' || tenant, 0))` (step 1), so two
publications of a tenant never both see a site date or an employee instant free; every employment of
the assigned employees overlapping the dates `FOR SHARE` in UUID order (step 2), so employment
changes, corrections and separations, which lock an employment `FOR UPDATE`, wait for the
publication or are seen by it; then the evaluation, the inserts, the deferred completeness check, the
audit record and the outbox event. A coarse tenant lock is preferred to a race-prone per-site design
for the pilot.

### Database authority (D50A-6, D50A-7)

- `people.work_schedule` and `people.work_schedule_slot` are published evidence: no update, delete or
  truncate; no status column.
- Keys: the tenant root; the slot's schedule (`(tenant_id, schedule_id)`); the assigned employee
  (`(tenant_id, assigned_employee_id)` to `people.employee`); and the site, `(tenant_id, site_id)` to
  `tenant.site (tenant_id, id)`. The site key is a deliberate integrity exception to module
  ownership, like ADR 0008 and ADR 0009: the plan requires the database to enforce tenant-consistent
  site references. Within the same exception, `work_schedule_site_timezone` checks at insert that the
  snapshot equals the site's own time zone (and is a zone PostgreSQL knows). Application code still
  reads sites only through the platform port; an architecture test refuses SQL naming a `tenant`
  table in the scheduling package.
- Exclusions: `work_schedule_site_no_overlap` (one published schedule per site and date) and
  `work_schedule_slot_employee_no_overlap` (assigned instants of an employee). They sit on the
  evidence itself: in MVP-050A no published row is ever superseded, so no historical row can block a
  later valid publication. **A story that adds cancellation or replacement (MVP-050B) must first move
  these exclusions to guarded operational projections** (the R98-1 lesson of ADR 0013), so a
  cancelled schedule frees its dates and instants. MVP-050B (V26) does exactly that; see below.
- A schedule commits only with exactly its stated slot and assigned counts, 1 to 500 slots, checked
  on both tables by deferred constraint triggers (`work_schedule_complete`); publication runs them
  immediately before its audit record and event.

### Evidence, events and privacy (D50A-10)

- One transaction: the schedule and all slots, the `work-schedule.publish` audit record (metadata:
  schedule and site IDs, dates, slot, assigned and uncovered counts; the whole published state only as
  `after_state_sha256`), the `people.work-schedule.published.v1` event (the same IDs, dates and counts)
  and the idempotency completion. Outbox, audit and completion failures roll everything back.
- Reads are fail-closed disclosures: `work-schedule.preview`, `work-schedule.read` (list and one
  schedule) and, for an employee's own shifts, `work-schedule.self-list` (not privileged-denial
  evidence). Labels, names, numbers, e-mail addresses and the publisher never enter metadata, events,
  logs or errors; the publisher subject is stored and never returned.

## Consequences

- Employees see exactly what was published; administrators see coverage gaps as data.
- An employment change, correction or separation recorded after publication does not revisit
  published slots in this increment; MVP-050B (amend, replace, cancel) owns that follow-up.
- The scheduling package is a bounded part of the people module (architecture test); extracting it
  to the Operations module later needs an employment-facts port in place of its direct reads of
  people's own tables.

## MVP-050B: cancel or replace a future published schedule (Issue #101)

### Evidence, not edits (D50B-1, D50B-2, D50B-3)

- A published schedule and its slots stay immutable. A change adds evidence: one row in the
  append-only `people.work_schedule_cancellation` (reason, instant, subject) or
  `people.work_schedule_replacement` (the successor schedule, reason, instant, subject). The status
  is derived, never stored: `PUBLISHED` (no terminal action), `CANCELLED` or `REPLACED`.
- One terminal action per schedule: both tables key on `(tenant_id, schedule_id)` and a
  `BEFORE INSERT` guard (`work_schedule_terminal_guard`) locks the schedule row and refuses a second
  action of either kind (`work_schedule_single_terminal`). A successor replaces at most one schedule
  (`work_schedule_replacement_successor`), was published at the replacement's instant, after the
  original, with the original's site and period, and has no terminal action of its own
  (`work_schedule_replacement_shape`). Chains A → B → C are allowed; branches and cycles are not.
- A replacement is a whole new schedule of the same site and period, with every slot given again
  (labels, times, employees), built by the same browser builder prefilled from the original. No
  partial amendment, no change of site or dates.

### Operational coverage projections (D50B-7)

- V26 moves both V25 exclusions from the evidence to guarded projections:
  `people.work_schedule_site_coverage` (`work_schedule_site_no_overlap`: one operational schedule per
  site and date) and `people.work_schedule_slot_coverage` (`work_schedule_slot_employee_no_overlap`:
  no overlapping operational assigned instants per employee). Every V25 schedule and assigned slot
  is backfilled first; a backfill that would violate an exclusion fails the migration.
- Each projection row is bound by a composite key to its evidence's exact tenant, ID, site, dates or
  instants and employee. Rows are only inserted (with a schedule that has no terminal action) and
  deleted (once one exists), never updated or truncated (`_guard`, `_no_truncate`). Deferred
  constraint triggers (`work_schedule_coverage_exact`) require an operational schedule to have
  exactly one site row and one slot row per assigned slot, and a cancelled or replaced one to have
  none. Projections are never the source of truth for status.

### The change window (D50B-4)

- A schedule can be cancelled or replaced only while every one of its slots starts strictly after
  the server's instant (`calendar.now()`), checked by the application and again by the database
  against the evidence's recorded instant (`work_schedule_change_window`). No client time is read.
  Otherwise `409 WORK_SCHEDULE_CHANGE_WINDOW_CLOSED`. Every slot of a replacement must also start
  after that instant (slot issue `WORK_SCHEDULE_SLOT_INVALID`, reason `NOT_FUTURE`).
- The window is about slots, not dates (architecture review R102-1). A replacement keeps the
  original's period even when that period has already begun at the site, for example Monday to
  Friday with a first slot on Thursday, replaced on Tuesday. The MVP-050A rule that a period starts
  today or later (`WORK_SCHEDULE_PERIOD_INVALID`, reason `PAST`) applies to new publications only.

### Lock order (D50B-9)

Cancellation and replacement: idempotency (step 0); the tenant's scheduling advisory lock (step 1,
the same as publication); the original schedule row `FOR UPDATE` (step 2); for a replacement, every
employment of the proposed employees overlapping the dates `FOR SHARE` in UUID order (step 3); then
the evaluation, evidence, coverage release and insert, the deferred checks run immediately, the
audit record and the outbox event. A replacement's evaluation leaves the original's own coverage
aside, so moving a shift within the same schedule is not a conflict with itself.

### Reason grammar inside the scheduling package

- The cancellation and replacement reasons follow the decision-reason grammar version 1 of the leave
  decisions (2 to 500 code points after NFC and trimming; checked in the database by
  `people.leave_reason_valid`). The architecture tests forbid the scheduling package from depending
  on the leave package, and the leave package from gaining dependents, so the package carries its
  own copy, `WorkScheduleReasonGrammar`; a unit test proves it accepts and normalizes exactly as
  `LeaveReasonGrammar` over every code point. A future shared-kernel grammar may replace both.

### Evidence, events and privacy (D50B-10)

- One transaction each: evidence, coverage, the `work-schedule.cancel` or `work-schedule.replace`
  audit record (metadata: schedule, successor and site IDs, dates and counts; the reason, subject
  and full state only inside `after_state_sha256`), the `people.work-schedule.cancelled.v1` or
  `people.work-schedule.replaced.v1` event (the same IDs, dates and counts), and the idempotency
  completion. The replacement preview is a fail-closed disclosure
  (`work-schedule.replacement-preview`).
- The reason is returned only in the administrators' list and detail; it never enters logs,
  metrics, events, audit metadata, errors, URLs or browser storage. Employees read only operational
  schedules: a cancelled or replaced schedule disappears from « Mon horaire » / "My schedule".

### Consequences (MVP-050B)

- Cancelling frees the site's dates and the employees' instants immediately for a new schedule.
- The original stays readable with its status, reason and a link to its successor; the successor
  links back.
- Rolling back V26 is refused once any cancellation or replacement exists, or when the projections
  no longer match every schedule exactly (`db/rollback/V26__rollback.sql`).
- Still out of scope: partial amendment, a change after the first slot started, recurrence, notice
  to employees, a manager view, attendance and payroll effects.
