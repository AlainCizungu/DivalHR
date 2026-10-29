# ADR 0032 Monthly base compensation: effective-dated, append-only, restricted

## Status

Decisions D93-1 to D93-16 approved by the architect on Issue #175 and implemented, as written, by the
MVP-093A pull request; pending architecture review of the implementation. Builds on ADR 0006
(idempotent commands), ADR 0007 (employment history, preview digests and transaction-time timelines),
ADR 0008 (global lock order), ADR 0009 (people ports), ADR 0023 (payroll preparation), ADR 0030
(restricted text, append-only evidence) and ADR 0031 (expenses, money representation).

## Context

DivalHR records who works where and when (MVP-020/021), their time (MVP-060 to MVP-062) and a
payroll-input preparation (MVP-090A to MVP-092). It has never recorded what an employee is paid. This
increment records one compensation fact and nothing else: a MONTHLY base amount in CDF or USD, effective
from a date, with its whole history. It deliberately calculates nothing. Compensation is Restricted
financial and HR data, so most of the design is about where the amount may and may not travel.

## Decision

- **Meaning (D93-1).** Frequency is exactly `MONTHLY`; one base amount; currency `CDF` or `USD`; no
  conversion. A monthly salary is not computed from worked minutes: attendance, schedules and timesheets
  stay payroll-input evidence and never scale the amount. No gross, proration, overtime, leave valuation,
  deduction, tax, employer contribution, net pay, payslip or payment exists in this story.
- **Authority (D93-2).** Tenant administrator with exact MFA and active membership (`TenantAdminOperation`)
  manages and reads any employee's compensation of the tenant. A linked active employee reads only their own
  (`EmployeeSelfOperation`, `/me/compensation`, link resolved on every request). Managers, substitutes,
  ordinary employees and platform administrators have no operation. Unknown, malformed, foreign and
  unauthorized employee or change ids are one safe `404 COMPENSATION_NOT_FOUND`.
- **Classification (D93-3).** Restricted financial and HR data. The amount, an employee number, name, e-mail
  or telephone, the free-text explanation and before/after values never enter logs, metrics, audit metadata,
  outbox payloads, idempotency receipts, cursors, URLs or error bodies. Audit and event metadata carry only
  stable ids, currency, frequency, effective dates, the action type, the version, whether a reason was
  supplied and bounded counts. All responses are `Cache-Control: private, no-store`; the web client keeps
  nothing in browser storage.
- **Money (D93-4).** Wire amount is a decimal string. Storage is currency, ISO exponent and signed 64-bit
  integer minor units. CDF: exponent 0, 1 to 1,000,000,000 francs, no fraction. USD: exponent 2, 0.01 to
  100,000.00; one or two fraction digits are accepted and canonicalized to two. No sign, exponent notation,
  grouping separator, negative value, excess scale or rounding. Responses always show the canonical text with
  the currency code. Currencies are never added, compared as one total or converted.
- **Dates (D93-5).** Intervals belong to one tenant and employee, start inside a qualifying employment
  interval, never overlap, and the active timeline is gap-free after the first fact (gaps only before it);
  only the last interval is open-ended. The organization's business time zone defines today. Every command's
  effective date is within `[today - 60 days, today + 366 days]` (exact boundaries accepted, one day beyond
  refused); a date before today is *retroactive* and needs `acknowledgeRetroactive`. A correction's *reach*
  (the earlier of the old and new start of the fact it changes) is held to the same 60-day bound. A separation
  or employment-history change never rewrites compensation: a separation whose last day precedes the start of
  an active compensation interval fails with `409 COMPENSATION_EMPLOYMENT_CONFLICT` (through a platform port
  implemented by payroll) and requires an explicit compensation correction first.
- **Evidence (D93-6, V43).** `payroll.compensation_change` is append-only command evidence with one
  increasing sequence per employee (the sequence is the *compensation version*; 0 when none).
  `payroll.compensation` is the transaction-time timeline of facts: a fact is *active* while
  `superseded_seq IS NULL`; the only legal update marks an active fact superseded by the latest change; a
  correction or cancellation appends fresh facts (a shortened or restored copy keeps its
  `origin_change_id`). Database constraints and triggers enforce tenant/employee binding, currency/exponent/
  amount bounds, frequency `MONTHLY`, non-overlap (exclusion constraint on active facts), increasing
  sequence, the legal action shape, the actor shape, a gap-free timeline that equals the latest committed
  change (deferred exact check), no update/delete/truncate of change evidence and no hard deletion. At most
  100 changes per employee in the database. Rollback (`db/rollback/V43__rollback.sql`, manual) refuses while any
  row exists: no override, no retention decision.
- **Actions (D93-7).** `ESTABLISHED` (first fact; only one per employee), `CHANGED` (appends a new fact
  starting strictly after the start of the last fact and closes the last fact on the prior day),
  `CORRECTED` (replaces one active fact, addressed by its `originChangeId`: amount, currency and/or start;
  needs a closed reason code, a 10-500 character restricted explanation and the acknowledgement; the
  preceding fact's end is adjusted, the following facts are untouched) and `SCHEDULED_CHANGE_CANCELLED` (only
  an unstarted `CHANGED` fact: its start is after today; the preceding fact is restored through the cancelled
  fact's end). Identical values record nothing. Reason codes: `DATA_ENTRY_ERROR`, `WRONG_EFFECTIVE_DATE`,
  `WRONG_CURRENCY`, `OTHER_CORRECTION`. The explanation is returned only to administrator reads; the
  employee sees the corrected history and the reason code.
- **Preview and confirmation (D93-8).** Every mutation is previewed first (stateless, like ADR 0007's
  previews): the preview returns the action, canonical money, the active intervals before and after, warnings,
  stable blockers, the compensation version, an employment digest (all the employee's employments: id,
  version, start, end), a preview digest and its expiry (15 minutes). The commit carries the command, the
  version, the employment digest, the preview digest, the expiry, the acknowledgement, `confirmed=true` and an
  `Idempotency-Key`; it re-evaluates under the lock order. A stale preview, an expired preview or employment
  drift is `409 COMPENSATION_PREVIEW_STALE`; a different version `409 COMPENSATION_VERSION_CONFLICT`. Concurrent
  commands produce one legal timeline. An identical replay returns the original response without another row,
  audit or event; another payload under the key is `IDEMPOTENCY_KEY_REUSED`. The stored response is a
  receipt without an amount or text.
- **API (D93-9).** Contract first (`docs/API-SPEC.yaml`): `getEmployeeCompensation`,
  `previewEmployeeCompensation`, `commitEmployeeCompensation`, `previewEmployeeCompensationCancellation`,
  `cancelEmployeeCompensationChange`, `getMyCompensation`; eight `COMPENSATION_*` problem codes; bilingual
  client messages; a bounded keyset history (default 25, maximum 100) with a signed cursor bound to the
  operation, tenant, subject, employee, role and page size.
- **Limits (D93-10).** At most 100 changes and 10 scheduled (unstarted) changes per employee
  (`409 COMPENSATION_LIMIT_REACHED`); previews 60 per subject and 600 per tenant per hour; commits and
  cancellations 30 per subject and 300 per tenant per hour; reads use a bounded subject bucket; explicit
  request, statement and transaction timeouts; no user-controlled or financial metric label.
- **Audit and events (D93-11).** Disclosures `compensation.read` (administrator and employee reads, previews)
  and `compensation.history-read` (cursor pages). Commands `compensation.establish`, `compensation.change`,
  `compensation.correct`, `compensation.scheduled-change-cancel`. Internal outbox events
  `payroll.compensation.established.v1`, `.changed.v1`, `.corrected.v1`, `.scheduled-change-cancelled.v1`;
  not delivered to webhooks or WhatsApp. No payload carries an amount, employee number, name, contact detail
  or explanation.
- **Lock order (extends ADR 0008).** Idempotency (0); employment rows `FOR SHARE` through the people port
  (2); the employee's compensation advisory lock `payroll.compensation:{tenant}:{employee}` (5d); deferred
  checks, audit and outbox (6). A separation takes the employment `FOR UPDATE` at step 2 and serializes with a
  compensation command; no path takes an earlier step after a later one.
- **UI (D93-12) and accessibility/localization (D93-16).** Administrator « Rémunération » / "Compensation"
  section on the employee record (current value with currency and effective date, immutable history,
  establish/change/correct/cancel-scheduled actions with a preview before confirmation, a privacy notice and
  the fixed notice "Recording compensation does not calculate payroll or initiate payment."); employee
  `/me/compensation` (own current value and history, currency always visible, no editing, a fixed notice that
  worked minutes do not automatically change monthly salary and that no payment is initiated). No manager
  page. Complete English and French; CDF and USD formatted with the locale and the currency code.
- **Truthfulness refresh (D93-13 to D93-15).** The backlog, the public landing page (three states: available
  now, capabilities available, coming next) and the role homes are reconciled with Git and GitHub evidence;
  no payroll calculation, payslip, payment, loan or unsupported integration is claimed.

## Consequences

- A correction never erases what was recorded: the change log and every superseded fact stay, so an auditor
  can reconstruct any date's compensation as it was known at any change sequence.
- Payroll calculation, proration, statutory rules and payslips remain separate, later decisions; none can
  start without a design that decides how this monthly fact feeds them.
- A pilot limit (60 days of retroactive reach) means older errors need an architect-approved procedure.
- Production use needs the existing encryption-at-rest gate; amounts live only in PostgreSQL.
