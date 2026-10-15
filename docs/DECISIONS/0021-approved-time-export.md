# ADR 0021 Approved-time export: an immutable snapshot of current approvals in a fixed CSV

## Status

Proposed with the MVP-070E pull request (Issue #134, architect specification T70E-1 to T70E-12);
pending architecture review. Builds on ADR 0018 (weekly timesheets), ADR 0019 (submission and
approval) and ADR 0020 (reopening approved time).

## Context

Payroll and accounting need the approved weekly time of a site in a file they can load and later
prove. The file must contain only time that is approved now: never a superseded approval, a
reopened timesheet, a draft, a returned cycle or submitted time. It must be reproducible byte for
byte, so that what was handed over can be verified, and it must never be rewritten by a later
correction or reopening. DivalHR does not calculate pay here.

## Decision

### Eligibility (T70E-1)

A timesheet of the scope's generated week is exported when its current state is `APPROVED` and its
current action is that `APPROVED` action, at the timesheet's current revision and cycle; its READY
rows are the exported rows. Every other timesheet is excluded and counted by state (DRAFT,
including returned and refreshed-after-reopening; SUBMITTED; REOPENED). The scope is one legal
entity, one of its sites (the site's legal entity now) and one generated site-local week with the
time zone and exact UTC boundaries recorded by its generation.

### Preview and creation (T70E-2, T70E-3)

- The preview returns counts, exclusions, the generation version, the export of identical evidence
  if any, a preview digest and its expiry. The digest binds the tenant, the evidence, the generation
  version and the expiry; the expiry is the end of the next 15-minute window (15 to 30 minutes after
  the preview), so the creation needs only the digest: the server recomputes it for the current
  and the next window. A refresh, an approval, a reopening or a correction in between, or an
  expired preview, makes it `409 TIMESHEET_EXPORT_PREVIEW_STALE`; an empty week is
  `409 TIMESHEET_EXPORT_EMPTY`.
- The creation takes the tenant's scheduling lock (taken first by every timesheet command and by
  attendance corrections, so nothing that could change eligibility interleaves), evaluates again,
  compares the digest and writes, in one transaction, the header, the rows, the audit record and
  the outbox event. Idempotent (`Idempotency-Key`).
- **Evidence and reuse.** The evidence digest covers the scope and every line (timesheet, revision,
  approval action and cycle, employee and employee number, employment, cost center, work date,
  shift, minutes), not the generation version. One export exists per site, week and evidence
  (`timesheet_export_evidence_unique`): an identical request returns the existing export (outcome
  `EXISTING`); a changed approval set or changed evidence creates a new one. A refresh that changes
  no approval therefore reuses the export.

### Snapshot (T70E-3, T70E-8, V35)

- `people.timesheet_export`: append-only, `READY` only, schema version 1, the scope, the generation
  and its version (equal to the generation's current version at insert), counts, total minutes,
  evidence and CSV SHA-256, the creation instant and the verified creator.
- `people.timesheet_export_row`: append-only, one row per exported READY row, numbered in CSV order;
  composite keys to the exact `timesheet_row` of the revision and to the timesheet's action and
  cycle; at insert, a trigger requires that the timesheet is APPROVED now by exactly that action,
  revision and cycle and that the row's employee, employment, cost center, minutes, site-local date
  and the employee's number match. A deferred check proves at commit that the header's counts and
  total are its rows', that every exported timesheet's revision is exported whole and that lines
  are 1..n in CSV order.
- The database-level site and legal-entity keys and the insert check that the site belongs to the
  legal entity are the documented integrity exception (as ADR 0014 and ADR 0018); the application
  reads units only through `OrganizationPlacementDirectory`.
- `db/rollback/V35__rollback.sql` restores V34 exactly only while no export exists; no override.

### Format (T70E-4, T70E-5)

- **CSV.** UTF-8 without a byte-order mark (it is for machines), comma-delimited, every cell
  quoted (RFC 4180) and neutralized against formulas by the platform's `CsvCells` (a leading `=`,
  `+`, `-`, `@`, tab or carriage return gets an apostrophe), CRLF line ends. The fixed English
  header `schema_version, export_id, legal_entity_id, site_id, site_timezone, week_start,
  week_end, employee_id, employee_number, employment_id, cost_center_id, work_date, shift_id,
  timesheet_id, timesheet_revision, approval_action_id, approval_cycle, worked_minutes`, whatever
  the interface language. `week_end` is the next Monday (exclusive), as in every DivalHR timesheet
  contract; the manifest also gives the last local day and the exact instants. Order: employee
  number, work date, shift ID (the shift ID compared as its lowercase text, the database's byte
  order). No name, address, label, explanation, reason, subject, attendance instant, rate, amount,
  currency or payment data.
- **Manifest.** Canonical JSON (fixed key order): schema and export identifiers, status `READY`,
  format and columns, scope with the exact week boundaries, generation and version, counts, total
  worked minutes, CSV SHA-256, creation instant. Never the creator.
- **Downloads.** Both are rendered from the stored export, so every download has the same bytes;
  the CSV's SHA-256 must equal the stored digest (otherwise `500`, nothing returned). Attachment,
  `private, no-store`, safe names `divalhr-time-export-<week>-<export>.csv` and
  `.manifest.json`.

### Authority, audit and privacy (T70E-7, T70E-10)

- Tenant administrators with exact MFA only (`timesheet-export.preview`, `.create`, `.list`,
  `.read`, `.download`); employees, managers and platform administrators have no access; foreign,
  malformed and unknown identifiers are the safe `404 TIMESHEET_TARGET_NOT_FOUND`.
- Fail-closed disclosure audits for preview, history and detail; the download audit commits before
  any byte is returned. The creation audit carries bounded metadata (export schema version, week,
  generation version, counts, a minute band); identifiers, digests and the creator only inside the
  audit state digest. Event `people.timesheet-export.created.v1`: stable IDs, scope, schema
  version, counts, minute band, CSV digest, status and instant; never names, employee numbers,
  subjects or rows. Logs and metrics carry the operation, outcome and count bands only.

## Consequences

- Payroll and accounting receive only current approvals, as an immutable, verifiable snapshot; a
  later reopening or correction affects only future exports, and every export stays in the
  history.
- An export is never edited, replaced, voided or deleted in this story; retention and deletion
  policies, period locking (MVP-071) and any pay calculation are separate stories.
- Not included: pay rates, gross or net pay, overtime, statutory calculations, currencies,
  deductions, allowances, leave valuation, payment, journals, configurable mappings, XLSX, SFTP,
  scheduled delivery, webhooks, project, grant or task allocation, bulk backfill and AI.
