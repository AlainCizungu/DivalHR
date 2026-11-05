# ADR 0038: Reconcile uncertain provider submissions without resending payment (MVP-095C, Issue #190)

Status: accepted for implementation (decisions D95C-1 to D95C-14 of Issue #190 are binding; this ADR records the
design points the issue leaves to the implementation). Builds on ADR 0037 (provider submission).
Migration: **V49**. Base: `main` at `c93ce0ec0a828e9dc4fd78298ca10475208ff58a`.
This is a **new** ADR, not an amendment of ADR 0037, because it adds a separate aggregate, state machine, provider
operation and migration; ADR 0037 only gains a pointer in its section 12.

## 1. Meaning and boundary

A **reconciliation** is an administrator-initiated investigation of one payment submission whose delivery is
uncertain (`DELIVERY_UNKNOWN`) or whose automatic handling ended (`MANUAL_INTERVENTION`). It asks the controlled
provider one question: "what is the status of the payment request with this stable request ID?" The question is a
**status inquiry**, a read-like operation. It is never a payment instruction.

The strongest conclusion is `CONFIRMED_ACCEPTED_FOR_PROCESSING`: the provider's authenticated answer says it holds the
request and accepted it for processing. That never means an employee was paid, funds settled, a destination was
verified or money moved. DivalHR has no "paid" or "settled" status anywhere. The fixed notice is on every
reconciliation view:

- EN: "This inquiry does not resend the payment instruction. Provider acceptance does not mean paid or settled."
- FR: « Cette vérification ne renvoie pas l'instruction de paiement. L'acceptation du prestataire ne signifie pas que
  le paiement est effectué ou réglé. »

Scope: `SYNTHETIC-TEST` only. No real bank or operator, endpoint, credential or egress is added. The one genuine
`DELIVERY_UNKNOWN` submission on hr-dev from the first live acceptance is immutable evidence: no code, test or
document of this story references, repairs, replays or copies it.

## 2. Authority and binding (D95C-1)

Every operation is tenant-scoped and requires an active `tenant-admin` membership with exact
`acr=urn:divalhr:loa:mfa` (`@TenantAdminOperation`). Tenant, actor subject and correlation ID come only from the
verified request context. Authorization, membership, MFA and the two rate limits run before path, query and body
binding. Unknown, malformed, foreign-tenant, foreign-entity and undisclosable batch, submission and reconciliation
identifiers all return the same safe **404 `PAYMENT_RECONCILIATION_NOT_FOUND`** on these endpoints (a batch without a
submission, a submission without a reconciliation and a foreign batch are indistinguishable). There is no employee,
manager or platform endpoint. Every response is `Cache-Control: private, no-store`.

## 3. Eligibility and immutable history (D95C-2)

Only a submission **currently** in `DELIVERY_UNKNOWN` or `MANUAL_INTERVENTION` may be investigated. Both states have
no outgoing transition in ADR 0037, so a submission cannot leave an eligible state while a reconciliation exists. A
reconciliation never resends the payment instruction, creates a request ID, deletes or rewrites an attempt, infers
success from a transport result alone, or turns acceptance into "paid". The submission row, its request ID, payload
digest, attempts and transition evidence are unchanged. Reconciliation is a **separate payroll-owned append-only
aggregate** (section 7). The submission view may reference the latest reconciliation conclusion separately.

## 4. Provider status-inquiry contract (D95C-3)

`PaymentProviderGateway` gains `inquire(profile, requestId, inquiryId, keyVersion, signature, body)` returning a closed
`InquiryResult`. `HttpsPaymentProviderGateway` stays the only adapter and shares one implementation of resolution,
pinning, SSRF policy (R188-1A), TLS 1.2/1.3 with host-name verification, no redirect, explicit deadlines and the
4 KiB response cap with the 095B send path (`exchange` is parameterised by path, request-identity header lines and a
classifier; the bytes of a payment request are unchanged).

Request: `POST /v1/payment-status-inquiries` on the profile's exact host and port.

- Headers: `X-Divalhr-Request-Id` (the existing stable payment request ID), `X-Divalhr-Inquiry-Id`,
  `X-Divalhr-Key-Version`, `X-Divalhr-Signature: v1=<hex>`.
- Body: canonical UTF-8 JSON, keys sorted, no whitespace, fixed schema:
  `{"inquiryId":"<uuid>","requestId":"<uuid>","schemaVersion":1}`. It contains no employee identity, destination,
  amount, currency, line, batch or original payload data. The inquiry carries **only** the two identifiers.
- Signature: HMAC-SHA256 with the protected, versioned submission signing key (no new secret, no default) over
  `"v1-inquiry\n" + requestId + "\n" + inquiryId + "\n" + body`. The distinct prefix makes an inquiry signature
  unusable as a payment-request signature and the reverse.

Response (HTTP 200, `X-Divalhr-Response-Signature: v1=<hex>`, signature computed with the same inquiry function over
the response body): JSON `{"inquiryId","requestId","result","code"?}`. The adapter verifies the signature in constant
time **before** parsing, then requires both identifiers to equal the request's. The closed `result` set:

| Provider `result`        | Meaning                                         | Recorded inquiry outcome (closed)                                                 |
| ------------------------ | ----------------------------------------------- | --------------------------------------------------------------------------------- |
| `ACCEPTED_FOR_PROCESSING`| holds the request, accepted for processing      | `ACCEPTED_FOR_PROCESSING`                                                         |
| `REJECTED` + `code`      | definitively rejected, allow-listed code        | `REJECTED_INVALID_REQUEST`, `_SIGNATURE`, `_UNSUPPORTED`, `_DUPLICATE_MISMATCH`, `_LIMIT`, `_OTHER` |
| `NOT_FOUND`              | the provider has no record of the request ID    | `NOT_FOUND`                                                                       |
| `PENDING`                | the provider holds it and has not decided       | `PENDING`                                                                         |
| `UNKNOWN`                | the provider cannot say                         | `UNKNOWN`                                                                         |

Anything else is mapped to a transport or response outcome, never to arbitrary text: unsigned, wrongly signed,
malformed, oversized, wrong identifiers, a status other than 200/429/5xx, or a `REJECTED` without a code all become
`BAD_RESPONSE`; 429 `TRANSIENT_RATE_LIMITED`; 5xx `TRANSIENT_SERVER`; refused address, DNS or connect failure
`TRANSIENT_CONNECT`; TLS failure `TRANSIENT_TLS`; timeout `TRANSIENT_TIMEOUT`; a connection lost after the inquiry
was written `TRANSIENT_CONNECT` (an inquiry is safe to repeat). Provider text, references and bodies are never
stored or returned.

## 5. States (D95C-4)

Closed: `OPEN` (investigation exists, no trustworthy conclusion), `INQUIRY_IN_PROGRESS` (one worker holds a bounded
lease), `CONFIRMED_ACCEPTED_FOR_PROCESSING`, `CONFIRMED_REJECTED`, `STILL_UNRESOLVED`, `MANUAL_REVIEW_REQUIRED`.

| From                  | To                                  | Cause                                                                                  |
| --------------------- | ----------------------------------- | -------------------------------------------------------------------------------------- |
| `OPEN`                | `INQUIRY_IN_PROGRESS`               | a worker claims the due row (counts one inquiry attempt)                               |
| `OPEN`                | `MANUAL_REVIEW_REQUIRED`            | attempts exhausted (`RETRIES_EXHAUSTED`)                                               |
| `INQUIRY_IN_PROGRESS` | `OPEN`                              | transport failure, 429 or 5xx before a usable answer; or an expired lease (crash); next attempt scheduled |
| `INQUIRY_IN_PROGRESS` | `CONFIRMED_ACCEPTED_FOR_PROCESSING` | authenticated `ACCEPTED_FOR_PROCESSING`                                                |
| `INQUIRY_IN_PROGRESS` | `CONFIRMED_REJECTED`                | authenticated `REJECTED` with an allow-listed code                                     |
| `INQUIRY_IN_PROGRESS` | `STILL_UNRESOLVED`                  | authenticated `PENDING`, `NOT_FOUND`, `UNKNOWN`, or `BAD_RESPONSE`                     |
| `INQUIRY_IN_PROGRESS` | `MANUAL_REVIEW_REQUIRED`            | the failed attempt was the last allowed one                                            |

The four non-working states have **no outgoing transition**: a terminal conclusion is never replaced and a
`STILL_UNRESOLVED` investigation is not automatically retried (D95C-5 limits automatic retry to transport failure,
429 and 5xx). Re-investigation after `STILL_UNRESOLVED` or `MANUAL_REVIEW_REQUIRED` is an explicit later story
(known limitation). The underlying submission keeps its original state; `GET .../submission` is unchanged.

## 6. No resend and bounded inquiry (D95C-5)

An inquiry is not a payment instruction: no endpoint or worker may call `send`, move a submission to `QUEUED` or
`SENDING`, or create a request ID (the database also refuses it, section 7). One stable **inquiry ID** (random UUID,
created with the investigation) serves every attempt; the **attempt number** only increases (1 to 6). Automatic
retry only after a transport failure before a usable answer, 429 or 5xx, on the schedule 30 seconds, 2 minutes,
10 minutes, 1 hour, 6 hours; six attempts end in `MANUAL_REVIEW_REQUIRED`. A crash may repeat the inquiry with the
same inquiry ID (lease recovery returns the row to `OPEN`; it never sends a payment). Bounds: 6 attempts, 8 claims
per cycle, a 2-minute lease, 4 KiB responses, an open-count gauge as the queue-depth bound.

## 7. Persistence (D95C-8)

Migration **V49** (rollback `db/rollback/V49__rollback.sql` refuses while any reconciliation or inquiry exists; no
override, no evidence deletion):

- `payroll.payment_reconciliation`: one current row per submission (`UNIQUE (tenant_id, submission_id)`), tenant
  owned; copies `request_id`, `provider_code`, `key_version` of the submission and the submission state when the
  investigation was opened; unique `inquiry_id`; `state`, optimistic `version` (equals the latest evidence sequence),
  `attempt_count` 0..6, allow-listed `outcome_code`, `next_attempt_at`, lease columns, the two confirmations.
- `payroll.payment_reconciliation_inquiry`: append-only evidence, one row per state change (sequence 1 is the opening):
  `seq`, `attempt_number`, `state`, `outcome_code`, `authenticated` and `occurred_at`.
- Guards (triggers): insert only for an existing submission of the same tenant in `DELIVERY_UNKNOWN` or
  `MANUAL_INTERVENTION` with the **same** request ID, provider and key version; a change touches only lifecycle
  columns; the transition and the evidence transition are legal; attempts are counted exactly at the claim; no
  delete, truncate or evidence update. A terminal state exists only with matching authenticated evidence:
  `CONFIRMED_ACCEPTED_FOR_PROCESSING` needs outcome `ACCEPTED_FOR_PROCESSING`, `CONFIRMED_REJECTED` a `REJECTED_*`
  outcome, both with `authenticated = true`; `NOT_FOUND`, `PENDING`, `UNKNOWN` and `BAD_RESPONSE` evidence is only
  valid for `STILL_UNRESOLVED`. A deferred exactness constraint ties the current row to its latest evidence row.
- The payment submission tables are untouched: the immutability of attempts is the existing V48 guard, proved again
  by a test that mutates nothing and by the row-for-row comparison in the integration tests.

Indexes (each used by one named statement, proved by query-plan tests with sorting enabled): the unique key
`(tenant_id, submission_id)` (lookup and current read); `payment_reconciliation_due (next_attempt_at, tenant_id, id)
WHERE state = 'OPEN'` (claim); `payment_reconciliation_lease (lease_expires_at, tenant_id, id) WHERE state =
'INQUIRY_IN_PROGRESS'` (lease recovery); the evidence primary key `(tenant_id, reconciliation_id, seq)` (keyset
history); the open-count statements use the two partial indexes.

## 8. Preview and commit (D95C-6)

Preview (read-only, one disclosure audit) returns: provider code, label and the synthetic flag; a **request ID
fingerprint** (first 12 hex characters of SHA-256 over the request ID; the raw request ID and any signing material
are never shown); the submission state, version and outcome code; the previous reconciliation conclusion (state and
outcome only) if any; `READY` or `BLOCKED` with blocker codes `SUBMISSION_NOT_ELIGIBLE`, `RECONCILIATION_EXISTS`,
`PROVIDER_NOT_AVAILABLE`, `PROVIDER_KEY_UNAVAILABLE`; the fixed not-paid notice code; a `previewDigest` and its
expiry (15 to 30 minutes on the 15-minute grid) when READY. The digest binds tenant, entity, batch, submission,
submission version and state, provider, key version and expiry.

Commit repeats `expectedSubmissionVersion`, `expectedSubmissionState`, `previewDigest`, `expiresAt`,
`understandInquiryDoesNotResend=true`, `understandAcceptedIsNotPaid=true` and an `Idempotency-Key`. One transaction
takes the batch's submission lock and the reconciliation lock, re-reads, re-evaluates and creates exactly one
reconciliation (`OPEN`, version 1, attempt 0, due now) with its opening evidence, the audit row and the outbox
event. Same key and command replay; a different command under the same key is refused by the shared idempotency
service; concurrent different keys yield exactly one aggregate and a 409 `PAYMENT_RECONCILIATION_EXISTS`.

## 9. API (D95C-7)

Under the existing payment-submission resource (`/legal-entities/{legalEntityId}/payment-batches/{batchId}`):

- `POST /submission/reconciliation/preview`
- `POST /submission/reconciliation` (`Idempotency-Key`) 201
- `GET  /submission/reconciliation`
- `GET  /submission/reconciliation/inquiries?cursor&limit` (keyset, newest first, default 25, maximum 100)

Stable errors added (client-safe, parameter-free): `PAYMENT_RECONCILIATION_NOT_FOUND` 404,
`PAYMENT_RECONCILIATION_NOT_ELIGIBLE` 409 (submission not in an eligible state or moved), `PAYMENT_RECONCILIATION_EXISTS`
409, `PAYMENT_RECONCILIATION_PREVIEW_STALE` 409 (changed or expired). Existing: `PAYMENT_PROVIDER_UNAVAILABLE` 409
(provider inquiry unavailable), `PAYMENT_SUBMISSION_KEY_UNAVAILABLE` 503, `RATE_LIMITED` 429. Inquiry outcomes are
**states and allow-listed outcome codes in the read model**, not HTTP errors, because the administrator's request
succeeded when the investigation was opened: `STILL_UNRESOLVED`, `MANUAL_REVIEW_REQUIRED`, and the outcome codes
`BAD_RESPONSE` (provider response unauthenticated or invalid), `TRANSIENT_*` (inquiry temporarily unavailable),
`RETRIES_EXHAUSTED`. Error and outcome parameters carry only identifiers, versions, counts or allow-listed codes.

## 10. Audit, events and fan-out (D95C-9)

Audit actions (fail closed): `payment-reconciliation.preview`, `.create`, `.read`, `.inquiry`. Metadata is an exact
allow-list: schema version, legal entity, batch, submission and reconciliation IDs, provider code, state, version,
inquiry attempt number, bounded outcome code, view, page and result count. Never an amount, total, employee,
destination, provider reference, signature, response body or raw request ID. Internal outbox events
`payroll.payment-reconciliation.created.v1` and `payroll.payment-reconciliation.state-changed.v1` carry the
reconciliation, submission and batch IDs, state, version and outcome code. They are absent from the webhook public
mappings, the WhatsApp mappings, fan-out markers and delivery tables; tests run both real fan-out jobs as controls.

## 11. Privacy, secrets and observability (D95C-10)

The protected submission signing key and its version are reused. Logs: operation, bounded state or outcome class,
correlation ID and exception class. Metrics: `divalhr.payment.reconciliation.transitions{provider,state,outcome}` and
the gauge `divalhr.payment.reconciliation.open`. Alerts: repeated inquiry failure, stale lease,
`STILL_UNRESOLVED`, `MANUAL_REVIEW_REQUIRED` (OPS-HR-DEV.md). Sentinel tests scan PostgreSQL evidence, audit, outbox,
idempotency, errors, cursors and every service log.

## 12. Limits (D95C-11)

Buckets (subject per minute / tenant per hour): `payment-reconciliation-preview` 5 / 60,
`payment-reconciliation-write` 2 / 20, `payment-reconciliation-read` 60 / 300. Each has an exact-limit, next-window and
tenant/subject isolation test without sleeps (the existing window test pattern).

## 13. Synthetic provider (D95C-12)

`infrastructure/docker/payment-provider/server.mjs` gains `POST /v1/payment-status-inquiries` on the existing HTTPS
port. It verifies the inquiry signature with the trimmed key, checks both identifiers against the body, signs its
answer, records metadata only and exposes no port. Deterministic behaviour through the control port
(`/control/inquiry-scenario`): `accepted`, `rejected` (+ allow-listed code), `pending`, `not-found`, `unknown`,
`unsigned`, `malformed`, `rate-limit-once`, `server-error-once`, `timeout`, `drop`. A **final** answer (accepted or
rejected) is stored per inquiry ID and replayed identically; a changed inquiry (different request ID under a known
inquiry ID) is refused with 409 and no state change. Non-final answers are recomputed from the scenario. The control
port also returns the inquiry log (metadata only) and resets it.

## 14. Bilingual accessibility (D95C-13)

English and French parity for the investigation preview and confirmation, reconciliation states, inquiry history,
blockers, stable errors, the fixed warning, manual-review guidance, and empty, loading and error states. Keyboard
flow, visible focus, accessible names, axe, text spacing and reflow at 320, 390 and 640 px are tested.

## 15. Truth refresh (D95C-14)

Repository truth is reconciled with the deployed environment: hr-dev runs `c93ce0ec…`, V48 is applied, MVP-095B is
merged, deployed and accepted, Issue #187 is closed, and the Git/deployment truth table gains MVP-095B and R187-1.
The landing page and administrator home mark controlled synthetic provider submission **active** (clearly
synthetic/test-only) and reconciliation **in progress** (active only after this story is deployed and accepted).
Real-provider connectivity, settlement, paid status, accounting posting, employee payment notification, loans and
salary advances stay under "Coming next" / « Prochainement ». The pending two-line ADR 0036 clarification is
included. The backlog truth test fails CI on a stale release or capability claim.

## 16. Not included

Any real bank or operator integration; sending or resending a payment; transfer, settlement, paid status, balance
inquiry; callbacks, webhooks or inbound reconciliation; a human override that fabricates provider acceptance or
rejection; resubmission after rejection or unknown outcome; automatic or manual re-investigation after
`STILL_UNRESOLVED`; partial batches, fees, FX, multi-currency; destination verification; accounting posting;
employee notification; loans or salary advances; tenant-authored endpoints or payloads; production egress changes;
editing or deleting reconciliation evidence.
