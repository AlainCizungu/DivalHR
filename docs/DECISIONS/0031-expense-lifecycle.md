# ADR 0031 Employee expenses: submit, manager approval or return, with immutable evidence

## Status

Decisions D81-1 to D81-14 approved by the architect on Issue #169 and implemented, as written, by the
MVP-081 pull request; pending architecture review of the implementation. Builds on ADR 0006
(idempotent commands), ADR 0007 (transactional outbox), ADR 0008 (lock order), ADR 0016 (neutral
wording), ADR 0002 (bilingual first class) and ADR 0030 (restricted text, append-only evidence). The
decision-to-code map is `docs/DECISIONS/0031-evidence.md`.

## Context

A linked employee needs to record a business expense, attach one receipt and send it to their
manager, who approves it or returns it for correction. Merchant, description, return reason and the
receipt are confidential tenant data, so most of the design is about where they may and may not
travel. Approval is a business decision only: it is not reimbursement, payment or accounting.

## Decision

- **Roles and routes (D81-1).** Linked active employee: `/me/expenses`. The employee's effective
  manager (resolved through the `ReportingLines` port, never by reading people tables): `/me/expense-approvals`.
  Tenant administrator (exact MFA): `/admin/expenses`, read only. Unknown, foreign and unauthorized
  ids are the same 404.
- **Currency and amount (D81-2).** CDF (exponent 0, 1 to 100,000,000) or USD (exponent 2, 1 to
  10,000,000 minor units). Integer minor units, a canonical string on the wire. Nothing converts,
  rounds across or combines currencies; lists and totals never mix them.
- **Expense data (D81-3).** Merchant 1 to 120, description 10 to 1,000 (restricted plain text, the
  ADR 0030 grammar), category `TRANSPORT | MEALS | LODGING | SUPPLIES | COMMUNICATION | TRAINING |
  MEDICAL_WORK_RELATED | OTHER` (a label, never a diagnosis), business date.
- **Business date (D81-4).** Not in the future and at most 90 days old, in the organization's
  business calendar; the server owns every timestamp.
- **Receipt (D81-5).** At most one per revision; PDF, JPEG or PNG; at most 2 MiB; the declared type
  must match the magic bytes and structure; SHA-256 stored and checked by the database. A JPEG
  must decode completely with the JDK's maintained image reader (every scan line, no decoder
  warning, whole segment structure ending exactly at the end-of-image marker, bounded pixel count,
  dimensions from the decoder); a PDF must load with Apache PDFBox's strict, non-repairing parser
  as an unencrypted document of 1 to 50 pages with no active or embedded content in the parsed
  object graph (object streams included) or in the raw bytes (R170-1, architecture review of
  PR #170). The upload is
  `PUT /receipt` with a raw body: authorization, idempotency and quotas run before the body is read;
  multipart parsing is not enabled. No public URL: the download is an authorized, `private, no-store`,
  `attachment` response with `nosniff`. Pilot storage is PostgreSQL; the production object-storage
  port is a documented follow-up (see OPS-HR-DEV).
- **Lifecycle (D81-6).** `DRAFT -> SUBMITTED -> APPROVED | RETURNED`, `SUBMITTED -> WITHDRAWN` by
  the employee, `DRAFT | RETURNED -> WITHDRAWN`. `APPROVED` and `WITHDRAWN` are terminal. A returned
  expense is corrected by editing and resubmitting; it cannot be resubmitted unchanged.
- **Manager routing (D81-7).** At submission the route is resolved to the single active manager and
  stored; every manager read and decision re-verifies that the route still holds and the manager is
  active (fail closed). No manager means `409 EXPENSE_ROUTE_UNAVAILABLE`. Nobody approves their own
  expense (database check and service check).
- **Revision and evidence (D81-8).** `expense` is the projection; `expense_revision` is editable
  until submitted then immutable; `expense_receipt` is immutable; `expense_action` is append-only
  with one increasing sequence. Nothing is deleted; a receipt used by a submitted revision is kept.
- **Idempotency and transactions (D81-9).** Every command needs `Idempotency-Key` and the expected
  version. Lock order: manager graph, employee link, expense row. Projection, revision, action,
  audit and outbox commit atomically; a failure of any rolls all back. The stored command receipt
  carries identifiers only.
- **API (D81-10).** Contract first (`docs/API-SPEC.yaml`, 16 operations), generated client, keyset
  pages (default 25, maximum 100) with a signed cursor bound to tenant, actor, filters, order and size;
  nine stable `EXPENSE_*` problem codes.
- **Audit and events (D81-11).** Disclosure audits for list, read and receipt download; command
  audits for create, update, receipt set and remove, submit, withdraw, approve, return. Events
  `operations.expense.submitted|approved|returned|withdrawn.v1` carry identifiers, state, currency,
  amount in minor units and counts. No merchant, description, reason, file name or bytes. No
  notification, WhatsApp or webhook mapping.
- **UI and accessibility (D81-12).** `/me/expenses`, `/me/expense-approvals`, `/admin/expenses`,
  complete in English and French, axe-checked, usable at 320 px, status as text, confirmations for
  submit, withdraw, approve and return, amounts formatted per locale and currency.
- **Limits and privacy (D81-13).** 100 open expenses per employee and 5,000 per tenant in the
  database; hourly quotas of 60 writes, 30 receipt changes per employee and 120 decisions per
  manager, counted from `expense_action`; per-minute burst buckets in `application.yaml`.
- **Tests (D81-14).** See the evidence map; two Playwright journeys.

## Consequences

- **PDFBox is a new runtime dependency** (Apache-2.0, `org.apache.pdfbox:pdfbox:3.0.8`, with
  `commons-logging` excluded because Spring's `spring-jcl` already provides that API). It parses
  only; nothing is rendered, no stream is decoded and nothing touches the disk. Its two 2026
  advisories concern the separate `pdfbox-examples` artifact, which is not used.
- **JPEG limits.** 8-bit baseline, extended-sequential and progressive JPEGs with one or three
  components, up to 16 megapixels; CMYK, arithmetic-coded, lossless and 12-bit files are refused.
  The JDK decoder keeps about one megapixel (subsampled output) in memory while it reads every
  scan line.
- **PDF strictness.** The cross-reference data and trailer must be intact; a damaged file that a
  tolerant reader could repair is refused, so a "loadable" receipt means loadable as written.

- The manager queue is a bounded scan with per-row route verification: correct and fail-closed, not
  the cheapest possible query; revisit if queues grow.
- The administrator view shows submitted revisions only; drafts stay private to the employee.
- Receipts in PostgreSQL grow the database; 2 MiB and the open-expense caps bound it for the pilot.
- V42 is additive; its rollback refuses while any expense row exists.

## Out of scope

Reimbursement or payment, accounting or payroll changes, exchange rates or conversion, OCR or AI,
multi-level approval, notifications (WhatsApp, e-mail, webhooks), external object storage, editing a
submitted revision, deleting expenses.
