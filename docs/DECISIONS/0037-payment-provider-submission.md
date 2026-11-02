# ADR 0037: Provider submission of a payment batch (MVP-095B, Issue #187)

Status: accepted for implementation (decisions D95B-1 to D95B-13 of Issue #187 are binding; this ADR records the
design points the issue leaves to the implementation). Builds on ADR 0036 (payment destinations and batches).
Migration: **V48**. Base: `main` at `5b0ff854693e702f0cc05f334609d96fb962346a`.

## 1. Meaning and boundary

A **submission** is the one durable handoff of a PREPARED payment batch to a **controlled provider connector**. The
strongest thing a provider may say is that it **accepted the instructions for processing** or **rejected** them.
That never means an employee was paid, funds settled, a destination was verified or money moved. DivalHR keeps no
"paid" status anywhere in this story, and the fixed notice (EN "Accepted for processing does not mean paid. DivalHR
has not confirmed settlement." / FR « Accepté pour traitement ne signifie pas payé. DivalHR n'a pas confirmé le
règlement. ») is on every submission view.

This story ships one provider profile, `SYNTHETIC-TEST`: a clearly labelled synthetic HTTPS receiver reachable only
inside the dev/hr-dev Compose network. No real bank, M-Pesa, Airtel Money, Orange Money or other endpoint or
credential is committed, seeded, enabled or contacted. A real provider needs its own approval of contract,
credentials, egress, legal and commercial terms and live test plan.

## 2. Authority (D95B-1)

Every administrator operation is tenant-scoped and requires an active `tenant-admin` membership with exact
`acr=urn:divalhr:loa:mfa` (the existing `@TenantAdminOperation` gate). Tenant, subject and correlation ID come only
from the verified request context. Unknown, malformed and foreign batch IDs return the same safe 404
(`PAYMENT_BATCH_NOT_FOUND`); an unknown submission is `PAYMENT_SUBMISSION_NOT_FOUND`. Authorization, membership and
rate limits run before path, query and body binding. There is no employee, manager or platform endpoint.

## 3. Connector and profiles (D95B-2, D95B-3)

`PaymentProviderGateway` is a payroll-owned port (`payroll.application`); `HttpsPaymentProviderGateway`
(`payroll.internal`) is its only adapter. No controller or domain service calls an HTTP client.

Profiles are operator configuration (`divalhr.payroll.submission.profiles`), allow-listed by **exact code**, legal
entity (or `*`), currency and supported methods (`BANK_ACCOUNT`, `MOBILE_MONEY`). Tenants supply none of: URL, header,
credential, certificate, provider code. A profile names one HTTPS host and port; the adapter requires the request URL
to equal it exactly. The default configuration contains exactly one active profile: `SYNTHETIC-TEST`
(host `payments.hooks.test`, port 8443, CA file mounted read-only), enabled only in the dev/hr-dev profile.

Transport security: HTTPS only, TLS 1.2/1.3 with hostname verification and the host name kept for SNI; redirects are
never followed (the adapter reads one response and closes); DNS is resolved per attempt and the connection is pinned
to the validated address; every answer must be a public global-unicast address except the **exact** synthetic
receiver host and port; explicit connect, write and read deadlines; the response is read up to a hard cap
(4 KiB) and anything larger or malformed is `UNKNOWN_BAD_RESPONSE`.

Secrets: the HMAC signing key lives only in the approved secret-file path (`secrets/core/DIVALHR_PAYMENT_SUBMISSION_SIGNING_KEY`,
created once by `ops/hr-dev/init-secrets.sh`, uid 10001, mode 0400, never rotated in place, never printed). It is read
at startup by `PaymentSubmissionSecrets`, never stored in PostgreSQL, an environment dump, Git, an API body or the UI.
The key carries a version (`key_version`, 1) recorded on the submission so a later rotation story can sign new
submissions with a new key.

## 4. Payload and signature (D95B-3, D95B-4)

The outbound bytes are a **canonical UTF-8 JSON** document built in memory per attempt: keys sorted, no whitespace,
integers only, lines ordered by batch line id. It contains `schemaVersion`, `requestId`, `batchDigest`, `currency`,
`lineCount`, `totalMinor` and, per line, `lineRef`, `amountMinor`, `method` and the destination fields the provider
needs. Net minor units and currency are copied from the immutable batch lines; nothing is calculated, converted,
aggregated or partially selected. Destination plaintext is opened (AES-256-GCM, MVP-095A) only while the bytes are
built, is never persisted anywhere (submission, attempt, audit, outbox, idempotency, error), and the temporary byte
buffers are zeroed best-effort after the attempt.

The request carries `X-Divalhr-Request-Id` (the stable submission request ID), `X-Divalhr-Key-Version` and
`X-Divalhr-Signature: v1=<hex>` where the signature is HMAC-SHA256 over `"v1\n" + requestId + "\n" + canonicalBytes`.
The synthetic receiver recomputes it, checks the request ID against the body, and checks the payload against what it
expects from the contract (counts and totals are self-consistent). Maximum 5,000 lines and one currency; one invalid
line, a decryption failure or a mapping failure blocks the whole request **before any network access**.

## 5. Preview and commit (D95B-5)

Preview is read-only (one disclosure audit) and returns provider code and label, the SYNTHETIC-TEST flag, batch
version and digest, line count, currency, total minor units, method counts, readiness and blocker codes, a
`previewDigest` and its expiry (15 to 30 minutes, on the 15-minute grid). It returns no destination plaintext. Commit repeats batch version and
digest, preview digest and expiry, `reviewedProviderAndTotals=true`, `understandAcceptedIsNotPaid=true`, plus
`Idempotency-Key`. One transaction takes the locks in the existing payroll/payment order (idempotency reservation,
tenant statutory lock shared, entity package lock, package row `FOR UPDATE`, tenant payment lock exclusive, then the
batch's own submission lock), re-reads and re-evaluates everything including a dry decryption and mapping of every
destination version, creates exactly one submission (`QUEUED`, version 1, evidence sequence 1), writes the audit row
and outbox event, and returns. The database enforces one submission per batch forever.

## 6. State machine and delivery semantics (D95B-6)

Closed states: `QUEUED`, `SENDING`, `ACCEPTED_FOR_PROCESSING`, `REJECTED`, `DELIVERY_UNKNOWN`,
`MANUAL_INTERVENTION`. Legal transitions are in the database (`payment_submission_transition_ok`) and every change
needs its evidence row in the same transaction (deferred exactness constraint).

A bounded worker claims due `QUEUED` rows with `FOR UPDATE SKIP LOCKED` and a lease (default 2 minutes, claim is
`QUEUED -> SENDING` and counts one attempt). The request ID never changes: it is created at commit and reused by
every attempt.

| Observation                                                                                                               | Result                                                   |
| ------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| 2xx with the contract's authenticated acceptance and a reference matching `^[A-Z0-9][A-Z0-9._-]{5,63}$`                   | `ACCEPTED_FOR_PROCESSING`                                |
| 400/409/422 with an allow-listed reject code                                                                              | `REJECTED` (never retried)                               |
| refused address, DNS failure, connect failure, TLS failure **before any byte was written**                                | back to `QUEUED` with the retry schedule (`TRANSIENT_*`) |
| 429 or 5xx **response**                                                                                                   | back to `QUEUED` with the retry schedule                 |
| timeout or connection loss **after** bytes were written, unparsable or oversized response, 2xx without a valid acceptance | `DELIVERY_UNKNOWN` (no blind resend)                     |
| lease expired in `SENDING` (worker crash)                                                                                 | `DELIVERY_UNKNOWN` / `UNKNOWN_LEASE_EXPIRED` (no resend) |
| attempts exhausted (6)                                                                                                    | `MANUAL_INTERVENTION` / `RETRIES_EXHAUSTED`              |
| a destination cannot be opened, authenticated or mapped at send time (nothing was written to the network)                 | `MANUAL_INTERVENTION` / `NOT_SENT_UNREADABLE`            |

Automatic retry is permitted only because the synthetic provider contract proves it honors the stable request ID
(a replay returns the first outcome and never a second acceptance). The retry schedule is 1 minute, 5 minutes, 30
minutes, 2 hours, 12 hours (the existing webhook schedule). Recovery of `DELIVERY_UNKNOWN`, resubmission after a
rejection and settlement reconciliation are later stories.

## 7. Guards (D95B-7)

Once any submission exists the batch cannot be cancelled and its package cannot be voided. Application code refuses
first (`PAYMENT_BATCH_ALREADY_SUBMITTED` on cancel preview and commit; the void preview lists the blocker); the
database refuses on the batch update, on the cancellation evidence insert and on the package void, so bypass writes
fail closed. This story never marks anything paid and never releases the void guard.

## 8. Audit, events and fan-out (D95B-8)

Audit actions: `payment-submission.preview`, `.create`, `.read`, `.attempt`. Metadata is an exact allow-list of ids,
provider code, state, version, counts, attempt number and outcome code; never an amount, currency total, employee
id, destination, provider reference, signature or response detail. Internal outbox events:
`payroll.payment-submission.created.v1`, `payroll.payment-submission.state-changed.v1`, carrying only submission id,
batch id, state, version and outcome code. They are internal: absent from the webhook public mappings and the
WhatsApp mappings, proved by tests that run the real fan-out jobs.

## 9. API (D95B-9)

Under the existing payment-batch resource, all `Cache-Control: private, no-store`:

- `POST /legal-entities/{legalEntityId}/payment-batches/{batchId}/submissions/preview`
- `POST /legal-entities/{legalEntityId}/payment-batches/{batchId}/submissions` (`Idempotency-Key`)
- `GET  /legal-entities/{legalEntityId}/payment-batches/{batchId}/submission`
- `GET  /legal-entities/{legalEntityId}/payment-batches/{batchId}/submission/attempts?cursor&limit` (keyset)

Error codes (stable, client-safe, parameter-free): `PAYMENT_SUBMISSION_NOT_FOUND` 404,
`PAYMENT_SUBMISSION_BLOCKED` 409 (batch not submittable), `PAYMENT_SUBMISSION_PREVIEW_STALE` 409,
`PAYMENT_SUBMISSION_VERSION_CONFLICT` 409, `PAYMENT_SUBMISSION_EXISTS` 409, `PAYMENT_PROVIDER_UNAVAILABLE` 409 (not
available or unsupported), `PAYMENT_SUBMISSION_KEY_UNAVAILABLE` 503, `PAYMENT_BATCH_ALREADY_SUBMITTED` 409 (cancel
refused), `RATE_LIMITED` 429 (existing). Delivery outcomes (rejection, unknown delivery, timeout, retries exhausted)
are not HTTP errors: they are states and allow-listed outcome codes in the read model, because the administrator's
request succeeded when the submission was queued.

## 10. Operations (D95B-12)

Rate limits (subject per minute / tenant per hour): preview 5 / 60, commit 2 / 20, read and history 60 / 300. The
buckets are `payment-submission-preview`, `payment-submission-write`, `payment-submission-read` (subject) and the
same names for the tenant. Worker: one scheduler, at most 8 submissions claimed per cycle, one in flight per
submission, bounded queue depth reported as a gauge. Metrics: counters by provider code, state and outcome class
only. Logs: operation, state, outcome class, correlation id, exception class only. Alerts: `DELIVERY_UNKNOWN`,
`MANUAL_INTERVENTION`, repeated worker failures, stale leases. Operator actions are documented in OPS-HR-DEV.md and
none of them authorizes a resend.

## 11. Truth refresh (D95B-13)

hr-dev runs `5b0ff854…` with V47; MVP-095A is deployed and accepted and Issue #183 closed. The landing page and
homes mark payment destinations and batch preparation active and provider submission in progress (active only after
this story is deployed and accepted). Settlement, reconciliation, accounting posting, real-provider connectivity,
loans and salary advances stay under "Coming next". The backlog truth test fails CI on stale deployment or
capability claims. No page claims DRC compliance, provider verification, disbursement or paid status.

## 12. Not included

Real providers, transfer, settlement, paid status, balance inquiry, destination verification, callbacks, polling,
reconciliation, resubmission, partial batches, fees, FX, multi-currency, accounting, statutory filing, employee
notification, loans, tenant-authored endpoints or payload templates, production egress changes.
