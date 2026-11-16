# ADR 0041: Consented salary-advance requests (MVP-120A, Issue #204)

Status: accepted for implementation (decisions D120A-1 to D120A-16 of Issue #204 are binding; this ADR records the design
points the issue leaves to the implementation). Builds on ADR 0003 (partner-led financial services), ADR 0035 (approval and
payslips) and ADR 0040 (governed statutory packs). Migration: **V52**. Base: `main` at
`0f9c960a65ddf72a1e45a61cc2ea75f25564897f`.

## 1. Boundary (read first)

DivalHR is **workflow software**: it records an employer participation policy, an employee's consent and a request to the
**employer**. It is not the lender, borrower, guarantor, credit bureau or payment provider. This story creates no loan,
makes no credit decision, sends nothing to any financial institution, moves no money and makes no payroll deduction. Lending,
underwriting, pricing, affordability, disbursement and deductions belong to MVP-120B/120C and to an authorized institution
after separate legal, privacy, commercial and regulatory review. The BCC and ARPTC links in the issue are architecture context,
not executable rules: no legal interpretation is encoded.

The permanent notice (stable code `DIVALHR_NOT_LENDER`, returned on every response and rendered in English and French) reads:
"DivalHR is not the lender. Employer eligibility is not credit approval. No funds are sent in this step." /
« DivalHR n’est pas le prêteur. L’admissibilité de l’employeur n’est pas une approbation de crédit. Aucun fonds n’est envoyé
à cette étape. » The words "approved loan", "guaranteed", "funded", "interest rate", "APR" and "credit score" appear nowhere
in the schema, the API, the translations or the UI (a guard test scans for them).

## 2. Contract adaptations (D120A-9)

The suggested custom-method paths (`:preview`, `:cancel`, `:withdraw`) are adapted to the established convention of this API
(`…/preview`, `…/cancel`, `…/{id}/withdraw`; Spring cannot bind a path variable followed by a colon suffix, and `docs/API-SPEC.yaml`
uses slash segments everywhere). Operation names, bodies, limits and errors follow the issue. The contract was added first, marked
`x-divalhr-lifecycle: planned`, and is `implemented` with the controllers. Amounts are `CompensationMoney` (`currency`,
canonical decimal text, never a JSON number); CDF has exponent 0 and USD exponent 2 as elsewhere.

## 3. Policy (D120A-3)

One chain per (tenant, legal entity, currency), `sequence` 1..25 (immutable version), non-overlapping periods enforced by a partial
exclusion constraint over non-cancelled versions, no edit and no delete. The only update the schema allows is the one-time
cancellation of a version that has not taken effect and that no request references (the database applies a UTC lower bound; the
service applies the exact tenant business date). Policy preview/commit follows the 15-minute digest, `expectedVersion`,
`confirmed` and idempotency pattern of ADR 0033: the digest binds tenant, legal entity, the full policy and the chain version.
Never enabled automatically (the administrator states `enabled`); no employee- or manager-specific exception exists.

**Ceiling arithmetic (decision).** `ceiling = min( floor(referenceNetMinor × maxPercent / 100), absoluteMaxMinor )` in `long`
arithmetic; `maxPercent` is a whole percent 1..50, so no float, no currency conversion and no rounding other than the documented
round-down. The database recomputes it and refuses a different stored ceiling.

## 4. Reference payroll amount (D120A-4) and operational packs (R202-1)

The reference is the employee's own `payroll_payslip.net_minor` of the latest APPROVED, non-voided `payroll_package` for the
employee, legal entity and currency (by `period_start`), never recalculated and never converted. **Decision for architect
confirmation:** a package establishes eligibility only when the governance state of its pack is `ACTIVE` or `RETIRED` (it was
approved while the governed pack was operational); `LEGACY` and `DRAFT` packs never establish eligibility (R202-1). If the
architect reads "operational" as `ACTIVE` only, change the single predicate in V52 (`g.state IN ('ACTIVE','RETIRED')`) and the
matching query. Approved packages are CDF-only today (`payroll_package_currency`), so a USD entry is
`NOT_ELIGIBLE (PAYROLL_REFERENCE_MISSING)` and no working USD employee journey is claimed.

## 5. Eligibility (D120A-5)

Deterministic and closed. Reason codes: `NO_ENABLED_POLICY`, `EMPLOYMENT_NOT_ACTIVE`, `LEGAL_ENTITY_MISMATCH`, `TENURE_TOO_SHORT`,
`PAYROLL_REFERENCE_MISSING`, `CURRENCY_MISMATCH`, `OPEN_LIMIT_REACHED`, `WAITING_PERIOD`, `AMOUNT_OUT_OF_RANGE`,
`ROLLING_LIMIT_REACHED`. Tenure is completed calendar months from the covering employment's start to the request date
(PostgreSQL `age()` semantics, the same expression as the service). The waiting period starts on the business date of the
employee's own WITHDRAWN or EMPLOYER_REFUSED event for the same legal entity and currency and lasts the policy's `waitingDays`.
"Open" means SUBMITTED or EMPLOYER_CONFIRMED. The response shows the reference month, currency and the employer-policy ceiling,
never the salary and never the words "credit limit".

## 6. Consent (D120A-6)

The disclosure is a canonical, versioned document made of **stable codes** (purpose, shared categories, categories explicitly not
shared, `externalSharing = NONE_IN_THIS_STEP`, withdrawal before external handoff, `NO_DELETION_SCHEDULE_YET`), translated by the
client into plain English and French. `contentSha256` is the SHA-256 of its canonical JSON (sorted keys, no whitespace) and
does not depend on the language; the language shown is recorded separately. The server holds the canonical document per consent
version, recomputes the digest at submission, and refuses a version that is not the policy's current one
(`SALARY_ADVANCE_CONSENT_VERSION_STALE`) or an unaffirmed control (`SALARY_ADVANCE_CONSENT_REQUIRED`). The stored evidence is
version, language, instant and digest only: no IP address, user agent or rendered text. The checkbox is never pre-checked.

## 7. Lifecycle and employer review (D120A-7, D120A-8)

`SUBMITTED → EMPLOYER_CONFIRMED | EMPLOYER_REFUSED | WITHDRAWN`, and `EMPLOYER_CONFIRMED → WITHDRAWN` (no partner handoff exists).
Header, history and decision are enforced by database triggers and a deferred completeness check, so a free status edit, a
reopening, a second decision, a confirmation without its recorded decision or an employee-signed employer outcome all fail at
commit. Exactly one command wins under concurrency: every command locks the request row first, then re-reads. A request is
`row_version` 1..3. The internal note (≤ 500 characters) is stored in the decision row, shown to administrators only, and is
never in an employee response, audit, outbox, log or partner-facing field. No rule prevents an administrator who is also the
requesting employee from deciding their own request; the issue does not ask for one, and none was added (open question for the
architect).

## 8. Authorization, privacy and audit (D120A-12, D120A-13)

Policy and employer review: tenant administrator, exact MFA, active membership (`@TenantAdminOperation`). Employee routes:
`@EmployeeSelfOperation`, resolved from the caller's active employee-access link on every request; managers and platform
administrators have no route. Unknown, foreign and wrong-owner ids give the same 404. Amounts, reference, ceiling, employment
date, consent detail and notes never enter URLs, cursors, audit metadata, outbox payloads, logs, metrics or error bodies; audit
and outbox carry stable ids, policy sequence, status, currency and the bounded reason code only. Cursors bind operation, tenant,
subject, role, filter and page size. Audit actions and internal events are those of D120A-13 and are not added to public
webhooks or WhatsApp.

## 9. Persistence (V52)

Tables: `salary_advance_policy`, `salary_advance_request`, `salary_advance_consent`, `salary_advance_employer_decision`,
`salary_advance_request_event`. Functions and triggers: see the header of `V52__salary_advance_requests.sql`. Rollback
(`db/rollback/V52__rollback.sql`) refuses while any row exists. Amounts are plain `bigint` minor units, access-restricted to the
payroll module like `payroll_payslip` (no separate column encryption exists in this module for amounts). The database proves the
frozen facts (policy applies on the date, payslip belongs to the employee, package approved and not voided, pack not DRAFT or
LEGACY, no later approved payslip, employment active and placed in the legal entity, tenure, ceiling, bounds, open limit, rolling
limit of 10 per 12 months, waiting period, submitting subject is the employee's own linked membership). Not enforced by the
database but by the service: rate limits, idempotency, the 15-minute preview binding, the exact tenant business date for
cancellation, and the 3 open-requests ceiling being "further bounded by policy".

## 10. Limits (shared limiter infrastructure)

Eligibility reads 30/subject/hour; employee previews/submissions 10/subject and 100/tenant per hour; employer decisions
20/subject and 200/tenant per hour; list reads 60/subject/hour; policy previews/commits/cancellations 20/subject/hour; request
body 32 KiB; list default 25, maximum 100; explicit request, statement and transaction timeouts as in the payroll module. The
shared limiter is per Core API instance; the issue's "disclose any remaining per-instance limitation" applies and is repeated in
the completion report.

## 11. Evidence map (updated as the story lands)

| Requirement                                                            | Evidence                                                                                                                                                                                                                                                          |
| ---------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D120A-11 tenant, immutability, transitions, no handoff state, rollback | `V52__salary_advance_requests.sql`, `V52__rollback.sql`; PostgreSQL 16 guard harness (75 checks, see the completion report); `TenantPredicateArchitectureTest`, `AccessPortArchitectureTest` (people tables read only through the `SalaryAdvanceEmployment` port) |
| D120A-9 contract first                                                 | `docs/API-SPEC.yaml` (14 operations, lifecycle `implemented`; `ApiContractDriftTest`)                                                                                                                                                                             |
| D120A-1/15 truthfulness                                                | `SalaryAdvanceTruthGuardTest`, `SalaryAdvanceErrorCodesTest`, the web page tests and the landing test                                                                                                                                                             |
| D120A-16 accessibility and localisation                                | locale parity check, axe in the page tests, axe and reflow in `e2e/salary-advance.spec.ts` (two journeys)                                                                                                                                                         |
