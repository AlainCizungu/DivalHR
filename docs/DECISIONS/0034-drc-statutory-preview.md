# ADR 0034: Source-versioned DRC statutory payroll preview (MVP-094B, Issue #179)

Status: accepted for implementation (decisions D94B-1 to D94B-14 of Issue #179 are binding; this ADR
fixes the design points the issue leaves to the implementation). Builds on ADR 0033 (monthly payroll
runs). Next migration: **V45**.

## 1. Meaning and boundary

A **statutory preview** applies an immutable, effective-dated, source-versioned **rule pack** to the
frozen lines of one MVP-094A payroll run and shows, per employee, contractual gross, employee
deductions, employer contributions, preview net and employer cost, with every step. It is not a
payslip, a payment, an approval, a filing or a claim of legal compliance. UI wording: "Statutory
preview—not a payslip or payment" / « Aperçu statutaire — ni bulletin de paie ni paiement ». Never
"compliant payroll".

Money is restricted financial data in CDF whole francs (minor unit = 1 CDF, scale 0, as in ADR 0033).
All arithmetic is exact integer arithmetic (`BigInteger`); no floating point, no script, no
expression language, no reflection, no SQL formula, no JavaScript.

## 2. Rule pack (platform-owned, immutable)

Table `payroll.statutory_rule_pack` (no tenant column; the application role can only `SELECT`; packs
enter by migration or by an operational procedure that supplies the professional approval evidence;
there is no mutation endpoint).

| Column | Meaning |
| --- | --- |
| `id`, `code`, `version` | pack identity; `(code, version)` unique; a source or rule correction is a new version |
| `country` = `CD`, `currency` = `CDF` | closed |
| `status` | `DRAFT` -> `VERIFIED` -> `RETIRED` only |
| `effective_from`, `effective_to` | inclusive interval, `effective_to` null = open |
| `method_version` | calculation-method version (`1`) |
| `rules` (jsonb), `rules_digest` | structured rules; `rules_digest` is a **generated** column `sha256(rules::text)` hex |
| `reviewer_name`, `reviewer_organization`, `reviewed_on`, `attested`, `verified_at`, `retired_at` | verification evidence (restricted operational evidence) |

Table `payroll.statutory_rule_pack_source` (`pack_id`, `seq`, `source_key` from the closed list `DGI_IRPP`,
`DGI_NOTICE_2026`, `DGI_PORTAL`, `CNSS_RATES`, `CNSS_DECLARATION`, `BUDGET_2026`; `title`; `url`
`https://` only on an allow-listed official host (`dgi.gouv.cd`, `desec.dgi.gouv.cd`, `cnss.cd`,
`budget.gouv.cd`); `sha256` (64 hex) nullable while DRAFT; `retrieved_on`). Source rows are immutable.

Database constraints (migration tests prove each):
- a pack is `VERIFIED` or `RETIRED` only with reviewer name and organization (1 to 200 characters),
  `reviewed_on`, `attested = true`, `verified_at`, `method_version`, `effective_from`, and at least one
  source, **every** source having a `sha256` and `retrieved_on` (deferred constraint trigger);
- status moves forward only (`DRAFT` -> `VERIFIED` -> `RETIRED`); `rules`, interval, identity and
  verification evidence never change once `VERIFIED`; DRAFT -> VERIFIED is the only update that may set
  the evidence; delete and truncate are refused;
- `rules` must have `schemaVersion = 1` and a non-empty `components` array (the application validates the
  full schema, section 3, when it parses a pack; a pack that does not parse is never applicable).

**Seed.** V45 inserts exactly one pack, `CD-SALARY-PREVIEW` version 1, **`DRAFT`**, effective
2026-01-01 to 2026-12-31, with the six sources of the issue (no `sha256`: nothing was hashed by a
reviewer) and the published reference values of section 3. The repository never seeds a `VERIFIED`
pack and never fabricates reviewer evidence. Tests create **synthetic** `VERIFIED` packs
(`code = SYNTHETIC-TEST-*`, reviewer "Synthetic Test Reviewer", organization "Test Only") through an
owner connection; these never appear in migrations, seed data or the dev data scripts.

## 3. Rules schema (`schemaVersion = 1`)

```json
{
  "schemaVersion": 1,
  "components": [
    {"id": "CNSS_EMPLOYEE_PENSION", "party": "EMPLOYEE", "category": "SOCIAL_CONTRIBUTION",
     "order": 1, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 500}, "rounding": "HALF_UP"},
    {"id": "CNSS_EMPLOYER_PENSION", "party": "EMPLOYER", "category": "SOCIAL_CONTRIBUTION",
     "order": 2, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 500}, "rounding": "HALF_UP"},
    {"id": "CNSS_EMPLOYER_OCCUPATIONAL_RISK", "party": "EMPLOYER", "category": "SOCIAL_CONTRIBUTION",
     "order": 3, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 150}, "rounding": "HALF_UP"},
    {"id": "CNSS_EMPLOYER_FAMILY_BENEFITS", "party": "EMPLOYER", "category": "SOCIAL_CONTRIBUTION",
     "order": 4, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 650}, "rounding": "HALF_UP"},
    {"id": "IRPP_SALARY_WITHHOLDING", "party": "EMPLOYEE", "category": "INCOME_TAX",
     "order": 5, "requires": "IRPP_SUBJECT", "base": "GROSS", "sourceKey": "DGI_IRPP",
     "method": {"type": "PROGRESSIVE", "periodsPerYear": 12, "capBp": 3000,
                "brackets": [{"upToAnnual": 1944000, "rateBp": 300},
                             {"upToAnnual": 21600000, "rateBp": 1500},
                             {"upToAnnual": 43200000, "rateBp": 3000},
                             {"upToAnnual": null, "rateBp": 4000}]},
     "rounding": "HALF_UP"}
  ]
}
```

Closed vocabularies: `party` (`EMPLOYEE`, `EMPLOYER`), `category` (`SOCIAL_CONTRIBUTION`,
`INCOME_TAX`), `requires` (`CNSS_SUBJECT`, `IRPP_SUBJECT`), `base` (`GROSS`,
`GROSS_LESS_EMPLOYEE_SOCIAL`: gross minus the amounts of the employee `SOCIAL_CONTRIBUTION`
components with a lower `order`), `method.type` (`RATE`, `PROGRESSIVE`), `rounding` (`HALF_UP`, `DOWN`,
`UP`). Validation (application, fail closed): `order` unique and ascending, ids unique and from the
closed id pattern `[A-Z_]{3,48}`, 1 to 12 components, a `base` may only depend on a lower `order`,
`rateBp` 0 to 10000, brackets ascending with the last `upToAnnual` null, `rateBp` per bracket 0 to
10000, `periodsPerYear` 1 or 12, `capBp` 0 to 10000 or absent, every `sourceKey` present in the
pack's sources. A pack with an unknown field or value does not parse.

Calculation of one component for monthly base `B` (integer CDF), exact then rounded **once**:
- `RATE`: `round(B * rateBp / 10000)`.
- `PROGRESSIVE`: annual base `A = B * periodsPerYear`; slices over the brackets
  (`lower` = the previous `upToAnnual`, 0 for the first; slice = `max(0, min(A, upper) - lower)`);
  `N = sum(slice * rateBp)`; exact monthly tax `N / (10000 * periodsPerYear)`; with `capBp` the exact
  cap `B * capBp / 10000`; the result is `round(min(exact tax, exact cap))`, comparing `N` with
  `B * capBp * periodsPerYear` as integers. The trace stores `annualBase`, each slice, `N`, whether the
  cap applied and the base.
- `round(n/d)`: `HALF_UP` = `floor((2n + d) / 2d)`, `DOWN` = `floor(n/d)`, `UP` = `ceil(n/d)`.
- A component whose `requires` flag is false in the employee's profile has amount 0 and the
  explanation `NOT_SUBJECT`. The base is never negative; a zero base gives 0.

**DRAFT reference values** recorded in the seed (published, not verified): CNSS pension 5% employee and
5% employer, occupational risk 1.5% employer, family benefits 6.5% employer (Décret n°18/04, no
contribution ceiling shown on the CNSS page); DGI IRPP annual bands 0 to 1,944,000 at 3%, to 21,600,000
at 15%, to 43,200,000 at 30%, above at 40%, tax capped at 30% of the taxable base, annual thresholds
and no monthly schedule published. The pack uses `GROSS` as the IRPP base because the issue's limited
pack sets statutory basis = contractual gross; whether CNSS or other adjustments reduce the taxable
base is exactly one of the points that professional review must confirm, and `GROSS_LESS_EMPLOYEE_SOCIAL`
exists so a verified pack can express it. Periodization (annual thresholds divided by 12) is likewise
unconfirmed. That is why the real pack is DRAFT.

## 4. Activation

Table `payroll.statutory_activation` (tenant, legal entity, `id`, `sequence`, `pack_id`, `from_month`
(first day of a month), `acknowledged` = true, `activated_at`, `activated_by`): append-only. The pack
in effect for a legal entity and month `M` is the activation with the greatest `from_month <= M` (then
greatest `sequence`) whose pack interval covers `M`. `(tenant, entity, from_month)` is unique (a second
activation of the same month is `409 STATUTORY_VERSION_CONFLICT`), so one pack is in effect per entity
and month. Constraints: the pack is `VERIFIED` at insertion (trigger), `from_month` is inside the
pack's interval, and the entity belongs to the tenant (composite foreign key). Maximum 60 activations
per entity (`STATUTORY_LIMIT_REACHED`). A retired or later-superseded pack stops being in effect for
new calculations; frozen calculations never change.

Activation needs exact MFA, `acknowledged = true` (the employer remains responsible for legal review),
an `Idempotency-Key` and `expectedVersion` (count of the entity's activations). A DRAFT or RETIRED pack
is `409 STATUTORY_RULE_PACK_NOT_VERIFIED`; `fromMonth` outside the pack interval or a pack of another
country or currency is `409 STATUTORY_RULE_PACK_NOT_APPLICABLE`; an unknown pack is
`404 STATUTORY_RULE_PACK_NOT_FOUND`.

## 5. Employee statutory profile

Table `payroll.statutory_profile` (tenant, employee, `id`, `sequence` 1.., `effective_from`,
`country` = `CD`, `classification` (`STANDARD_RESIDENT`, `UNSUPPORTED`), `cnss_subject`,
`irpp_subject`, `evidence_code` from `EMPLOYMENT_CONTRACT`, `PAYROLL_FILE_REVIEW`,
`EMPLOYER_DECLARATION`, `PROFESSIONAL_REVIEW`, `created_at`, `created_by`): append-only (no update,
delete or truncate), unique `(tenant, employee, sequence)`. A `UNSUPPORTED` profile must have both
subject flags false. The profile in force on a day is the row with the greatest `effective_from <= day`
(then greatest `sequence`), so a correction is a new row. `version` = number of rows for the employee.
No identifiers, citizenship, dependants, health data or free text. At most 100 rows per employee.

Operations (tenant administrator, exact MFA): read, preview, commit. Preview returns the timeline
after the change, blockers (`EFFECTIVE_DATE_OUT_OF_RANGE` outside 2000-01-01..today+366 days,
`NO_CHANGE` when the profile in force on that date is identical, `LIMIT_REACHED`),
`version`, `previewDigest` (employee, version, command, expiry) and a 15-minute `expiresAt`.
Commit needs the digest, `expectedVersion`, `confirmed`, `Idempotency-Key`; drift is
`409 STATUTORY_PREVIEW_STALE`, a moved version `409 STATUTORY_VERSION_CONFLICT`. Audit actions
`statutory-profile.read` (fail-closed disclosure) and `statutory-profile.change` carry employee id,
sequence and codes only; outbox event `payroll.statutory-profile.changed.v1` is internal.

## 6. Calculation

Source: **one frozen MVP-094A run version** (`payroll.pay_run` and its lines), addressed by the path
`/legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations`.

**Run-level blockers** (stable codes; any one blocks, lines carry no amounts): `SOURCE_RUN_NOT_CURRENT`
(the run is not the current DRAFT of its chain), `CURRENCY_NOT_SUPPORTED` (the run currency is not
`CDF`; no exchange-rate policy exists), `RULE_PACK_MISSING` (no activation in effect for the month),
`RULE_PACK_NOT_VERIFIED` (the pack in effect is not `VERIFIED`, or not applicable to the month).
**Employee-level blockers** per source line: `PROFILE_MISSING` (no profile in force on the first
eligible day), `PROFILE_UNSUPPORTED` (classification `UNSUPPORTED`), `PROFILE_CHANGES_IN_MONTH` (a profile
row with an `effective_from` after the first and up to the last eligible day), `UNMODELED_EARNINGS` (an
earning other than contractual base exists for the employee and month: the port
`StatutoryEarningsGuard` in `platform.access`; the shipped implementation returns none because no other
earning type exists in the data model, and the engine blocks whenever the port reports one),
`DEDUCTIONS_EXCEED_GROSS` (employee deductions would exceed gross). Unknown or multiple currencies
cannot occur inside one run (one currency per run) and are covered by the run-level blocker.

Amounts are produced only when there is no run-level blocker; a `DRAFT` or `RETIRED` pack never produces
amounts and is shown as "Professional verification required". Employee-level profile blockers are
still reported so profiles can be completed.

**Per line** (supported lines only): `grossMinor` (frozen from the source line, never recalculated),
one component row per rule (`componentId`, `party`, `category`, `baseMinor`, `amountMinor`,
`explanationCodes`, `trace`), `employeeDeductionsMinor`, `employerContributionsMinor`,
`previewNetMinor = gross - employeeDeductions`, `employerCostMinor = gross + employerContributions`.
Employer components are never deducted from the employee. Explanation codes: `RATE_APPLIED`,
`PROGRESSIVE_APPLIED`, `CAP_APPLIED`, `NOT_SUBJECT`, `BASE_GROSS`, `BASE_GROSS_LESS_EMPLOYEE_SOCIAL`.
Counts: `supportedCount` (lines with at least one applicable component), `excludedCount` (lines for
which no component applies because the profile is not subject to CNSS nor IRPP; they are kept with
zero components, net = gross), `blockedCount`. Aggregates: `grossTotal`, `employeeDeductionsTotal`,
`employerContributionsTotal`, `previewNetTotal`, `employerCostTotal` (all CDF).

**Digests** (SHA-256 over versioned canonical text, ADR 0007 style): `sourceRunDigest` (run id,
version, status, `input_digest`, line ids and gross per line), `profileDigest` (every profile row of
every employee of the run, ordered), `rulePackDigest` (activation id and sequence, pack id, version,
status, `rules_digest`), `calculationDigest` = SHA-256(tenant, entity, run, pack, the three digests,
`expectedVersion`, expiry). Preview returns all four, `expectedVersion` and `expiresAt` (15 minutes,
on a 5-minute grid like ADR 0033). Commit repeats them and re-reads source run, activation, pack and
profiles under the locks; any difference or expiry is `409 STATUTORY_PREVIEW_STALE`.

**Commit order of checks:** idempotency; moved chain version `STATUTORY_VERSION_CONFLICT`; no
activation in effect or pack not verified `STATUTORY_RULE_PACK_NOT_VERIFIED`; pack not covering the
month or currency `STATUTORY_RULE_PACK_NOT_APPLICABLE`; any employee with `PROFILE_UNSUPPORTED`
-> `STATUTORY_PROFILE_UNSUPPORTED`; else any with `PROFILE_MISSING` -> `STATUTORY_PROFILE_MISSING`; else
any other blocker -> `STATUTORY_CALCULATION_BLOCKED`; identical to the current draft ->
`STATUTORY_CALCULATION_NO_CHANGE` (before the digest comparison); digests / expiry
`STATUTORY_PREVIEW_STALE`; 25 versions -> `STATUTORY_LIMIT_REACHED`. Preview never fails for a
blocker: it returns `status` `READY`, `BLOCKED` or `NO_CHANGE` with the blockers (the lesson of R178-1).

**Lifecycle.** `DRAFT` -> `SUPERSEDED` only. One current draft per `(tenant, source run, pack)`;
recalculation after a legitimate source, profile or pack change creates version N+1, supersedes N
(update names the successor first, then insert, deferred successor key, exactly like ADR 0033 section 7).
An identical recalculation writes nothing. Same-key replay returns the original amount-free receipt
(`calculationId`, `runId`, `legalEntityId`, `packId`, `packVersion`, `period`, `version`, `status`,
counts).

Tables: `payroll.statutory_calculation` (identity, source run id and version, `period_start`,
`pack_id`, `activation_id`, `version`, `status`, counts, the five totals, the four digests,
`created_at`, `created_by`, `superseded_at`, `superseded_by_id`),
`payroll.statutory_calculation_line` (calculation, `source_line_id` -> `payroll.pay_run_line`,
employee id and number, `gross_minor`, `employee_deductions_minor`, `employer_contributions_minor`,
`preview_net_minor`, `employer_cost_minor`, `excluded`), `payroll.statutory_calculation_component`
(line, `seq`, `component_id`, `party`, `category`, `base_minor`, `amount_minor`, `explanation_codes`,
`trace` jsonb). Constraints: closed enums, non-negative amounts, `net = gross - deductions`,
`employer_cost = gross + contributions`, line totals equal component sums, calculation totals equal
line sums and counts (deferred, at commit), `gross` equals the source line's gross, the source run is a
run of the same tenant and entity, the pack is `VERIFIED` or `RETIRED` at insertion, one `DRAFT` per
`(tenant, run, pack)` (partial unique index), CDF only, append-only except the supersession, no delete,
no truncate. Rollback `db/rollback/V45__rollback.sql` refuses while any activation, profile or
calculation row exists and never deletes evidence; it drops the seed DRAFT pack only when nothing
references it.

## 7. Locks (extends ADR 0033 section 8)

Idempotency reservation first. Profile commit: employee row `FOR SHARE` through the people port, then
the tenant advisory lock `payroll.statutory:{tenant}` **shared**, then the employee's profile chain.
Activation commit: tenant lock shared, then the entity advisory lock `payroll.statutory-activation:
{tenant}:{entity}` exclusive. Calculation commit: tenant lock **exclusive**, then the entity lock
`payroll.statutory-calculation:{tenant}:{entity}`; only then are run, activation, pack and profiles
re-read. Deferred constraint checks, audit and outbox come last. Reads take no lock.

## 8. Authority, privacy, limits

Tenant administrator with exact MFA for every operation; employees, managers and platform
administrators are refused (403, denial audited); another tenant's entity, run, employee or
calculation is a safe 404. Tenant and actor come from the verified token. Responses are
`Cache-Control: private, no-store`. Audit actions: `statutory-rule-pack.read` (disclosure),
`statutory-activation.read`, `statutory-activation.create`, `statutory-profile.read`,
`statutory-profile.change`, `statutory-calculation.read`, `statutory-calculation.create`; metadata
allow-list: ids, pack id and version, period, currency, status, version and aggregate counts - never
an amount, employee number, classification, flag, reviewer identity, digest, rule or trace. Outbox
events (`payroll.statutory-activation.created.v1`, `payroll.statutory-profile.changed.v1`,
`payroll.statutory-calculation.created.v1`) follow the same allow-list and are not delivered to
webhooks or WhatsApp. Receipts, cursors (opaque, signed, bound to caller, route and filters) and error
bodies carry no amount, flag, classification or reviewer identity.

Limits: 5,000 source lines (`STATUTORY_LIMIT_REACHED`), 25 versions per chain, 100 profile rows per
employee, 60 activations per entity. Rate limits: calculation preview 5 per subject per minute and 60
per tenant per hour; calculation commit 2 per subject per minute and 20 per tenant per hour; profile
preview 10 and commit 5 per subject per minute; activation 2 per subject per minute; reads 60 per
subject per minute. Explicit request and database timeouts; no background job. Metrics: operation,
outcome, line-count bucket, blocker-count bucket, duration, rule-pack version; a DRAFT-pack attempt is
counted as `blocked`, never as success.

## 9. Truth refresh (D94B-13)

The repository ships only a DRAFT pack, so the landing page, home and catalogue keep "DRC statutory
calculation" under **coming next**; the readiness state ("Professional verification required") is
shown only inside the administrator workflow. Payslips, approval, filing, disbursement and advances
stay coming next. `docs/BACKLOG.md` is reconciled with Git: MVP-094A merged (PR #178, merge commit
`dcd4d7e24c6a0c2a3af68a481a576d5eb3cc5430`), not deployed; hr-dev still runs `3223454…`.

## 10. Assumptions flagged for review

(a) The IRPP base is gross, annual bands are divided over 12 months and the 30% cap applies to the
taxable base: all unconfirmed, hence DRAFT. (b) `UNMODELED_EARNINGS` is wired through a port that
reports none today because the data model has no allowance, bonus, overtime-pay or retro-pay earnings.
(c) Packs become `VERIFIED` only by an operational procedure outside the application, with the
reviewer's evidence; the application has no verify endpoint. (d) An employee not subject to CNSS and
IRPP is kept in the calculation as `excluded` with zero components. (e) The profile effective on the
first eligible day must hold for the whole eligible period. (f) A pack ends for new calculations when
`RETIRED` or when a later activation takes effect; nothing frozen changes.

## 11. API operations

Contract in `docs/API-SPEC.yaml`, tag `payroll`; every operation is tenant administrator with exact MFA,
`Cache-Control: private, no-store`, and commits carry an `Idempotency-Key`. Lists use opaque keyset `cursor`
and `limit`.

| operationId | Method and path |
| --- | --- |
| `listStatutoryRulePacks` | `GET /statutory-rule-packs` (`status`, `cursor`, `limit`) |
| `getStatutoryRulePack` | `GET /statutory-rule-packs/{packId}` |
| `getStatutoryActivation` | `GET /legal-entities/{legalEntityId}/statutory-rule-pack?month=YYYY-MM` |
| `activateStatutoryRulePack` | `POST /legal-entities/{legalEntityId}/statutory-rule-pack/activation` |
| `getStatutoryProfile` | `GET /employees/{employeeId}/statutory-profile` |
| `previewStatutoryProfile` | `POST /employees/{employeeId}/statutory-profile/preview` |
| `commitStatutoryProfile` | `POST /employees/{employeeId}/statutory-profile` |
| `previewStatutoryCalculation` | `POST /legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations/preview` |
| `commitStatutoryCalculation` | `POST /legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations` |
| `listStatutoryCalculations` | `GET /legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations` (`cursor`, `limit`) |
| `getStatutoryCalculation` | `GET /legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations/{calculationId}` (`cursor`, `limit` for lines) |

Error codes (all in the Problem `code` enum, `ErrorCode.java` and the EN/FR `errors.*` messages):
`STATUTORY_RULE_PACK_NOT_FOUND` (404), `STATUTORY_RULE_PACK_NOT_VERIFIED`, `STATUTORY_RULE_PACK_NOT_APPLICABLE`,
`STATUTORY_PROFILE_MISSING`, `STATUTORY_PROFILE_UNSUPPORTED`, `STATUTORY_CALCULATION_BLOCKED`,
`STATUTORY_PREVIEW_STALE`, `STATUTORY_CALCULATION_NO_CHANGE`, `STATUTORY_VERSION_CONFLICT`,
`STATUTORY_LIMIT_REACHED` (409) and `STATUTORY_CALCULATION_NOT_FOUND` (404). An unknown employee on the profile
operations is `COMPENSATION_NOT_FOUND`, as for the compensation operations; the run is `PAYROLL_RUN_NOT_FOUND` and the
legal entity `LEGAL_ENTITY_NOT_FOUND`.
