# ADR 0023 Payroll inputs: one locked period, evaluated whole, through one port

## Status

Proposed with the MVP-090A pull request (Issue #141); pending architecture review. The first
increment of one combined payroll release (MVP-090A, MVP-091, MVP-092). Builds on ADR 0018 (weekly
timesheets), ADR 0019 (submission and approval), ADR 0021 (approved-time export) and ADR 0022
(period lock).

## Context

Payroll preparation needs, for one legal entity and one period, exactly the approved worked time
that was locked (ADR 0022), traceable row by row to the employee and employment, the dates, the
lock, the approval and the shift, and never anything DivalHR did not lock. It must never produce a
partial figure, must survive later upstream corrections unchanged, and must say in both languages
that it is inputs, not calculated pay. Payroll is a new bounded module: it must not read people's
tables, and people must not depend on payroll.

## Decision

### Identity and period

A run belongs to one tenant, one legal entity and one inclusive period of 1 to 31 calendar days in
the organization's business time zone, starting no earlier than 2026-01-01 and ending no later than
today. A period's required weeks are, at every site of the legal entity, the generated weeks and
the weeks with published operational shifts that the period touches; a generated week with no
timesheet is skipped. A run is version 1 and status `GENERATED`; one run per legal entity, period
and version. A superseding version is a later increment: a second generation of the same period
returns the existing run when the evidence is identical and is refused otherwise.

### One port

The payroll module (`com.divalhr.core.payroll`, schema `payroll`) reads people's locked approved
time only through the platform port `LockedApprovedTime` (`lockForPayrollInputs`, `weeks`,
`approvedRows`), implemented by the people module's schedule adapter, which returns weeks with
their generation, lock state, lock action and current evidence digest, and the approved rows of a
generation with the employment's legal-entity placement on the work date. The architecture tests
forbid a payroll dependency on people, any payroll SQL over a people table, and a port lock that
does not join the caller's transaction. Payroll keys only to `tenant.legal_entity` and
`tenant.site` (the documented integrity exception).

### Evaluated whole, under the scheduling lock

Preview and generation evaluate the period as a whole under the tenant's scheduling lock, the
lock every timesheet command, attendance correction, period lock and unlock takes first (ADR 0022),
so the evidence evaluated is the evidence written. Blockers are stable codes and any one blocks
the whole period: `WEEK_NOT_GENERATED` (published shifts without a generation), `WEEK_NOT_LOCKED`,
`EVIDENCE_CHANGED` (a locked week whose current evidence digest or generation version differs from
its lock), `ATTRIBUTION_AMBIGUOUS` (a row whose employment is placed in another legal entity on its
work date) and `NO_APPROVED_TIME`. Exclusions are stable codes, counted and never blocking:
`ROW_OUTSIDE_PERIOD` (rows of a week the period cuts) and `TIMESHEET_WITHOUT_TIME`. The preview
returns every week with its status and versions, the eligible counts, the exclusions, the
blockers, a source-versions digest (every week's generation and lock versions), a canonical preview
digest (tenant, evidence digest, source-versions digest, readiness, expiry) valid 15 to 30 minutes,
and the existing run of the period, unchanged or not; never a name, an employee number or a pay
amount.

### Generation

A generation needs the unexpired preview digest, the current source-versions digest, an explicit
confirmation and an `Idempotency-Key`. In one transaction it takes the scheduling lock, evaluates
again, refuses any difference as `409 PAYROLL_INPUT_PREVIEW_STALE` and any blocker as
`409 PAYROLL_INPUT_NOT_READY`, returns the existing run of identical evidence (`EXISTING`) or
refuses a run of different evidence (`409 PAYROLL_INPUT_RUN_EXISTS`), and otherwise writes the
run, its source weeks, its employee rows and its source rows (V37), the audit record and the outbox
event; a failure of any of them writes nothing. The database proves at commit that the run's
counts and total are exactly its rows', that every employee row and source week has rows that
reconcile, that every row lies within the period at its week's site and lock, and that rows are
numbered 1 to n; every V37 table is append-only. An identical command replays the original.

### Authority, audit and privacy

Tenant administrators with exact MFA preview, generate, list and read; every other caller is
refused; unknown or foreign targets are `404 PAYROLL_INPUT_TARGET_NOT_FOUND`. Audit
`payroll-input.generate` carries bounded metadata (versions, period, counts, a minute band);
identifiers, the digest, exact minutes and the actor only inside the audit state digest. Previews,
the history and reads are disclosure-audited. Event `payroll.payroll-input.generated.v1` carries
the run, legal entity, period, versions, bounded counts, the minute band and the source weeks'
generation and lock identifiers and versions; never a name, an employee number, the actor, a pay
amount or a digest. Logs and metrics carry operation, outcome and count bands; never a cursor, a
key or a digest. Dedicated rate buckets (`payroll-input-read`, `payroll-input-write`) keep the
payroll journeys off the timesheet budgets.

## Consequences

- A run is a permanent, exact record of which locked evidence was handed to payroll preparation;
  later unlocks, corrections and relocks never change it, and a new run of the same period with
  different evidence waits for the superseding-version increment.
- The payroll module can be extracted with its schema: its only coupling to people is the port.
- `db/rollback/V37__rollback.sql` restores V36 exactly only while no run exists; there is no
  override and no edit of `flyway_schema_history`.
- Not included: compensation, rates, gross or net pay, taxes, deductions, allowances, leave
  valuation, variance review (MVP-091), exports or payroll-provider files (MVP-092), superseding
  runs, scheduled generation, payroll officer roles, notifications, AI and native mobile.
