# ADR 0035: Payroll approval and bilingual payslips (MVP-094C, Issue #181)

Status: accepted for implementation (decisions D94C-1 to D94C-16 of Issue #181 are binding; this ADR
fixes the design points the issue leaves to the implementation). Builds on ADR 0033 (monthly payroll
runs) and ADR 0034 (DRC statutory preview). Next migration: **V46**. Third and last story of the payroll
release train (094A run, 094B statutory preview, 094C approval and payslips).

## 1. Meaning and boundary

An **approved payroll package** is the immutable approval record that joins one frozen MVP-094A payroll
run version and one current MVP-094B statutory calculation version of a legal entity, a closed month and
the CDF currency, and publishes one immutable canonical **payslip snapshot** per employee. It may be
**voided** before any payment exists. It is not a payment, a disbursement, a filing, an accounting entry, a
digital signature or a claim of legal compliance. A payslip is **not proof of payment**.

Product language (EN / FR): "Approved payroll" / « Paie approuvée »; "Payslip" / « Bulletin de paie »;
"Not proof of payment" / « Ne constitue pas une preuve de paiement »; "Statutory calculation based on rule
pack {code} v{version}" / « Calcul statutaire fondé sur le jeu de règles {code} v{version} ». Never
"paid", "payment completed", "filed", "tax compliant" or "government approved" (EN) and never « payé »,
« paiement effectué », « déclaré », « conforme fiscalement » or « approuvé par l'État » (FR).

Money is restricted financial data in CDF whole francs (minor unit = 1 CDF, scale 0). All amounts are
integers (`BigInteger`/`bigint`); nothing is recalculated at approval: **every amount is copied from the
frozen 094B calculation line** and the database proves the copy (section 3).

## 2. Decision index

| Id | Decision | Where |
| --- | --- | --- |
| D94C-1 | Package = approval joining run, calculation, pack, policy, lines, totals, approver, digest; copies everything needed to render | sections 3, 4 |
| D94C-2 | Authority: tenant admin + exact MFA; linked employee own payslips only; same-preparer fact exposed | sections 5, 13 |
| D94C-3 | Approval preview: closed blockers/warnings, digest and 15 min expiry, no write | section 6 |
| D94C-4 | Approval commit: precedence, locks, source lock, receipt | sections 7, 8, 9 |
| D94C-5 | Lifecycle APPROVED -> VOIDED, void evidence, payment guard | sections 4, 10, 11 |
| D94C-6 | Canonical snapshot and digests | section 4 |
| D94C-7 | Bilingual rendering, deterministic PDF, headers | section 12 |
| D94C-8 | Employee publication | section 13 |
| D94C-9 | Migration V46, full database enforcement, rollback | section 3 |
| D94C-10 | Contract-first API | section 16 |
| D94C-11 | Twelve stable errors | section 15 |
| D94C-12 | Privacy, audit/outbox allow-lists | section 14 |
| D94C-13 | UI (web worker; not in this ADR beyond contract needs) | section 16 |
| D94C-14 | Truth refresh (web/docs worker) | not here |
| D94C-15 | Synthetic hr-dev boundary | section 17 |
| D94C-16 | Limits, rate limits, timeouts, metrics | section 18 |

## 3. Database (V46, `db/migration/V46__payroll_approval_payslips.sql`)

Schema `payroll`, no GRANT/REVOKE (as V43-V45: authority is in triggers). Constraint violations are
SQLSTATE `23514` with `CONSTRAINT_NAME` starting `payroll_package_` or `payroll_payslip_`; uniqueness
violations are `23505`; foreign keys `23503`. Seeds nothing. Applied on PostgreSQL 16 (section 20).

### 3.1 `payroll.payroll_package`

PK `(tenant_id, legal_entity_id, id)`; `UNIQUE (tenant_id, id)`; `UNIQUE (tenant_id, legal_entity_id,
period_start, currency, version)` = `payroll_package_version_unique`.

| Column | Meaning |
| --- | --- |
| `tenant_id`, `legal_entity_id`, `id` | identity; FK `payroll_package_legal_entity` |
| `period_start` (first day of month), `currency` = `CDF`, `version` 1..25 | the chain `(tenant, entity, period, currency)`; version = max + 1 (trigger) |
| `status` | `APPROVED` or `VOIDED` |
| `run_id`, `run_version`, `run_digest` | frozen `pay_run` (FK `payroll_package_run`), `run_digest` = `pay_run.input_digest` |
| `calculation_id`, `calculation_version`, `calculation_digest`, `calculation_source_run_digest`, `calculation_profile_digest`, `calculation_rule_pack_digest` | frozen `statutory_calculation` (FK `payroll_package_calculation`) and its four stored digests |
| `pack_id`, `pack_code`, `pack_version`, `rules_digest`, `synthetic` | rule pack (FK `payroll_package_pack`); `synthetic` is GENERATED `left(pack_code,14) = 'SYNTHETIC-TEST'` |
| `policy_id`, `policy_sequence` | payroll policy frozen in the run (FK `payroll_package_policy`) |
| `payslip_count` 1..5000 | = payslip rows = calculation `supported_count + excluded_count` |
| `gross_total_minor`, `employee_deductions_total_minor`, `employer_contributions_total_minor`, `net_total_minor`, `employer_cost_total_minor` | aggregate totals (`bigint`) |
| `reviewed_totals`, `reviewed_exceptions`, `understands_no_payment` | the three confirmations, CHECK all true (`payroll_package_acknowledgement`) |
| `same_preparer` | the approver is the creator of the run or of the calculation (trigger-verified) |
| `approval_digest` | section 4.3, recomputed by the database at commit |
| `approved_at`, `approved_by_subject` | approval instant, verified JWT subject (restricted, never exposed) |
| `voided_at` | null while APPROVED; set by the one lifecycle step |

CHECKs: `payroll_package_month`, `_currency`, `_version_range`, `_status`, `_lifecycle`
(`APPROVED` iff `voided_at IS NULL`), `_payslip_count`, `_totals`, `_arithmetic` (`net = gross -
employee deductions` and `employer cost = gross + employer contributions`), `_acknowledgement`,
`_digests`, `_approved_by_length`.

Indexes: `payroll_package_one_approved` UNIQUE `(tenant_id, legal_entity_id, period_start, currency)
WHERE status = 'APPROVED'` (one approved package per entity, month and currency),
`payroll_package_one_approved_run` UNIQUE `(tenant_id, run_id) WHERE APPROVED`,
`payroll_package_one_approved_calculation` UNIQUE `(tenant_id, calculation_id) WHERE APPROVED`,
`payroll_package_history` `(tenant_id, legal_entity_id, period_start DESC, version DESC, id)` (package
list keyset), `payroll_package_pair` `(tenant_id, run_id, calculation_id)`, FK indexes
`payroll_package_calculation_fk`, `payroll_package_pack`, `payroll_package_policy_fk`.

Triggers: `payroll_package_guard` (BEFORE INSERT OR UPDATE), `payroll_package_no_delete`,
`payroll_package_no_truncate`, deferred constraint trigger `payroll_package_exact` (INSERT OR UPDATE).
INSERT guard (all raise 23514 with the named constraint): advisory lock `payroll.package:{tenant}:{entity}`;
`payroll_package_version` (status APPROVED, `voided_at` null, version = max+1); the run and the calculation are
re-read **`FOR SHARE`**; `payroll_package_run_state` (run is DRAFT, same version, `input_digest`,
period, CDF and policy); `payroll_package_calculation_state` (calculation DRAFT, of this run and version, same
pack, period, currency and four digests); `payroll_package_pack_state` (pack `VERIFIED` and code, version,
`rules_digest` equal); `payroll_package_totals_source` (count and totals equal the calculation's);
`payroll_package_approved_at` (not before the calculation's `created_at`); `payroll_package_same_preparer`
(`same_preparer = approved_by_subject IN (pay_run.created_by, statutory_calculation.created_by)`);
`payroll_package_source_voided` (a `(run_id, calculation_id)` pair that is in any VOIDED package is refused
forever). UPDATE guard: the only legal update is APPROVED -> VOIDED with `voided_at` equal to the void
evidence row, which must already exist (`payroll_package_lifecycle_only`, `payroll_package_void_evidence`).
Deferred `payroll_package_exact` (`payroll_package_void_evidence`, `payroll_package_exact`,
`payroll_package_sources_exact`, `payroll_package_approval_digest`): exactly one void row iff VOIDED;
payslip count and sums equal the package's and the calculation's; exactly two source rows; the recomputed
approval digest equals `approval_digest`.

### 3.2 `payroll.payroll_package_source` (append-only binding and lock evidence)

PK `(tenant_id, package_id, kind)`; FK `payroll_package_source_package`; `kind` in `RUN`, `CALCULATION`;
`source_id`, `source_version`, `source_digest`. Guard `payroll_package_source_binding`: the row names the
package's own run (`run_id`, `run_version`, `run_digest`) or calculation (`calculation_id`,
`calculation_version`, `calculation_digest`) and the package is APPROVED. Index `payroll_package_source_lookup`
`(tenant_id, kind, source_id)`. UPDATE/DELETE/TRUNCATE refused (`payroll_package_immutable`). Deferred
`payroll_package_source_exact`: exactly two rows per package.

### 3.3 `payroll.payroll_package_void` (append-only void evidence)

PK `(tenant_id, legal_entity_id, id)`; `UNIQUE (tenant_id, package_id)` = `payroll_package_void_package_unique`
(one void per package); FK `payroll_package_void_package`. Columns: `reason_code` (CHECK
`payroll_package_void_reason`: `SOURCE_CORRECTION`, `PROFILE_CORRECTION`, `RULE_PACK_CORRECTION`,
`ADMINISTRATIVE_ERROR`, `OTHER_CONTROLLED`), `explanation` (CHECK `payroll_package_void_explanation`: present,
10..500 characters, trimmed, no control character **iff** `OTHER_CONTROLLED`, else null),
`no_payment_acknowledged` (CHECK true: `payroll_package_void_acknowledgement`), `approval_digest` (must equal the
package's), `voided_at`, `voided_by_subject` (1..255). Guard `payroll_package_void_state` (package APPROVED, digest
equal, `voided_at >= approved_at`; the package row is locked `FOR UPDATE`). Append-only. Deferred
`payroll_package_void_exact`: the package is VOIDED at commit.

### 3.4 `payroll.payroll_payslip` (append-only canonical snapshot)

PK `(tenant_id, package_id, id)`; `UNIQUE (tenant_id, id)`; `UNIQUE (tenant_id, package_id, employee_id)`
(`payroll_payslip_employee_unique`); `UNIQUE (tenant_id, package_id, calculation_line_id)`. FKs:
`payroll_payslip_package` `(tenant_id, legal_entity_id, package_id)`, `payroll_payslip_employee`
`(tenant_id, employee_id) -> people.employee`, `payroll_payslip_line` `(tenant_id, calculation_id,
calculation_line_id) -> statutory_calculation_line`.

Columns: `legal_entity_id`, `period_start`, `approved_at` (denormalised, trigger-checked equal to the package),
`employee_id`, `employee_number`, `calculation_id`, `calculation_line_id`, `excluded`, `gross_minor`,
`employee_deductions_minor`, `employer_contributions_minor`, `net_minor`, `employer_cost_minor` (CHECK
`payroll_payslip_arithmetic`: net = gross - employee deductions, cost = gross + employer contributions;
`payroll_payslip_amounts`; `payroll_payslip_excluded`), `snapshot_version` = 1, **`snapshot_text`** (the canonical
UTF-8 text, 2..16384 characters, authoritative), **`snapshot`** `jsonb` GENERATED from the text,
**`snapshot_digest`** `char(64)` GENERATED = lower-case hex SHA-256 of the UTF-8 bytes of `snapshot_text`
(`payroll.payroll_snapshot_digest`, like `rules_digest` in V45; a generated column is not available in a BEFORE
trigger, so the guard parses the text itself).

Indexes: `payroll_payslip_employee_page` `(tenant_id, employee_id, approved_at DESC, id DESC)` (employee list,
newest first), `payroll_payslip_package_page` `(tenant_id, package_id, (employee_number COLLATE "C"), id)`
(admin list), `payroll_payslip_line_fk`, `payroll_payslip_package_fk`.

Guard `payroll_payslip_guard` (BEFORE INSERT): `payroll_payslip_package_state` (package APPROVED; month, approval
instant and calculation equal); `payroll_payslip_source_line` (employee id/number, `excluded` and **every amount**
equal the calculation line); `payroll_payslip_snapshot_fields` (exactly the 19 canonical top-level keys);
`payroll_payslip_snapshot_values` (every value equals the columns or the package, `approvedAt` format
`YYYY-MM-DD"T"HH24:MI:SS.US"Z"`, `status` = APPROVED, rule pack code/version); `payroll_payslip_snapshot_sums`
(employee CNSS + IRPP = employee deductions, employer components = employer contributions: **employer contributions
never reduce net**); `payroll_payslip_snapshot_components` (components equal the calculation components in order);
`payroll_payslip_snapshot_explanations` (line codes = sorted distinct component codes). Append-only:
`payroll_payslip_immutable` on UPDATE, DELETE, TRUNCATE. Deferred `payroll_payslip_exact` (`payroll_payslip_set_exact`):
a payslip can only be added with its package (row count equals `payslip_count`).

### 3.5 Source lock

Triggers added to V44/V45 tables: `payroll_pay_run_package_lock` BEFORE UPDATE ON `payroll.pay_run` and
`payroll_statutory_calculation_package_lock` BEFORE UPDATE ON `payroll.statutory_calculation`, both calling
`payroll.payroll_package_source_lock(kind)`: if a `payroll_package_source` row for that run/calculation belongs to a
package with `status = 'APPROVED'`, raise `23514` constraint **`payroll_package_source_locked`**. The sole legal
update of both tables is the supersession, so supersession is what is refused. A VOIDED package releases the lock
(the binding rows remain as evidence). The recalculation of 094A (new run version) and of 094B (new calculation
version) both need that supersession, so neither can proceed while a package is APPROVED. The package insert
reads run and calculation `FOR SHARE`, so a supersession racing an approval either commits first (the approval
then fails `payroll_package_run_state`/`_calculation_state`) or waits for the approval and meets the lock
(validated with two sessions, section 20).

### 3.6 Rollback

`db/rollback/V46__rollback.sql` (manual, never run by Flyway) locks the four tables and **refuses**
(`RAISE EXCEPTION 'V46 rollback refused: ...'`) while any package, source, void or payslip row exists; otherwise
drops the two lock triggers, the four tables and twelve functions, restoring V45 exactly (verified by a
schema-only dump diff, section 20). It does not edit `flyway_schema_history`.

## 4. Canonical snapshot and digests

### 4.1 Canonical snapshot (schemaVersion 1)

One snapshot per **calculation line** (all lines: supported and excluded; blocked lines cannot exist in a
committed calculation). Canonical JSON text: UTF-8, no whitespace outside strings, no byte-order mark, object
keys in exactly the order below (not alphabetical), strings escaped as RFC 8259 minimal (`"` `\\` and control
characters as `\u00xx` lower-case hex; `/` and non-ASCII are not escaped), integers in the JSON-number form
(`1`), amounts as **decimal strings of whole francs** with no sign, no leading zero, no grouping (like 094B
`Money`, minor unit 1 CDF), no null and no absent field:

```
{"schemaVersion":1,
 "packageId":"<uuid>","legalEntityId":"<uuid>","period":"YYYY-MM","currency":"CDF",
 "employeeId":"<uuid>","employeeNumber":"<text>",
 "status":"APPROVED","approvedAt":"YYYY-MM-DDTHH:MM:SS.ffffffZ",
 "rulePack":{"code":"<code>","version":<int>},
 "excluded":<bool>,
 "contractualGrossMinor":"<n>","employeeCnssMinor":"<n>","irppWithholdingMinor":"<n>",
 "employeeDeductionsMinor":"<n>","netMinor":"<n>","employerContributionsMinor":"<n>",
 "explanationCodes":["<code>",...],
 "components":[{"seq":<int>,"id":"<ID>","party":"EMPLOYEE|EMPLOYER","category":"SOCIAL_CONTRIBUTION|INCOME_TAX",
                "baseMinor":"<n>","amountMinor":"<n>","explanationCodes":["<code>",...]},...]}
```

* `approvedAt` is the approval instant in UTC with exactly six fractional digits (microsecond).
* `status` is the status **at publication** and is always `APPROVED`; the current status (VOIDED) lives only on
  the package, never in the immutable snapshot.
* `employeeCnssMinor` = sum of the EMPLOYEE `SOCIAL_CONTRIBUTION` components; `irppWithholdingMinor` = sum of
  the EMPLOYEE `INCOME_TAX` components; `employeeDeductionsMinor` = both; `netMinor` = gross - employee
  deductions; `employerContributionsMinor` = sum of the EMPLOYER components, shown separately and **never**
  subtracted from net (database-checked, section 3.4). Employer cost is **not** in the snapshot (it is an
  employer figure, kept on the row and the package).
* `components` are the calculation components in rule-pack order with their per-component `explanationCodes` in
  stored order; empty for an excluded line. Line `explanationCodes` = sorted (byte order) distinct union of the
  component codes (`[]` for an excluded line).
* **Excluded** from every snapshot (D94C-6): national ID, tax number, CNSS number, bank or mobile-money detail,
  home address, health or family data, approver subject, administrative notes, employee name, the calculation
  `trace`, any digest of the calculation, the void evidence. The snapshot holds **employee number only**; see
  open question 1.

The API object `PayslipSnapshot` is the same data with `CompensationMoney` objects for the amounts (values
identical to the canonical strings) and `snapshotDigest` beside it. The text is stored once and rendered, never
duplicated as mutable business data.

### 4.2 Snapshot digest

`snapshot_digest` = lower-case hex SHA-256 of the UTF-8 bytes of the exact stored `snapshot_text`. It is the
same in English and French, before and after a void (the original digest "remains verifiable"). The database
generates it; the application never supplies it.

### 4.3 Approval digest (stored, recomputed by the database)

`approval_digest` = lower-case hex SHA-256 of the UTF-8 text (every line ends with `\n`, uuids lower-case):

```
payroll-package-approval:v1
tenant=<tenant_id>
entity=<legal_entity_id>
period=<YYYY-MM>
currency=CDF
run=<run_id>|<run_version>|<run_digest>
calculation=<calculation_id>|<calculation_version>|<calculation_digest>|<calculation_source_run_digest>|<calculation_profile_digest>|<calculation_rule_pack_digest>
pack=<pack_id>|<rules_digest>
policy=<policy_id>|<policy_sequence>
totals=<gross>|<deductions>|<employer contributions>|<net>|<employer cost>|<payslip_count>
<employee_id>|<employee_number>|<snapshot_digest>      (one line per payslip, employee_number in byte order, then employee_id)
```

It excludes the chain version, the approver, the approval time and the acknowledgements (so identical sources
give an identical digest), and includes every payslip digest, so any later change of a snapshot, line or total is
detectable. The Python reference computed from this text equals `payroll.payroll_package_approval_digest()`
(section 20).

### 4.4 Approval preview digest

`previewDigest` = SHA-256 of `DIVALHR-PAYROLL-APPROVAL-PREVIEW\nversion=1\n` + `tenant=`, `entity=`, `run=<id>`,
`runVersion=`, `calculation=<id>`, `calculationVersion=`, `sourceRunDigest=`, `calculationDigest=`,
`profileDigest=`, `rulePackDigest=`, `policy=<policy_id>;<sequence>`, `samePreparer=<true|false>`,
`expectedVersion=`, `expiresAt=<Instant ISO>` each followed by `\n` (house style of `StatutoryDigests`).
`expiresAt` is 15 minutes after the preview (same expiry grid as 094A/094B). The digest is bound to the committing
subject only through `samePreparer` (a different administrator committing a preview of another with a different
fact gets the stale error).

## 5. Authority

Every administrator operation is `@TenantAdminOperation`: role `tenant-admin`, exact MFA (`acr`
`urn:divalhr:loa:mfa`), active membership, checked before any processing, durable denial evidence. The employee
operations are role `employee` and resolve the employee from the caller's **current active employee-access link**
on every request (403 `EMPLOYEE_LINK_REQUIRED` otherwise, as `/me/compensation`). Managers, unlinked employees,
platform administrators and other tenants gain nothing: an unauthorized or foreign identifier is the same 404
(`PAYROLL_PACKAGE_NOT_FOUND`, `PAYSLIP_NOT_FOUND`, `LEGAL_ENTITY_NOT_FOUND`, `PAYROLL_RUN_NOT_FOUND`,
`STATUTORY_CALCULATION_NOT_FOUND`). Tenant and actor come from the verified token; the approver is **never** in the
request. Every tenant statement carries the tenant predicate; the employee payslip queries carry
`tenant_id = :tenant AND employee_id = :linked employee`.

**Same-preparer fact.** V44 stores `pay_run.created_by` and V45 `statutory_calculation.created_by` (the verified
subject of the creator). The *preparer* is therefore the set {creator of the run, creator of the calculation}. The
preview returns `samePreparer` (and the warning `SAME_PREPARER_AND_APPROVER`), the package stores `same_preparer`
(database-verified against both columns) and the approval audit carries the boolean. Limitation (stated honestly):
profile, activation and policy edits made by others are not part of "preparer", and no separation of duties is
enforced: MVP-094C permits the same administrator; **separation of preparer and approver, multi-step approval and
monetary thresholds are future governance work.**

## 6. Approval preview

`POST .../statutory-calculations/{calculationId}/approval/preview` (no body fields; path names legal entity, run and
calculation). It writes nothing except the fail-closed, amount-free disclosure audit `payroll-package.read`. It
never fails for a blocker (the R178-1 lesson): `status` is `READY`, `BLOCKED` or `NO_CHANGE` (an APPROVED package
exists for exactly this run and calculation); `previewDigest` and `expiresAt` are null unless READY.

**Four-part staleness evidence** (recomputed live by the same digest functions as 094A/094B and compared with what
the calculation stored): (1) `sourceRunDigest` of the frozen run (and a read-only 094A recomputation that must equal
`run.input_digest`, else `SOURCE_CHANGED`); (2) `calculationDigest` as stored; (3) `profileDigest` of the profiles now
in force (must equal the calculation's, else `PROFILE_CHANGED`); (4) `rulePackDigest` of the activation and pack now in
effect (must equal, else `RULE_PACK_CHANGED`). The payroll policy in force on the month's last day must equal the
frozen run policy (else `POLICY_CHANGED`). At commit the four digests supplied by the client must equal the live ones
**and** `previewDigest` must equal its recomputation; any difference or expiry is
`PAYROLL_APPROVAL_PREVIEW_STALE`.

Closed **blockers** (`PayrollApprovalBlocker`): `SOURCE_RUN_NOT_CURRENT`, `CALCULATION_NOT_CURRENT`,
`CALCULATION_INCOMPLETE` (lines missing or counts differ), `RULE_PACK_NOT_VERIFIED` (not VERIFIED, including
RETIRED/DRAFT), `POLICY_CHANGED`, `PROFILE_CHANGED`, `RULE_PACK_CHANGED`, `SOURCE_CHANGED`,
`CURRENCY_NOT_SUPPORTED`, `NO_PAYABLE_LINES` (no line with net > 0), `ARITHMETIC_MISMATCH` (any line/total/count
differs from its sources), `ALREADY_APPROVED` (another APPROVED package for the entity/month/currency from other
sources), `SOURCE_ALREADY_VOIDED` (the run and calculation were in a voided package: a corrected chain is required),
`SOURCE_MISSING`, `LIMIT_REACHED`. Closed **warnings**: `SAME_PREPARER_AND_APPROVER`, `SYNTHETIC_RULE_PACK`,
`EXCLUDED_LINES_PRESENT`, `ZERO_NET_LINES_PRESENT`. The preview returns aggregate `totals` (contractual gross,
employee deductions, employer contributions, approved net, employer cost), `payslipCount`, `excludedCount`, one
`PayrollApprovalLine` per employee (employee id and number, no name: the 094A/094B line schemas expose none),
`expectedVersion` (count of packages of the chain), `existingPackage`, `pack` with `synthetic`.

## 7. Approval commit

`POST .../approval` with `expectedVersion`, the four digests, `previewDigest`, `expiresAt`, and the three
confirmations `reviewedTotals`, `reviewedExceptions`, `understandsNoPayment` (all must be true, else 400
`VALIDATION_FAILED`), `Idempotency-Key`, exact MFA. Order of checks (first failure wins):

1. Idempotency reservation (a same-key same-payload replay returns the original 201 with `Idempotent-Replayed`;
   a different payload is 409 `IDEMPOTENCY_KEY_REUSED`).
2. NOT_FOUND: `LEGAL_ENTITY_NOT_FOUND`, `PAYROLL_RUN_NOT_FOUND`, `STATUTORY_CALCULATION_NOT_FOUND` (404).
3. `PAYROLL_PACKAGE_VERSION_CONFLICT`: `expectedVersion` differs from the chain's package count.
4. `PAYROLL_PACKAGE_ALREADY_APPROVED`: an APPROVED package of the chain exists from a different run or calculation.
5. `PAYROLL_PACKAGE_BLOCKED`: any blocker of section 6 (including `SOURCE_ALREADY_VOIDED`).
6. `PAYROLL_APPROVAL_NO_CHANGE`: an APPROVED package exists for exactly this run and calculation (new key, same
   data; checked before the digest comparison, like 094B).
7. `PAYROLL_APPROVAL_PREVIEW_STALE`: expiry or any digest difference.
8. `PAYROLL_PACKAGE_LIMIT_REACHED`: more than 5,000 payslips or a 26th package in the chain.

In **one transaction**: insert the APPROVED package (version = count + 1), the two `payroll_package_source` rows,
one `payroll_payslip` row per calculation line with its canonical text, the audit record and the outbox event;
deferred checks fire at commit. A concurrent duplicate loses to the unique index
`payroll_package_one_approved` (23505, mapped to `PAYROLL_PACKAGE_VERSION_CONFLICT` if the loser's
`expectedVersion` is stale, else `PAYROLL_PACKAGE_ALREADY_APPROVED`) or to the lock order below: exactly one
package results. Response: amount-free `PayrollApprovalReceipt` (201): ids, versions, pack id/version, period,
currency, `status`, `synthetic`, `payslipCount`, `samePreparer`, `approvedAt`.

## 8. Lock order (extends ADR 0033 section 8 and ADR 0034 section 7)

Global order for every payroll write: **idempotency reservation -> tenant lock -> legal-entity locks -> run row ->
calculation row -> package** (rows `FOR UPDATE`/`FOR SHARE` last). Concretely:

* Approval commit: (1) idempotency; (2) tenant advisory lock `payroll.statutory:{tenant}` **exclusive**
  (freezes profile and activation commits); (3) entity locks in this order: `payroll.run:{tenant}:{entity}` (094A),
  `payroll.statutory-calculation:{tenant}:{entity}` (094B), `payroll.package:{tenant}:{entity}` (094C); (4) the run
  row, then the calculation row `FOR UPDATE`; (5) the entity's package rows (chain) `FOR UPDATE` in id order; only
  then (6) re-read of policy, activation, pack and profiles, the 094A recomputation, and the inserts; deferred
  constraints, audit and outbox last. The database triggers re-take the same advisory keys (re-entrant, free).
* Void commit: idempotency, tenant lock `payroll.statutory:{tenant}` **shared**, `payroll.package:{tenant}:{entity}`,
  the package row `FOR UPDATE`, the payment guard, inserts.
* 094A/094B recalculation locks are unchanged; they take run/calculation locks *before* any package lock, which is
  the same order, so no cycle exists. Reads take no lock.

## 9. Package lifecycle and void

`APPROVED -> VOIDED` only; no delete, reopen, in-place correction or return. Void preview
(`POST .../payroll-packages/{packageId}/void/preview`, body `reasonCode` + `explanation`) returns `READY` or
`BLOCKED` (`ALREADY_VOIDED`, `PAYMENT_EXISTS`), `approvalDigest`, `previewDigest`, 15-minute `expiresAt`; the
explanation rule is validated here (a missing or superfluous explanation is 400 `VALIDATION_FAILED` with a field
error, never echoing the text). Void commit (`POST .../void`) with `reasonCode`, `explanation`, `packageVersion`,
`approvalDigest`, `previewDigest`, `expiresAt`, `noPaymentAcknowledged` (true), `Idempotency-Key`, exact MFA. Order:
1 idempotency; 2 `PAYROLL_PACKAGE_NOT_FOUND`; 3 `PAYROLL_PACKAGE_VOIDED`; 4 `PAYROLL_PACKAGE_VERSION_CONFLICT`
(`packageVersion` or `approvalDigest` differs); 5 `PAYROLL_VOID_NOT_ALLOWED` (payment guard); 6
`PAYROLL_VOID_PREVIEW_STALE` (expiry or `previewDigest`). One transaction: insert the void evidence row, update
the package (APPROVED -> VOIDED, `voided_at` = evidence instant), audit, outbox; the explanation is stored **only**
in `payroll_package_void.explanation` and returned only by `getPayrollPackage` to the tenant administrator; never in
audit, outbox, idempotency receipt, cursor, error, log or metric, nor to employees. Payslip snapshots are never
touched; the same canonical text and digest are rendered with a prominent VOIDED status and the closed reason code
(never the explanation). A void **releases** the source lock. A void permits a later corrected chain but a
`(run, calculation)` pair that was ever in a VOIDED package can **never** be approved again
(`SOURCE_ALREADY_VOIDED`, database `payroll_package_source_voided`): the corrected chain must contain a new run
version (094A) and a new calculation (094B).

## 10. Payment guard (D94C-5)

Port `com.divalhr.core.platform.access.PayrollPaymentGuard#paymentExists(TenantId, UUID legalEntityId, UUID
packageId)` (read only; identifiers and a boolean). Shipped implementation `payroll.application.NoPaymentRecords`
reports `false` (no payment or disbursement integration exists), mirroring `StatutoryEarningsGuard` /
`NoUnmodeledEarnings`. A future payment module implements the port; the void preview reports `PAYMENT_EXISTS` and
the commit fails closed with `PAYROLL_VOID_NOT_ALLOWED` without a change to the approval service.

## 11. Source lock proof obligations

(a) supersession of a bound run/calculation is `23514 payroll_package_source_locked`; (b) after a void the same
supersession proceeds; (c) approving a voided pair is `23514 payroll_package_source_voided`; (d) an approval racing
a supersession ends with exactly one winner. All four were exercised on PostgreSQL 16 (section 20).

## 12. Rendering, HTML and PDF (D94C-7)

* The **HTML view** is the accessible primary representation: `getMyPayslip` / `getPackagePayslip` return
  `PayslipView` (JSON: canonical snapshot fields, current status, snapshot digest, server-rendered `wording` in the
  selected locale); the web renders it with semantic headings and a `<table>`. `locale` is `en` or `fr`; it
  **selects wording only**; absent, the first of `en`/`fr` in `Accept-Language`, else `en`; another value is 400
  `LOCALE_NOT_SUPPORTED`. Amounts, rule-pack code/version and digest are identical in both languages.
* **Rendered bytes are not stored.** The PDF is rendered on demand from the immutable `snapshot_text` (plus the
  package status and reason code for a VOIDED copy), so no duplicate mutable business data exists and no content
  addressing is needed. Determinism is therefore a property of the renderer and is tested (same input, same
  bytes).
* **PDF** (PDFBox 3.0.8, repository-owned code and strings, no remote asset, no embedded font file):
  Standard-14 Helvetica / Helvetica-Bold with `WinAnsiEncoding` (covers French accents and « ’ »); a character of
  the employee number outside WinAnsi is replaced by `?` in the PDF only (limitation 6). A4 portrait, one page.
  Deterministic: no compression (`CompressParameters.NO_COMPRESSION`), the trailer `/ID` is the first 16 bytes of
  the snapshot digest twice, `CreationDate` = `ModDate` = the approval instant (UTC), the Info dictionary holds
  only `Title` (generic localized title, e.g. "Payslip" / « Bulletin de paie », with " - VOIDED" / « - ANNULÉ »
  on a voided copy; no name, number or period), `Creator` and `Producer` = the constant `DivalHR`; no `Author`,
  `Subject`, `Keywords`, XMP, file path, host, subject, token, email or local filename. `/Lang` is `en` or `fr`,
  `/MarkInfo << /Marked true >>`, `ViewerPreferences/DisplayDocTitle true`, a minimal tagged structure
  (`Document > H1`, `P`, `Table > TR > TH/TD` with marked content). Full PDF/UA conformance is not claimed.
  Content: title, "Approved payroll"/« Paie approuvée » (or VOIDED banner with the reason label), period, legal
  entity id is not printed, employee number, rule pack statement, the amount table (contractual gross, employee
  CNSS, IRPP, total employee deductions, net), employer contributions in a separate block with the sentence that
  they do not reduce the net, the "Not proof of payment" sentence, the synthetic test notice when applicable, and
  the 64-hex snapshot digest. **Size cap 256 KiB**; render timeout 5 s; exceeding either is 500
  `PAYSLIP_RENDER_FAILED` with nothing partial.
* **Filename** `payslip-<YYYY-MM>-<first 8 hex of the payslip id without dashes>.pdf`. **Headers**:
  `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="..."`, `Cache-Control: private,
  no-store`, `X-Content-Type-Options: nosniff`, plus the usual correlation header. Caddy already deletes
  `Content-Disposition` from logged response headers (SEC-003); a new canary sentinel for the payslip filename is
  required of the ops/web workers. The download audit (`payslip.download`) commits **before** any byte is returned.
* Mandatory wording keys (both locales, repository-owned, the web localization package carries the UI copy): title,
  status label (Approved payroll / Paie approuvée; VOIDED / ANNULÉ), "Not proof of payment" / « Ne constitue pas une
  preuve de paiement », "Employer contributions are shown separately and do not reduce the employee's net." /
  « Les cotisations patronales sont indiquées séparément et ne réduisent pas le net du salarié. »,
  "Statutory calculation based on rule pack {code} v{version}" / « Calcul statutaire fondé sur le jeu de règles
  {code} v{version} », the synthetic notice, the voided notice. None says paid/filed/compliant/approved by the
  government.

## 13. Employee publication (D94C-8)

Payslips are visible only after the APPROVED commit (same transaction). `listMyPayslips`: newest first by
`(approved_at DESC, id DESC)` keyset, default 25, maximum 100, cursor bound to operation, tenant, subject, role and
page size (carries no amount, number or digest). Reads and downloads require `tenant_id = :tenant AND employee_id =
:linked`; an unlinked employee gets 403 `EMPLOYEE_LINK_REQUIRED`, a foreign or unknown id 404 `PAYSLIP_NOT_FOUND`
(same body). A VOIDED payslip stays listed with status VOIDED and `voidReasonCode`. Employees never receive
employer-wide totals, other employees, the approver subject, a void explanation or an administrator note. No
email, WhatsApp, webhook or push. Administrators list a package's payslips (`listPackagePayslips`, employee number
byte order) and download any (`downloadPackagePayslipPdf`).

## 14. Privacy and audit

Never in a URL, query, cursor, log, metric, audit metadata, outbox, idempotency receipt or error body: amounts,
employee-linked components, snapshot data, digests, void explanation, approver subject, filenames. **Audit actions**
(all disclosures fail closed: the read fails if its audit cannot be written): `payroll-package.read` (preview,
list, read; counts only), `payroll-package.approve`, `payroll-package.void`, `payslip.read`, `payslip.download`.
**Allow-list for audit metadata and outbox payload** (enforced at runtime like `StatutoryRecorder.ALLOWED`):
`tenantId`, `legalEntityId`, `packageId`, `payslipId` (payslip actions only), `version`, `period`, `currency`,
`status`, `payslipCount`, `runVersion`, `calculationVersion`, and **`samePreparer`** (approve only; D94C-2 requires
the audit to expose the fact). Nothing else: no reason code, subject, digest, amount, number, locale-dependent text,
filename or pack code. **Outbox**: `payroll.package.approved.v1` and `payroll.package.voided.v1`, internal, not
mapped to webhooks or WhatsApp (nondelivery test as `StatutoryEventsNotDeliveredTest`); no payslip event exists.
Idempotency receipts are the amount-free receipts of the API. Responses carry `Cache-Control: private, no-store`.
`access-log-policy.mjs` must keep treating `Content-Disposition` as a deleted response header; the admin and
employee paths contain only opaque UUIDs.

## 15. Errors

Core is language-neutral; EN/FR messages are in `packages/localization/locales/{en,fr}/common.json` under
`errors.*` and contain no amount, employee identity, digest or hidden source detail.

| Code | HTTP | Meaning |
| --- | --- | --- |
| `PAYROLL_PACKAGE_NOT_FOUND` | 404 | package unknown/malformed/foreign/unauthorized (indistinguishable) |
| `PAYSLIP_NOT_FOUND` | 404 | payslip unknown/malformed/foreign/other employee/not published (indistinguishable) |
| `PAYROLL_PACKAGE_BLOCKED` | 409 | a blocker exists |
| `PAYROLL_APPROVAL_PREVIEW_STALE` | 409 | expiry or drift of run, calculation, policy, profiles, pack or digest |
| `PAYROLL_APPROVAL_NO_CHANGE` | 409 | APPROVED package exists for exactly this run and calculation |
| `PAYROLL_PACKAGE_VERSION_CONFLICT` | 409 | chain moved / package version or digest differs |
| `PAYROLL_PACKAGE_ALREADY_APPROVED` | 409 | another APPROVED package for the entity, month, currency |
| `PAYROLL_PACKAGE_VOIDED` | 409 | the package is already VOIDED |
| `PAYROLL_VOID_PREVIEW_STALE` | 409 | void preview expired or package changed |
| `PAYROLL_VOID_NOT_ALLOWED` | 409 | the payment guard reports a payment |
| `PAYROLL_PACKAGE_LIMIT_REACHED` | 409 | 5,000 payslips or 25 package versions |
| `PAYSLIP_RENDER_FAILED` | 500 | PDF could not be rendered or exceeded 256 KiB or 5 s; body is the safe Problem with no params |

Also used: 400 `VALIDATION_FAILED` / `LOCALE_NOT_SUPPORTED` / `CURSOR_INVALID`, 401, 403 `ACCESS_DENIED`,
`MFA_REQUIRED`, `EMPLOYEE_LINK_REQUIRED`, 404 `LEGAL_ENTITY_NOT_FOUND`, `PAYROLL_RUN_NOT_FOUND`,
`STATUTORY_CALCULATION_NOT_FOUND`, 409 `IDEMPOTENCY_KEY_REUSED`, 429 `RATE_LIMITED`, 503
`EMPLOYMENT_CHANGE_TIMEOUT` (database/lock timeout, as 094A/094B). Idempotency outcome mapping: the nine 409 codes
are `STATE_CONFLICT`, the two 404 codes `NOT_FOUND`, `PAYSLIP_RENDER_FAILED` `FAILURE` (done in
`IdempotentOperation`).

## 16. API operations (`docs/API-SPEC.yaml`, tag `payroll`, lifecycle `planned` until the controllers land)

| operationId | Method and path | Role / assurance |
| --- | --- | --- |
| `previewPayrollApproval` | `POST /legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations/{calculationId}/approval/preview` | tenant-admin, mfa |
| `commitPayrollApproval` | `POST .../statutory-calculations/{calculationId}/approval` (201, Idempotency-Key) | tenant-admin, mfa |
| `listPayrollPackages` | `GET /legal-entities/{legalEntityId}/payroll-packages` (`status`, `cursor`, `limit`) | tenant-admin, mfa |
| `getPayrollPackage` | `GET /legal-entities/{legalEntityId}/payroll-packages/{packageId}` | tenant-admin, mfa |
| `previewPayrollVoid` | `POST .../payroll-packages/{packageId}/void/preview` | tenant-admin, mfa |
| `commitPayrollVoid` | `POST .../payroll-packages/{packageId}/void` (201, Idempotency-Key) | tenant-admin, mfa |
| `listPackagePayslips` | `GET .../payroll-packages/{packageId}/payslips` (`cursor`, `limit`) | tenant-admin, mfa |
| `getPackagePayslip` | `GET .../payroll-packages/{packageId}/payslips/{payslipId}` (`locale`) | tenant-admin, mfa |
| `downloadPackagePayslipPdf` | `GET .../payroll-packages/{packageId}/payslips/{payslipId}.pdf` (`locale`) | tenant-admin, mfa |
| `listMyPayslips` | `GET /me/payslips` (`cursor`, `limit`) | employee |
| `getMyPayslip` | `GET /me/payslips/{payslipId}` (`locale`) | employee |
| `downloadMyPayslipPdf` | `GET /me/payslips/{payslipId}.pdf` (`locale`) | employee |

Optimistic concurrency follows 094B: `expectedVersion`/`packageVersion` and digests in the body (no ETag).
Pagination is opaque signed keyset (`Cursor`, `TaskLimit`/`PayrollLineLimit`). No amount in any path, query or
cursor. The web worker implements D94C-13 against these schemas; the `synthetic` flag drives the test badge.

## 17. Synthetic pack boundary (D94C-15)

A rule pack whose `code` starts with `SYNTHETIC-TEST` is a test/synthetic pack: packages expose `synthetic: true`
(generated column), the preview carries the warning `SYNTHETIC_RULE_PACK`, the payslip wording carries the
synthetic notice, and the UI shows a badge. The real pack `CD-SALARY-PREVIEW` stays DRAFT; no migration, seed,
profile or default activates a synthetic pack. hr-dev acceptance creates and activates the pack with the same
operator SQL helper as 094B (`apps/web/e2e/synthetic-verified-rule-pack.sql`, variables `pack_id`, `pack_code`) as
the database owner and records insertion/activation and cleanup (or preservation within synthetic hr-dev data) per
`docs/OPS-HR-DEV.md`.

## 18. Limits, rate limits, timeouts, metrics (D94C-16)

At most 5,000 payslips per package; 25 package versions per entity/month/currency. Rate limits (subject per
minute / tenant per hour; `application.yaml`, environment `DIVALHR_PAYROLL_APPROVAL_*`, `DIVALHR_PAYSLIP_*`):

| Bucket | Operations | Subject / minute | Tenant / hour |
| --- | --- | --- | --- |
| `payroll-approval-preview` | `previewPayrollApproval`, `previewPayrollVoid` | 5 | 60 |
| `payroll-approval-write` | `commitPayrollApproval`, `commitPayrollVoid` | 2 | 20 |
| `payslip-download` | `downloadPackagePayslipPdf`, `downloadMyPayslipPdf` | 30 | 300 |
| `payroll-package-read` | `listPayrollPackages`, `getPayrollPackage`, `listPackagePayslips`, `getPackagePayslip`, `listMyPayslips`, `getMyPayslip` | 60 | none |

Timeouts (explicit, all roll back on expiry and surface as 503 `EMPLOYMENT_CHANGE_TIMEOUT`, as 094A/094B):
`SET LOCAL lock_timeout = 5s`; `statement_timeout` 10 s for previews and reads, 20 s for approval/void commits;
request timeout 30 s; PDF render timeout 5 s and size cap 256 KiB (500 `PAYSLIP_RENDER_FAILED`). No background
job. Safe metrics: `operation`, `outcome`, package `status`, line-count bucket (1, 2-10, 11-100, 101-1000,
1001-5000), `locale`, render duration bucket and size bucket; never an employee, amount or identifier.

## 19. Truth refresh (D94C-14, informational)

The real pack remains DRAFT, so the landing may describe approval and bilingual payslips as a capability that needs
a VERIFIED pack ("pilot / readiness"), must not claim operational DRC statutory payslips, and keeps filing,
accounting posting, disbursement and advances under "coming next".

## 20. Validation performed (honest scope)

PostgreSQL 16.15 (the project targets a later major version; V31+ use syntax PostgreSQL 16 rejects, so the
chain V1-V42 could not be applied): a **baseline of stubs** for `tenant.organization`, `tenant.legal_entity` and
`people.employee` plus the real V43, V44, V45, then V46. Exercised and passing: apply; the full happy path
(synthetic VERIFIED pack, two lines one excluded, package, sources, payslips) with the database-recomputed approval
digest equal to an independent Python computation of section 4.3; about two dozen refused approvals (wrong digest, totals,
version, run digest, same-preparer both ways, missing payslips/sources, nine snapshot tampering cases, line amounts,
foreign tenant, RETIRED pack, CHECK constraints under `session_replication_role = replica`); source locks on run and
calculation; second approved package (23505); append-only on payslip/package/source/void; void evidence rules (seven
cases); VOIDED -> APPROVED refused; lock released after void; voided pair re-approval refused; two-session races
(approval vs supersession both directions); rollback refusal with rows, rollback success on an empty V46 with a
schema-only dump diff identical to V45, and re-application. **Not validated here**: the Java code (nothing was built),
Flyway placeholder parsing (the file contains no `${`), PostgreSQL 17, the real V1-V42 chain, query plans on volume.

## 21. Open questions and limitations

1. **No employee name.** The snapshot and the 094A/094B line schemas carry the employee number only (D94C-6 lists
   no name). A payslip identifying the employee by number alone may be insufficient for real use; adding a name
   needs a decision and a cross-module read.
2. **`SOURCE_CHANGED` costs a recomputation.** Detecting source drift requires a read-only 094A recomputation of up
   to 5,000 lines inside the preview and commit (bounded by the statement timeout).
3. **Void without a source change is a dead end.** 094A refuses an identical recalculation (`PAYROLL_RUN_NO_CHANGE`)
   and a voided `(run, calculation)` pair can never be approved again, so a void for `ADMINISTRATIVE_ERROR` cannot be
   re-approved until something in the sources changes. A product decision (for example a "recreate run" step) is
   needed.
4. The preparer fact covers only the creators of the run and the calculation; no separation of duties is enforced.
5. The approver subject is stored (restricted) and never returned; a database owner can read it.
6. Characters of an employee number outside WinAnsi print as `?` in the PDF (the HTML is exact).
7. Canonical key order and number/escape format are produced and verified by the application and its tests; the
   database guarantees the field set, every value, the sums, the components and the digest, not the byte layout.
8. `snapshot` (jsonb) normalisation depends on the PostgreSQL major version; the digest does not (it hashes the text).
9. The PDF is deterministic for one pinned PDFBox version; a library upgrade may change bytes (not content).
10. Voids share the approval rate bucket; `approvedAt` is not checked against the database clock, only against the
    calculation's creation time.
11. Real DRC statutory payslips remain out of reach until a professionally verified pack exists.
