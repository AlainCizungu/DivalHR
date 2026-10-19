# ADR 0025 Payroll-input versions, final lock and worked-time export

## Status

Proposed with the MVP-092 pull request (Issue #149); pending architecture review. The last
increment of one combined payroll release (MVP-090A, MVP-091, MVP-092). Builds on ADR 0023 (payroll
inputs) and ADR 0024 (variance review). Nothing is deployed or verified on AWS before approval.

## Context

A payroll officer needs to correct worked-time inputs after upstream evidence changes, to freeze the
final version, and to hand a deterministic file to payroll preparation. V37 evidence is immutable and
must stay so. The company pays a monthly salary: worked minutes are evidence for preparation, never a
pay, overtime, deduction or payment figure.

## Decision

- **Additive migration V38, V37 untouched.** `input_run_supersession`, `input_run_lock`,
  `input_export` and `input_export_row` reference V37 keys only and use V37's append-only guards
  (`payroll.input_immutable`, `payroll.input_no_truncate`). No column holds a rate, a salary, an
  amount or a note.
- **Versions.** Version N+1 of the same tenant, legal entity and period is a new V37 run plus a
  supersession of exactly version N with a reason code (`UPSTREAM_CORRECTION`,
  `UPSTREAM_REAPPROVAL`, `SCOPE_CORRECTION`, `OTHER`). `OTHER` alone carries a bounded plain-text
  explanation (1 to 200 characters; no markup, link, address or long number), enforced in the domain
  and by a database check; it is shown to tenant administrators in the version chain only and never
  in audit, events or logs. A deferred constraint proves a gap-free chain at commit; a scope is
  serialized by a transaction advisory lock, after the tenant scheduling lock every command takes
  first. Nothing is deleted, edited, invalidated or hidden. Generation of identical upstream evidence
  returns the latest run; different evidence on an existing scope is the supersession flow.
- **Lock.** OPEN, then an append-only LOCKED action. Only the latest version can be locked; a locked
  run is never superseded and never unlocked in this MVP (there is no unlock action). Lock, audit and
  outbox commit in one transaction (the platform idempotent operation). Blockers are stable codes:
  `NOT_LATEST_VERSION`, `ALREADY_LOCKED`, `UPSTREAM_EVIDENCE_CHANGED`, `SOURCE_PERIOD_NOT_LOCKED`,
  `NO_EXPORTABLE_ROWS`. Preview digests bind the purpose, run, state, upstream evidence, sources,
  blockers and a 15 to 30 minute expiry.
- **Export.** One immutable logical export per locked run (a unique key), whose rows reproduce all
  and only the run's source rows in a deterministic order, counts and minutes reconciled at commit.
  CSV: UTF-8, CRLF, every cell quoted and formula-neutralized, 18 identifier, date and minute
  columns, no name, no email, no amount. The canonical JSON manifest carries the SHA-256 of the exact
  CSV bytes, both fixed notices and the lock and run versions. A download verifies the stored hash
  before serving. File names derive only from the legal entity, the period dates and the version.
  Explicit limits: 50,000 rows (`409 PAYROLL_INPUT_EXPORT_TOO_LARGE`); `Cache-Control: private,
  no-store`.
- **Monthly-salary boundary.** The fixed bilingual notice appears on every preview, lock, result,
  history and download area, in the contract descriptions and in the manifest. The artifact is always
  called "worked-time inputs for payroll preparation", never a payroll, salary, payment or bank file.
- **Authority and limits.** Tenant administrator with exact MFA, tenant from the verified JWT. Writes
  share `payroll-input-write` (subject and tenant); reads and downloads share `payroll-input-read`.
  No bucket is added and no limit weakened.
- **Contract first.** `API-SPEC.yaml` gains ten operations; the TypeScript client is regenerated;
  `PayrollInputRun` gains a required `lifecycle` object.
- **Rollback.** `db/rollback/V38__rollback.sql` refuses while any supersession, lock, export, export
  row or run of version 2 or more exists; there is no override. Otherwise it removes only V38 objects
  and V37's signature is restored (catalog fingerprint test).

## Consequences

- A corrected period is a visible chain of immutable versions; the previous evidence is always
  readable.
- A locked version is final. An error found later needs a product decision (unlock, void, or a
  corrective period); this MVP offers none of them.
- Compensation, pay calculation, payslips, payment files, unlocking, voiding exports, notifications
  and AI are out of scope.
