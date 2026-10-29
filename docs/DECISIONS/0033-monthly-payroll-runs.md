# ADR 0033: Monthly payroll runs and contractual gross (MVP-094A, Issue #177)

Status: accepted by the architect on Issue #177 (decisions D94A-1 to D94A-13). This record fixes the
details that the issue leaves to the implementation. It is binding for the backend, the web and the
tests of this story. First story of the three-story release train (094A runs and contractual gross,
094B approved country rule pack, 094C review, approval and payslips); one combined verification and
deployment follow the train.

Base: `main` at `3223454379a9b6342fe3c6b2728d8f17680466e7` (MVP-093A merged and deployed). Next free
migration: **V44** (V43 is the last).

## 1. Meaning (D94A-1)

- A **run** belongs to one tenant, one legal entity, one closed calendar month and one currency
  (CDF or USD). Currencies are never converted or combined.
- **Contractual gross** is the employee's monthly base compensation (MVP-093A) after the entity's
  proration policy. It is not taxable gross, net pay, or money owed. Nothing here reads attendance,
  schedules, timesheets or approved-time exports; worked hours never scale a monthly salary.
- Display labels: "Contractual gross" / "Brut contractuel"; "Draft—not statutory payroll" /
  "Brouillon — pas une paie statutaire". Core returns codes only.

## 2. Authority (D94A-2)

All seven operations are `@TenantAdminOperation` with exact MFA (role and assurance enforced by the
platform before any lookup: employees, managers and platform administrators get the platform's 403).
The legal entity comes from the path but is resolved in the verified tenant through
`OrganizationPlacementDirectory.resolveIds` (kind LEGAL_ENTITY). A malformed, unknown, foreign or
wrong-kind id is one safe `404 PAYROLL_RUN_NOT_FOUND` on every operation (policy operations too).
Tenant comes only from the verified context.

## 3. Effective-dated policy (D94A-3)

`payroll.run_policy` is append-only. Each row: sequence (1, 2, 3 per legal entity), `effectiveFrom`,
pay day (`DAY` with day 1-28, or `LAST_DAY`), proration method (`CALENDAR_DAYS`, `FIXED_30_DAYS`,
`NONE`). The policy in force on a date is the one with the greatest `effectiveFrom` not after it;
`effectiveTo` is derived (the day before the next policy's `effectiveFrom`, null for the last).
Distinct `effectiveFrom` per entity (`PAYROLL_POLICY_CONFLICT` otherwise). `effectiveFrom` must lie
in [first day of the month 24 months before today, today + 366 days] in the organization's business
timezone (`422 PAYROLL_PERIOD_INVALID` otherwise). At most 60 policies per entity
(`409 PAYROLL_RUN_LIMIT_REACHED`). The policy **version** of an entity is its last sequence (0 when
none). A policy identical in pay day and method to the one already in force on its date records
nothing: a preview blocker `NO_CHANGE`, commit `409 PAYROLL_POLICY_CONFLICT`.

The policy applied to a run is the one in force **on the last day of the run's month**; it is
frozen onto the run (id, sequence, pay day, method). Pay day is stored and shown; this story computes
no pay date. Copy advises payroll/legal review: these are employer policies, not compliance claims.

Policy operations: `GET` returns the timeline (200 with `current: null` when none), `POST preview`
(no write; returns before/after timeline, blockers, `previewDigest`, 15-minute expiry) and `POST`
(commit with `expectedVersion`, `previewDigest`, `expiresAt`, `confirmed=true`, Idempotency-Key).

## 4. Population, eligibility and calculation (D94A-4)

Inputs, all read for one run: the month `[first, last]`, the entity, the currency, the policy.

Population (port `PayrollPopulation`, section 9): employees that have an ACTIVE `PLACEMENT`
assignment in the entity overlapping one of their employments in the month. The port returns for
each such employee the employments overlapping the month (id, version, start, end) and **all** active
placements overlapping the month in any entity (id, entity, start, end).

For each employee the calculator (pure, `payroll/domain`) computes, per calendar day of the month:
`employed` (in some employment), `placedHere` (placement in this entity), `placedElsewhere`
(placement in another entity), and `compensation` (active MVP-093A fact covering the day).

- **Eligible days** = days that are employed AND placedHere AND covered by a compensation fact of
  the run currency. They are a set of days, so overlapping intervals never double count; the
  eligible ranges are the maximal runs of that set (hire, separation, rehire and multiple
  employments fall out of the set arithmetic).
- **Blockers** (employee level, stable codes, all reported; any blocker forbids creation):
  - `COMPENSATION_MISSING`: an employed and placedHere day with no compensation fact (a gap or none).
  - `CURRENCY_MISMATCH`: an employed and placedHere day covered only by a fact in another currency.
  - `FOREIGN_PLACEMENT`: the employee has an employed day in the month placed in another legal
    entity (a transfer inside the month needs payroll review; nothing is silently split).
  - `OVERLAPPING_EVIDENCE`: overlapping employments, overlapping placements, or overlapping
    compensation facts for the employee in the month (corrupt or ambiguous source data).
  - `MULTIPLE_MONTHLY_AMOUNTS`: more than one distinct monthly amount applies on the eligible days
    (a compensation change inside the month; retro and intra-month changes are out of scope).
  - `PARTIAL_MONTH_NOT_ALLOWED`: method `NONE` and the eligible days are not every day of the month.
- An employee with zero eligible days and no blocker is not in the run. A population member with
  blockers is listed with them (no gross).
- **Gross**, with `D` eligible days, `M` the monthly amount in minor units, `dim` the days of the
  month, using exact integer arithmetic and rounding once with HALF_UP:
  - every day of the month eligible: gross = M exactly, no division, `fullMonth = true`,
    `denominator = null`;
  - `CALENDAR_DAYS`: gross = round_half_up(M x D / dim), denominator = dim;
  - `FIXED_30_DAYS`: gross = round_half_up(M x min(D, 30) / 30), denominator = 30 (so D >= 30
    returns M; `CAPPED_AT_FULL_MONTH` explains it);
  - `NONE`: partial month is the blocker above; a full month is gross = M.
  - round_half_up(n / d) = floor((2n + d) / (2d)) in BigInteger; never a double.
- CDF exponent 0 (1 to 1,000,000,000), USD exponent 2 (1 to 10,000,000 minor units). Money on the
  wire is the MVP-093A canonical decimal string next to its currency code.
- Explanation codes per line (stable, ordered): `FULL_MONTH`, `PRORATED_CALENDAR_DAYS`,
  `PRORATED_FIXED_30_DAYS`, `CAPPED_AT_FULL_MONTH`, `STARTED_IN_MONTH`, `ENDED_IN_MONTH`,
  `MULTIPLE_PERIODS`.
- Run totals: employees (with a gross), blocked employees, total contractual gross (sum of lines,
  exact). More than 5,000 population members is `409 PAYROLL_RUN_LIMIT_REACHED` **before** anything
  is calculated or written (preview too).

Reference vectors (the unit tests and the migration trigger must agree):

| Method | Month | Days | Monthly | Gross |
| --- | --- | --- | --- | --- |
| any | 30 or 31 days | all | CDF 450000 / USD 1250.00 | 450000 / 1250.00 (no division) |
| CALENDAR_DAYS | February 2024 (29) | 15 | CDF 450000 | 232759 (6750000 / 29 = 232758.62) |
| CALENDAR_DAYS | February 2025 (28) | 14 | USD 1250.00 | 625.00 |
| CALENDAR_DAYS | March 2026 (31) | 10 | USD 1250.00 | 403.23 (125000 x 10 / 31 = 40322.58 => 40323 minor) |
| FIXED_30_DAYS | any | 10 | USD 1250.00 | 416.67 (125000 x 10 / 30 = 41666.67 => 41667) |
| FIXED_30_DAYS | 31-day month | 30 | CDF 450000 | 450000 (capped) |
| FIXED_30_DAYS | 31-day month | 31 | CDF 450000 | 450000 (full month) |
| half-up tie | 30-day month | 1 | USD 0.15 (15 minor) | 0.01 (15 / 30 = 0.5 => 1) |

## 5. Preview and frozen creation (D94A-5, D94A-6)

- A **period** is `YYYY-MM`, a fully closed month (its last day is before today in the business
  timezone) within the prior 24 months (this month and future months, and older than 24 months,
  are `422 PAYROLL_PERIOD_INVALID`).
- **Preview** writes nothing. It returns the entity, period, currency, the policy in force on the
  period's last day (or `409 PAYROLL_POLICY_NOT_FOUND` when none), the current draft's version
  (`expectedVersion`, 0 when none), the existing current draft summary, counts, totals, one line per
  population member (employee id and number, eligible ranges and days, denominator, monthly amount,
  contractual gross, explanation codes, employee blockers), `inputDigest`, `previewDigest` and
  `expiresAt` (15 minutes; `previewDigest` and `expiresAt` null while blockers exist or when an
  identical draft already exists, shown as `identicalToCurrent = true`).
- `inputDigest` = SHA-256 over a versioned canonical text of: tenant, entity, period, currency,
  policy id and sequence, and for every population member the employment, placement and
  compensation source records that fed the calculation (ids, versions, ranges, currency, minor
  amount). `previewDigest` binds the command, `expectedVersion`, `inputDigest` and `expiresAt`.
- **Create** repeats the command with `expectedVersion`, `inputDigest`, `previewDigest`, `expiresAt`,
  `confirmed=true` and an Idempotency-Key. Under the lock order (section 8) the service re-reads
  policy, population, employment, placement and compensation and recomputes. Differences:
  `409 PAYROLL_RUN_VERSION_CONFLICT` when the chain's current version moved; otherwise any change
  of the inputs, an expired preview or a tampered digest is `409 PAYROLL_RUN_PREVIEW_STALE` and
  nothing is written; blockers are `409 PAYROLL_RUN_BLOCKED` (the preview already showed them).
  When the recalculated `inputDigest` equals the current draft's, nothing is recorded and no new
  version is created: `409 PAYROLL_RUN_PREVIEW_STALE` (the draft is already current; the preview
  of such a month says `identicalToCurrent = true` and carries no digest).
- Run, lines, ranges, sources, audit and outbox commit in one transaction. Totals and counts are
  checked by deferred database constraints.
- **Lifecycle:** `DRAFT -> SUPERSEDED` only. One current draft per (entity, month, currency); a
  changed recalculation inserts version N+1 and supersedes version N in the same transaction.
  Maximum 25 versions per chain (`409 PAYROLL_RUN_LIMIT_REACHED`). No edit, no delete. Approval,
  locking, payslips and payment are later stories.

## 6. Data (V44, D94A-7)

Tables in schema `payroll` (see `V44__payroll_monthly_runs.sql`, which is normative for columns and
constraints): `run_policy`, `pay_run`, `pay_run_line`, `pay_run_line_range`, `pay_run_line_source`.
Money is `bigint` minor units. The database enforces tenant ownership (composite keys), month
boundaries, currency and scale, status and version rules, one current draft (partial unique index),
non-negative and bounded day counts, denominator rules, **recomputation of every line's gross** from
its monthly amount, days, the run's method and the month (a trigger), range/day agreement, totals and
employee counts (deferred), and immutability of everything but `DRAFT -> SUPERSEDED`. Sources
reference other modules by id and version only (no foreign keys across the module boundary:
employment and placement ids, compensation fact ids with the creating change sequence).
Rollback `db/rollback/V44__rollback.sql` refuses while any policy or run row exists.

## 7. Contract (D94A-8)

Seven operations under `/api/v1/legal-entities/{legalEntityId}/`: `getPayrollPolicy`,
`previewPayrollPolicy`, `commitPayrollPolicy`, `previewPayrollRun`, `commitPayrollRun`,
`listPayrollRuns`, `getPayrollRun` (`docs/API-SPEC.yaml` is normative). Lists and run lines use keyset
pagination (cursors are opaque, bound to operation, tenant, subject, entity and page size, and carry
no amount, number or digest). Run detail lines page by employee number (`C` collation) then id,
default 50, maximum 200; the run list orders by period descending, currency, version descending,
default 25, maximum 100. Eight stable codes (D94A-10) plus the platform's. All responses are
`private, no-store`.

## 8. Lock order (extends ADR 0008 and ADR 0032)

0 idempotency; 2 employment rows `FOR SHARE` for every employment of the tenant overlapping the month
(port `PayrollPopulation.lockShared`, in identifier order; the result is read after the locks);
**5c** the tenant payroll-population advisory lock `payroll.population:{tenant}` (EXCLUSIVE for run
creation, SHARED for every compensation command, which then takes its employee lock 5d as before);
**5e** the entity run lock `payroll.run:{tenant}:{entity}` (policy commit and run creation, both
EXCLUSIVE); then deferred checks, audit, outbox (6). Policy commit takes only 5e. No path takes
a lower number after a higher one. `CompensationService` and `MyCompensationService` change only by
taking 5c shared before 5d.

## 9. Ports (modular monolith)

`platform/access/PayrollPopulation`, implemented by `people` (`PeoplePayrollPopulation`): `lockShared
(tenant, from, to)` (MANDATORY transaction) and `read(tenant, legalEntityId, from, to)` returning
members (employee id, employee number, employments, placements), exactly the data of section 4,
without names or any other attribute. Payroll never reads people tables; people never reads payroll
tables. Legal entity existence and the organization's business timezone come from the existing
`OrganizationPlacementDirectory` and `OrganizationDirectory`. ArchUnit rules cover the new port.

## 10. Privacy (D94A-9)

Restricted financial and HR data: amounts, employee numbers with day counts, ranges, compensation
values, digests and explanations never appear in URLs, logs, metrics, audit metadata, outbox, idempotency
receipts, cursors or error bodies. Receipts are amount-free (run id, version, period, currency,
status, employee count). Audit actions (fail-closed): `payroll-policy.read`,
`payroll-policy.change`, `payroll-run.read` (previews and reads; counts only), `payroll-run.create`.
Metadata allow-list: `legalEntityId`, `runId`, `version`, `period`, `currency`, `status`,
`policySequence`, `employeeCount`, `blockedCount`, `supersededRunId`, `view`, `page`,
`resultCount`, `schemaVersion`. Outbox (internal, never delivered to webhooks or WhatsApp):
`payroll.payroll-policy.changed.v1` and `payroll.payroll-run.created.v1`, same allow-list. Metrics:
operation, outcome, employee-count bucket, blocker-count bucket.

## 11. Limits (D94A-13)

Preview 5 per subject per minute and 60 per tenant per hour; create 2 per subject per minute and
20 per tenant per hour (platform buckets `payroll-run-preview`, `payroll-run-create`); policy
reads 60/min, policy preview 10/min, policy commit 5/min per subject; run reads 60/min.
Hourly quotas are counted like MVP-093A (commit counts from the evidence table; previews by the
platform buckets only). Explicit request and database timeouts (`DatabaseTimeouts`), no job.

## 12. Out of scope and assumptions to confirm at review

Out of scope as in the issue. Assumptions made where the issue is silent (each is a localized
rule that review can change): (a) a compensation change inside the month is the blocker
`MULTIPLE_MONTHLY_AMOUNTS`; (b) a transfer to another legal entity inside the month is the blocker
`FOREIGN_PLACEMENT`; (c) the policy of a run is the one in force on the month's last day; (d) an
identical recalculation records nothing and is refused as already current; (e) a run needs at least one employee with a gross.
