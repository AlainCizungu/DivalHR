# ADR 0022 Period lock: one guarded boundary around a fully approved week

## Status

Proposed with the MVP-071 pull request (Issue #139, architect specification L71-1 to L71-13);
pending architecture review. Builds on ADR 0018 (weekly timesheets), ADR 0019 (submission and
approval), ADR 0020 (reopening approved time) and ADR 0021 (approved-time export).

## Context

Payroll preparation needs a week whose approved time no longer moves: no correction, refresh,
submission, approval or reopening may change it once it was handed over, unless someone with the
authority to do so deliberately reopens it, says why, and leaves a record. DivalHR already has the
generation as the identity of a site's week (ADR 0018), the approval as the current evidence of a
timesheet (ADR 0019), the reopening as the only way approved time changes (ADR 0020) and the
export as an immutable snapshot (ADR 0021). The lock must sit on top of these without rewriting
any of them, and every path that mutates the week must ask the same question.

## Decision

### Identity and lifecycle (L71-1)

The lock belongs to exactly one tenant, legal entity, site and generated Monday week: the
generation is the period. `OPEN → LOCKED → OPEN → LOCKED …` by immutable `LOCKED` and
`UNLOCKED` actions numbered 1, 2, 3 … (the lock version after the transition; odd locks, even
unlocks). A one-row projection per generation (`people.timesheet_period_lock`) holds the state,
the version and the current action; it is created by the first lock and changed only by the next
transition. No monthly, cross-site, cross-entity or arbitrary-date period exists.

### Eligibility and evidence (L71-2, L71-4)

A week locks only when its generation holds at least one timesheet and every timesheet is
`APPROVED` by its current action at its current revision and submission cycle (an approval binds
the generation version of its day; a refresh never rewrites an approved timesheet, so that version
is recorded, not required). The readiness names stable blockers (`EMPTY`, `NOT_ALL_APPROVED`,
`APPROVAL_NOT_CURRENT`, `ALREADY_LOCKED`) and counts by state; it never names an employee. A
`LOCKED` action binds the exact scope and boundaries, the generation version, the evidence digest
(every timesheet's current approval, revision and cycle), the counts and the total worked minutes.
The database proves the binding at insert (`timesheet_period_fully_approved`), the alternation and
monotonic version, the scope, the projection's consistency with the latest action at commit, and
append-only evidence; a prior export is not required and a lock creates none.

### Preview, lock, unlock (L71-3, L71-4, L71-6)

The lock preview follows the export's shape (ADR 0021): a digest bound to the tenant, the evidence
digest, the generation and lock versions and a 15-to-30-minute expiry; the command needs that
digest, the two versions, an explicit confirmation and an `Idempotency-Key`, takes the tenant's
scheduling lock (the lock every timesheet command and correction takes first), locks the generation,
the projection and every timesheet, evaluates again and refuses anything stale before any write
(`409 TIMESHEET_PERIOD_PREVIEW_STALE`, `409 TIMESHEET_PERIOD_NOT_READY`). A lock of an already
locked week whose preview named the current lock is `UNCHANGED`; an identical replay returns the
original receipt; a changed payload under the same key is refused by the idempotency contract.

The unlock takes a closed reason (`ATTENDANCE_CORRECTION_REQUIRED`, `TIMESHEET_EVIDENCE_ERROR`,
`ORGANIZATION_REQUEST`, `OTHER`) and an explanation under the attendance-correction grammar (10 to
500 trimmed characters, no markup, link, control or format character; Restricted HR/Operations data
stored only with the `UNLOCKED` action, digested everywhere else, never returned). Its preview digest
binds the current lock action and version, the reason and the explanation digest; the command needs
them, the current lock, a confirmation and an `Idempotency-Key`. Unlocking alone changes no
timesheet and no export: a later material correction reopens only the affected timesheets
(ADR 0020), and the week relocks after its refresh, resubmission and reapproval.

### One guard (L71-5)

`TimesheetPeriodGuard` is the single domain question "is this generated week locked?", asked
under the scheduling lock by every protected mutation: the attendance-correction commit (for a
shift whose site-local date lies in the locked week; the preview stays readable), the generation
refresh, the submission, the return, the approval and the reopening. The answer is the one stable
`409 TIMESHEET_PERIOD_LOCKED`. V36 repeats the boundary in the database for writes that bypass the
application (no timesheet change, no action, no refresh, no correction of a locked week). Reads,
exports of unchanged approved evidence and every operation outside the exact generated week are
untouched.

### Authority, audit and privacy (L71-7, L71-10)

Tenant administrators with exact MFA preview, lock, unlock and read the history; employees and
routed managers read only the lock state inside the views they already have; platform
administrators have no tenant-data shortcut; tenant, actor, scope and the current lock come from
verified server state. Audits `timesheet-period.lock` and `.unlock` carry bounded metadata (action,
versions, week, counts, a minute band, the reason code); identifiers, the evidence digest, exact
minutes, the explanation digest and the actor only inside the audit state digest. Events
`people.timesheet-period.locked.v1` and `.unlocked.v1` carry stable scope and action IDs, versions,
bounded counts, the minute band, the reason code and the instant; never a name, an employee number,
the actor or the explanation. Logs and metrics carry operation, outcome, transition, reason code and
count bands. Audit or outbox failure rolls the transition back.

## Consequences

- A locked week is stable payroll-source evidence: nothing changes it until a recorded, reasoned
  unlock, and the history of every lock and unlock is permanent.
- Earlier exports are never changed by a lock or an unlock; a new export of unchanged evidence
  follows the export's own rules.
- `db/rollback/V36__rollback.sql` restores V35 exactly only while no period action and no lock
  projection exists; there is no override and no edit of `flyway_schema_history`.
- Not included: payroll inputs or calculations, rates, overtime, premiums, statutory rules, leave
  valuation, deductions, allowances, money movement, bank or mobile-money files, accounting entries,
  payroll officer or custom roles, multi-site or monthly locks, scheduled locking, four-eyes unlock
  approval, attachments, notifications, export deletion or voiding, retention, project, grant or
  task allocation, AI and native mobile.
