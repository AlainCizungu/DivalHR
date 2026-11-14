# ADR 0039: Immutable, balanced accounting-journal export from an approved payroll package (MVP-096, Issue #194)

Status: accepted for implementation (decisions D96-1 to D96-10 of Issue #194 are binding; this ADR records the design
points the issue leaves to the implementation). Builds on ADR 0034 (statutory preview), ADR 0035 (approval and
payslips) and ADR 0036 (payment batches). Migration: **V50**. Base: `main` at
`ce871f48e7be14d824a0800bc7a03be05b72d2ff`.

## 1. Meaning and boundary

An **accounting journal** is an immutable, balanced list of debit and credit lines derived once from the frozen evidence
of one APPROVED payroll package and a tenant-configured account mapping, plus two deterministic download files (a CSV
and a JSON manifest) that a tenant administrator imports into an accounting system by hand.

It is an **accounting-ready export**. It is not posting to accounting software, not settlement, not a bank, cash or tax
entry and not proof that any employee was paid. The fixed notice appears on every relevant page and download surface:

- EN: "Accounting export only — not posted and not proof of payment."
- FR: « Export comptable uniquement — ni comptabilisé ni preuve de paiement. »

There is no endpoint, worker or file that contacts an accounting system, a bank, a mobile-money operator or any
provider. No new secret, egress or provider is added.

## 2. Source of truth (D96-1)

The only inputs are (a) the row of `payroll.payroll_package` and (b) the immutable `payroll.payroll_payslip` rows of that
package (per employee: `gross_minor`, `employee_deductions_minor`, `employer_contributions_minor`, `net_minor`), both
frozen at approval (ADR 0035), and (c) the applicable mapping version (section 3). Nothing else is read: no mutable
compensation, attendance, timesheet, employment, placement, statutory-profile or rule-pack row, and no employee identity
(the payslip query selects only the four amount columns). Nothing is recalculated and no currency is converted.

- The package must be `APPROVED` with `voided_at IS NULL`. A `VOIDED` package gives 409
  `ACCOUNTING_JOURNAL_SOURCE_VOIDED`; any other status 409 `ACCOUNTING_JOURNAL_SOURCE_NOT_APPROVED` (defensive: the
  package table allows only the two statuses today).
- A PREPARED or submitted payment batch does not block the journal; accounting export and payment processing are
  independent evidence streams. This story never touches payroll, payslips, payment batches, submissions or
  reconciliations (no write to any of their tables, proved by row-for-row comparison in the integration tests).
- The sum of the four payslip amount columns must equal the package header totals (`gross_total_minor`,
  `employee_deductions_total_minor`, `employer_contributions_total_minor`, `net_total_minor`); a mismatch is an
  integrity failure and is refused as `ACCOUNTING_JOURNAL_UNBALANCED` before any write.

Repository facts the implementation respects and the report discloses: the package table currently allows only the
currency `CDF` (runs allow `USD`); and the frozen payroll lines carry **no cost center and no site** (the pay-run line
has neither). The journal therefore always has empty `cost_center_code` and `site_code` today; the columns, the
aggregation key and the CSV columns exist as D96-3 requires and are populated only when a frozen line contains one.
They are never read from mutable employment or placement data.

## 3. Minimal mapping (D96-2)

One **mapping version** per legal entity and currency holds five required account codes: `salaryExpense`,
`employeeDeductionLiability`, `employerContributionExpense`, `employerContributionLiability`, `netSalaryPayable`.

- Each code is trimmed, then must match `^[A-Z0-9][A-Z0-9._-]{0,39}$` (1 to 40 characters; uppercase letters, digits,
  hyphen, underscore and period). **Refinement of D96-2, disclosed:** the first character must be a letter or digit, so
  that no code can start with `-` and be read as a formula by a spreadsheet that opens the CSV. Everything the issue
  allows in the other positions is allowed. Stored exactly as entered after trimming; no names, descriptions or free
  text.
- Versions are append-only: `version` 1 to 10 per (tenant, legal entity, currency) (maximum 10, then 409
  `ACCOUNTING_MAPPING_LIMIT_REACHED`), `effective_from` is a date and must be **strictly later** than the latest existing
  version's `effective_from` (otherwise 409 `ACCOUNTING_MAPPING_OVERLAP`). A version is derived `SCHEDULED`
  (`effective_from` after the business date), `ACTIVE` (the latest with `effective_from` on or before the business
  date) or `SUPERSEDED`. Never edited or deleted in place (database guard).
- The mapping that applies to a package is the version of the package currency with the greatest `effective_from` on or
  before the package `period_start` (the first day of the payroll month). None: preview blocker `MAPPING_NOT_FOUND`,
  commit 409 `ACCOUNTING_MAPPING_NOT_FOUND`. USD and CDF mappings are independent and nothing converts between them.
- Mapping preview and commit follow section 5. Invalid codes: 422 `ACCOUNTING_MAPPING_INVALID` (parameter `field` is one
  of the five property names; the value is never echoed).

## 4. Journal model (D96-3)

One journal per package and currency (the package has exactly one currency, so one per package). **Version**: always 1
in this story (no regenerate, cancel or overwrite exists); the column and its uniqueness exist for the later stories the
issue names. Entries per payslip, each only when the amount is positive:

1. DEBIT `salaryExpense` account, `gross_minor`;
2. CREDIT `employeeDeductionLiability` account, `employee_deductions_minor`;
3. DEBIT `employerContributionExpense` account, `employer_contributions_minor`;
4. CREDIT `employerContributionLiability` account, `employer_contributions_minor`;
5. CREDIT `netSalaryPayable` account, `net_minor`.

Lines are aggregated by (account code, cost center code, site code, direction); entries mapped to the same account and
direction merge. No tax-authority, bank, cash, FX, rounding, receivable or clearing entry exists. Because
`net = gross - deductions` and the employer entries are equal, `totalDebits == totalCredits` by arithmetic; the domain
checks it anyway and refuses with `ACCOUNTING_JOURNAL_UNBALANCED` (no write) when it fails.

Deterministic order (and `line_number` 1..n): account code in byte order, then DEBIT before CREDIT, then cost center code
(empty first), then site code (empty first). Limits: 10,000 entries before aggregation and 2,000 lines after, otherwise
422 `ACCOUNTING_JOURNAL_TOO_LARGE`.

## 5. Preview and commit (D96-4)

Both mapping and journal use the established preview/commit pattern of ADR 0036 and ADR 0038.

- **Preview** (read-only, one disclosure audit, nothing is written) returns the evidence, `status` `READY` or `BLOCKED`
  with stable blocker codes, and (only when READY) a server-owned `previewDigest` and a 15-minute `expiresAt` (computed
  exactly like the payment-batch and reconciliation previews). Journal blockers: `PACKAGE_VOIDED`,
  `PACKAGE_NOT_APPROVED`, `JOURNAL_ALREADY_EXISTS`, `MAPPING_NOT_FOUND`, `TOO_LARGE`, `UNBALANCED`. Mapping blockers:
  `OVERLAP`, `LIMIT_REACHED`. Journal preview shows the package version, the mapping version, the currency, the
  per-account lines (so the administrator sees exactly what will be generated), the totals, the line counts and the
  fixed notice code.
- The journal digest binds tenant, legal entity, package id, package version, package approval digest, calculation
  digest, mapping id, version and digest, currency, aggregated line count, total debits, total credits and expiry.
  The mapping digest binds tenant, entity, currency, version, `effective_from` and the five codes.
- **Commit** repeats the previewed expectations (`expectedPackageVersion`, `expectedMappingVersion` for the journal;
  `expectedVersion` for the mapping), `previewDigest`, `expiresAt`, the confirmation `understandExportOnly=true` and an
  `Idempotency-Key`. One transaction takes the locks (section 7), re-reads and re-evaluates; any difference in package
  state, mapping or totals, or an expired preview, is 409 `ACCOUNTING_JOURNAL_PREVIEW_STALE` (mapping:
  `ACCOUNTING_MAPPING_PREVIEW_STALE`). A second journal for the package is 409 `ACCOUNTING_JOURNAL_ALREADY_EXISTS`.
  Same key and command replays the original 201 with `Idempotent-Replayed: true`; the same key with a different command
  is refused by the shared idempotency service. The response is an amount-free receipt.
- Concurrency: two simultaneous commits (different keys) create exactly one journal; the loser gets
  `ACCOUNTING_JOURNAL_ALREADY_EXISTS`. A real PostgreSQL test proves it.

## 6. Immutability and source void (D96-5)

Mapping, journal and journal lines are append-only: the database refuses UPDATE, DELETE and TRUNCATE. If the package is
voided later, the journal row and lines are untouched; the read model exposes `sourceVoided` derived at read time from
`payroll_package.voided_at` (a join, never a write) and the UI marks the journal `SOURCE_VOIDED`. The CSV and manifest
**never contain** the source status, so the downloaded files are byte-identical before and after a void. A replacement
package can produce its own journal; nothing replaces an earlier one. There is no cancel, delete, regenerate or
overwrite operation.

## 7. Persistence (V50)

All tables are in schema `payroll`, tenant-owned with composite foreign keys, classification restricted financial data.
No GRANT or REVOKE (as V43 to V49).

- `accounting_mapping`: `(tenant_id, legal_entity_id, id)` primary key; `currency` in (`CDF`, `USD`); `version` 1..10;
  `effective_from` date; the five code columns with the format check; `mapping_digest` (hex SHA-256 computed by the
  application and format-checked); `created_at`, `created_by_subject`. Unique (tenant, entity, currency, version) and
  (tenant, entity, currency, effective_from). Guard trigger: insert only for an existing legal entity of the tenant,
  version = previous + 1, `effective_from` strictly after the previous version's (serialized by an advisory lock keyed on
  tenant, entity and currency); no update, delete or truncate.
- `accounting_journal`: `(tenant_id, legal_entity_id, id)` primary key; `package_id`, `currency`, `version` (1..25),
  `period_start`, `legal_entity_code` (copy, for the deterministic file name), `package_version`,
  `package_approval_digest`, `calculation_id`, `calculation_version`, `calculation_digest`, `mapping_id`,
  `mapping_version`, `mapping_digest`, `payslip_count`, `source_entry_count`, `line_count` (1..2000),
  `total_debits_minor`, `total_credits_minor` (CHECK equal and positive), the four source totals, `csv_sha256`,
  `csv_bytes`, `journal_digest`, `schema_version` (=1), `generated_at`, `generated_by_subject`. Unique
  (tenant, package_id) and (tenant, package_id, currency, version); foreign key to the package and to the mapping.
- `accounting_journal_line`: `(tenant_id, journal_id, line_number)` primary key; `account_code`, `direction` in
  (`DEBIT`, `CREDIT`), `amount_minor` > 0, nullable `cost_center_code` and `site_code`; foreign key to the journal.
- Guards: a deferred constraint trigger checks that, at commit, the lines of a journal are numbered 1..`line_count`
  without gaps, that debits equal credits and equal the header totals, and that the journal's package and mapping belong to
  the same tenant, entity and currency; immutability triggers refuse update, delete and truncate on all three tables
  (constraint names `accounting_mapping_immutable`, `accounting_journal_immutable`,
  `accounting_journal_line_immutable`, mapped to a safe internal error).
- Rollback `db/rollback/V50__rollback.sql` refuses while any mapping, journal or line row exists (no override, no
  deletion), restores V49 exactly, and does not edit `flyway_schema_history`.

## 8. API (D96-10)

The contract in `docs/API-SPEC.yaml` is defined first and the client is generated from it. Resource paths follow the
established payroll convention (everything under the legal entity), which adjusts the issue's suggested shape only in
that prefix:

- `GET  /legal-entities/{legalEntityId}/accounting-mappings?currency=` (all versions, newest first, at most 20)
- `POST /legal-entities/{legalEntityId}/accounting-mappings/preview`
- `POST /legal-entities/{legalEntityId}/accounting-mappings` (`Idempotency-Key`) 201
- `GET  /legal-entities/{legalEntityId}/payroll-packages/{packageId}/accounting-journals`
- `POST /legal-entities/{legalEntityId}/payroll-packages/{packageId}/accounting-journals/preview`
- `POST /legal-entities/{legalEntityId}/payroll-packages/{packageId}/accounting-journals` (`Idempotency-Key`) 201
- `GET  /legal-entities/{legalEntityId}/accounting-journals/{journalId}` (header, source evidence, lines, `sourceVoided`)
- `GET  /legal-entities/{legalEntityId}/accounting-journals/{journalId}/csv`
- `GET  /legal-entities/{legalEntityId}/accounting-journals/{journalId}/manifest`

No generic journal-entry API exists. Stable errors added (client-safe, parameter-free except `ACCOUNTING_MAPPING_INVALID`):
`ACCOUNTING_MAPPING_NOT_FOUND` 409, `ACCOUNTING_MAPPING_INVALID` 422, `ACCOUNTING_MAPPING_OVERLAP` 409,
`ACCOUNTING_MAPPING_LIMIT_REACHED` 409, `ACCOUNTING_MAPPING_PREVIEW_STALE` 409, `ACCOUNTING_JOURNAL_SOURCE_NOT_APPROVED`
409, `ACCOUNTING_JOURNAL_SOURCE_VOIDED` 409, `ACCOUNTING_JOURNAL_ALREADY_EXISTS` 409,
`ACCOUNTING_JOURNAL_PREVIEW_STALE` 409, `ACCOUNTING_JOURNAL_UNBALANCED` 409, `ACCOUNTING_JOURNAL_TOO_LARGE` 422,
`ACCOUNTING_JOURNAL_NOT_FOUND` 404. Existing codes are reused where exact: `PAYROLL_PACKAGE_NOT_FOUND` (unknown,
foreign or foreign-entity package), `RATE_LIMITED`, `IDEMPOTENCY_KEY_REUSED`. `ACCOUNTING_MAPPING_NOT_FOUND` is a 409
conflict ("no applicable mapping"), not a 404 resource lookup, because no mapping is addressed by identifier.

## 9. Downloads (D96-6)

- CSV: UTF-8 **with** BOM (`EF BB BF`), comma separated, RFC 4180 escaping (a cell is quoted only when it contains a
  comma, quote, CR or LF; none of the allowed characters needs it, the encoder is still general), **CRLF after every row
  including the last**, header
  `journal_id,package_id,legal_entity_id,currency,line_number,account_code,direction,amount_minor,cost_center_code,site_code`,
  rows in the section 4 order, lowercase UUIDs, plain integers. No employee name, number, e-mail, bank account, phone,
  national identifier, payslip detail or free text.
- Manifest: canonical JSON (keys sorted, no insignificant whitespace, LF-free), `schemaVersion` 1, `journalId`,
  `journalVersion`, `packageId`, `packageVersion`, `calculationId`, `calculationVersion`, `legalEntityId`,
  `legalEntityCode`, `period` (YYYY-MM), `currency`, `mappingId`, `mappingVersion`, `mappingDigest`, `lineCount`,
  `totalDebitsMinor`, `totalCreditsMinor`, `generatedAt` (UTC, millisecond precision), `csvFileName`, `csvBytes`,
  `csvSha256`. It carries no source status and no employee data. Its `csvSha256` must equal the SHA-256 of the downloaded
  CSV bytes; the service recomputes the CSV from the stored lines at every download and refuses (internal error) if it
  differs from the digest stored at generation.
- File names (generated, never from user text): `accounting-journal-<ENTITYCODE>-<YYYY-MM>-<CUR>-v<version>.csv` and
  `.manifest.json`. Headers: `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`,
  `Cache-Control: private, no-store`. Every download is a fail-closed disclosure audit written before any byte is
  returned.

## 10. Authority, privacy, audit, events (D96-7)

Every operation requires an active `tenant-admin` membership with exact `acr=urn:divalhr:loa:mfa`
(`@TenantAdminOperation`); tenant, subject and correlation ID come only from the verified request context; authorization,
membership, MFA and the rate limits run before path, query and body binding. Unknown, malformed, foreign-tenant and
foreign-entity identifiers give the established safe 404. There is no employee, manager or platform endpoint. Mapping
values and journal amounts never enter URLs, cursors, audit or outbox metadata, logs, metrics or error bodies.

Audit actions (fail closed): `accounting-mapping.preview`, `.create`, `.read`; `accounting-journal.preview`,
`.generate`, `.read`, `.download` (view `csv` or `manifest`). Metadata is an exact allow-list of identifiers, versions,
currency, line count and view. **No totals and no amounts** in audit or outbox: the existing payroll policy keeps
amounts out of audit and outbox metadata, so they stay out. Internal outbox events `payroll.accounting-mapping-created.v1`
(entity, mapping id, currency, version) and `payroll.accounting-journal-generated.v1` (entity, package, journal id,
version, currency, line count) are absent from the webhook public mappings, the WhatsApp mappings and the fan-out
markers (tests run both real fan-out jobs as controls). Sentinel tests scan PostgreSQL evidence, audit, outbox,
idempotency, errors, cursors and every service log for the privacy sentinels.

## 11. Limits

Buckets: `accounting-preview` 20 per subject per hour (mapping and journal previews share it);
`accounting-journal-write` 5 per tenant per hour; `accounting-mapping-write` 20 per tenant per hour;
`accounting-read` (reads and downloads) 60 per subject per minute and 300 per tenant per hour. They use the existing
limiter infrastructure (no new instance-local assumption beyond what it already documents). Maximum 10 mapping
versions per legal entity and currency; 10,000 entries before and 2,000 lines after aggregation. Downloads are bounded
by those limits (at most 2,000 lines, a few hundred KiB) and are produced in memory exactly like the payment register.

## 12. Bilingual UI (D96-8)

Under Payroll: **Accounting mappings** / **Paramètres comptables** (legal-entity payroll area; list, schedule a new
version through preview and confirmation) and **Accounting journal** / **Journal comptable** (approved-package detail
page; preview with the exact lines, confirmation, generated journal detail, CSV and manifest downloads, journal
history link, visible `SOURCE_VOIDED` state). Every page states the fixed notice. No control suggests posting,
settlement or payment confirmation. English and French parity for all texts, errors, empty states, confirmations and
the disclaimer; keyboard flow, visible focus, accessible names, axe, and reflow at 320, 390 and 640 px.

## 13. Truth (D96-9)

When merged, the landing page and administrator home mark **accounting-ready payroll journal export** active. They keep
under Coming next / Prochainement: direct accounting-system posting, real bank or mobile-money connectivity, settlement
or paid status, employee payment notification, salary advances and loans. BACKLOG.md records MVP-096 as implemented in
the PR and **not** deployed or accepted until those steps pass. The real DRC statutory pack remains a DRAFT
(ADR 0034): the journal exports whatever the approved package froze and makes no statement about statutory
correctness; the existing statutory-pack limitations apply unchanged.

## 14. Not included

Direct posting to any accounting system; tenant-defined debit/credit formulas; chart-of-accounts import; tax filing;
settlement, paid status, bank reconciliation; real bank or operator connectivity; employee notification; loans or salary
advances; FX conversion; partial packages; journal cancellation, deletion, manual editing or regeneration; attachments,
approvals or four-eyes review; AI-generated accounting treatment; a generic journal-entry API.
