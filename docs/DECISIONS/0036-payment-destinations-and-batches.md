# ADR 0036: Payment destinations and payment batches (MVP-095A, Issue #183)

Status: accepted for implementation (decisions D95A-1 to D95A-13 of Issue #183 are binding; this ADR
records the design points the issue leaves to the implementation). Builds on ADR 0035 (payroll approval
and payslips). Migration: **V47**.

## 1. Meaning and boundary

A **payment destination** is the employee's recorded place to be paid: a bank account, a mobile-money
number or a manual arrangement. A **payment batch** is an immutable **register of payment instructions**
copied from one APPROVED, non-voided, latest payroll package. A prepared batch is **not** a payment: nothing
is sent to a bank or provider, no money moves, no account is verified, and no payment is claimed. A
destination's status is RECORDED and never VERIFIED. DivalHR makes no claim of DRC compliance.

Product wording avoids "paid", "sent", "disbursed", "payment completed", "bank-verified" (EN) and their
French equivalents; web tests scan both locales for these phrases.

## 2. Authority and privacy (D95A-1, D95A-2)

Tenant administrators with exact MFA record destinations and prepare, cancel and download batches. A linked
employee reads only their own current destination, masked, at `/me/payment-destination`. Every query is
tenant-scoped (ArchUnit tenant-predicate rule plus a repository source test). Foreign tenants and non-admins
receive the platform's safe refusals.

## 3. Destinations (D95A-3 to D95A-6)

Methods: `BANK_ACCOUNT`, `MOBILE_MONEY` (E.164 phone), `MANUAL`. History is append-only and effective-dated
(database triggers refuse UPDATE and DELETE); a new version supersedes by date, with a controlled reason
code. Preview then commit with expected version, preview digest and expiry (stale evidence fails closed),
and an `Idempotency-Key`.

Account and phone values are sealed with an AES-256-GCM envelope (`PaymentCipher`) under a versioned key
provisioned through the approved secret path; a keyed fingerprint supports duplicate detection without
decrypting. After commit only masked values are readable; the plain value and holder name never appear in
logs, audit metadata, outbox events, idempotency bodies or receipts (sentinel scans in integration tests).

## 4. Batches (D95A-7 to D95A-10)

A batch exists only for the latest APPROVED, non-voided package, with exact net amount and currency copied
from the package (never recalculated) and no partial batch: any employee without a valid destination blocks
the whole batch (`MISSING_DESTINATION`). States are PREPARED and CANCELLED only. One PREPARED batch per
package. Limit 5,000 lines; 25 destination versions per employee.

`PreparedPaymentBatchGuard` implements `PayrollPaymentGuard`: a PREPARED batch makes the payroll void fail
closed (`PAYROLL_VOID_NOT_ALLOWED`); cancelling the batch releases it. Cancellation uses a controlled reason
and its own preview and digest.

Thirteen stable error codes (`PAYMENT_DESTINATION_*`, `PAYMENT_BATCH_*`) are mirrored in the web client.

## 5. Downloads (D95A-11)

`register.csv` and `manifest.json` are deterministic for a batch (stable ordering, no timestamps beyond the
stored ones, SHA-256 in the manifest). CSV cells that could start a formula are neutralised. Downloads are
audited and rate limited.

## 6. Operations (D95A-12, D95A-13)

New request-limit buckets (`payment-read`, `payment-destination-preview`, `payment-destination-write`,
`payment-batch-preview`, `payment-batch-write`, `payment-register-download`). Key material is provisioned by
the ops script, never committed. Rollback of V47 refuses while destinations or batches exist and records the
evidence. No webhook or WhatsApp event carries payment data.

Executable proofs (review R184-1): `PaymentQueryPlanIntegrationTest` (every `JdbcPaymentRepository` statement is
index driven on a five-tenant analyzed dataset, no sequential scan of a payment, payslip or package table, no sort
on the ordered and paged paths, no index added); `PaymentEventsNotDeliveredTest` and
`PaymentEventsNotDeliveredIntegrationTest` (the three payment outbox types are in neither the webhook nor the
WhatsApp mapping or fan-out allow-list, and after the real operations and both fan-out jobs no fan-out marker and no
delivery row exists for them); `PaymentRateLimitIntegrationTest` (the six subject buckets and the 14 handlers, with
a reflection check against the configured buckets) and `PaymentTenantRateLimitIntegrationTest` (the five tenant
buckets); `PaymentRateLimitWindowTest` (all eleven buckets at the configured defaults on a stepped clock: exact limit,
Retry-After to the window end, fresh count in the next window).

## 7. Not included

Payment submission to a bank or provider, disbursement, reconciliation, accounting posting, salary advances,
destination verification, notifications.
