# ADR 0042: Controlled financial-partner handoff of consented salary-advance requests (MVP-120B, Issue #206)

Status: accepted for implementation (decisions D120B-1 to D120B-16 of Issue #206 are binding; this ADR records the design
points the issue leaves to the implementation). Builds on ADR 0041 (salary-advance requests) and ADR 0037 (provider
submission, whose connector, signing and delivery-uncertainty design this story reuses). Migration: **V53**. Base: `main`
at `4b947a6cfa2135369cd2e8ac1e2710c84a2bb525`.

## 1. Boundary (read first)

DivalHR is workflow software and is not the lender. A **handoff** is the one durable, employee-authorized delivery of an
employer-confirmed salary-advance request to a **controlled synthetic financial-partner adapter** (`SYNTHETIC-TEST`). The
strongest thing the partner side can say is that it **received** the application and that a review is **under review**,
needs **more information** or was **declined**. The words approved, funded, disbursed, repaid and paid exist nowhere in
this story: not as a state, a column, a code, a locale string or an event name. No real bank, microfinance institution,
bureau, mobile-money operator, hostname, credential, logo or production egress exists. No interest, fee, term or offer is
computed, no money moves and no payroll deduction occurs.

The fixed notice of MVP-120A (`DIVALHR_NOT_LENDER`) is on every handoff view. A second fixed notice
(`DIVALHR_HANDOFF_SYNTHETIC`, "This is a controlled test handoff. No real financial partner is connected and no funds are
sent." / « Ceci est un transfert de test contrôlé. Aucun partenaire financier réel n’est connecté et aucun fonds n’est
envoyé. ») is on every handoff view and on the consent document.

## 2. Authority and separation (D120B-7)

| Operation                                                        | Caller                                                    |
| ---------------------------------------------------------------- | --------------------------------------------------------- |
| preview, authorize, read own, list own, withdraw before receipt  | the linked employee who owns the request (existing `@EmployeeSelfOperation`) |
| list, read, safe diagnostics, reconcile/retry                    | `tenant-admin` with exact `acr=urn:divalhr:loa:mfa` (`@TenantAdminOperation`) |
| signed callback                                                  | the synthetic partner actor only (HMAC key, no bearer token)  |

Managers, platform administrators and every other role have no access. Tenant, subject and correlation id come only from
the verified context (the callback derives the tenant from the stored handoff, never from the body). Unknown, malformed,
foreign and wrong-owner ids return the same safe 404 (`SALARY_ADVANCE_HANDOFF_NOT_FOUND`; the source request keeps
`SALARY_ADVANCE_REQUEST_NOT_FOUND`). The employee who authorizes, the employer reviewer who confirmed and the partner
actor are three distinct actors; the database refuses an authorizing subject equal to the confirming reviewer, and no
administrator route can create a consent or an authorization.

## 3. Eligible source request and fresh consent (D120B-1, D120B-2)

Only a request in `EMPLOYER_CONFIRMED` may start a handoff, still owned by the caller's linked employee, tenant and legal
entity. The MVP-120A consent never covers external sharing and is never reused. The employee previews the closed
handoff disclosure `SA-HANDOFF-1` (server-owned, English and French, canonical bytes, SHA-256 digest) and must affirm it
with an unchecked control. The preview binds tenant, employee, request, request version, handoff consent version, payload
digest and a 15-minute expiry (`previewDigest`). Authorization repeats request version, consent version, previewDigest and
expiry, `affirmed=true`, `understandsSyntheticTest=true`, plus `Idempotency-Key`. A changed request, consent version or
payload digest is `SALARY_ADVANCE_HANDOFF_PREVIEW_STALE`.

Stored evidence per consent: request id, consent version, language, disclosure SHA-256, consented instant and the verified
employee subject. No IP address, user agent or rendered text is stored.

## 4. Minimal payload (D120B-3)

The closed `schemaVersion: 1` payload contains only: `applicationId` (a new opaque UUID, not the request id), `requestId`,
`legalEntityRef` (an opaque per-handoff UUID derived by the Core from the legal entity id and the application id; never
the display name or the real entity id), `employmentActive` (boolean), `employmentStart`, `payrollReferenceMonth`,
`currency`, `requestedMinor`, `ceilingMinor`, `employerConfirmed` (true), `consentVersion`, `consentSha256`, `issuedAt`
and `correlationId`. Nothing else: no name, email, phone, address, identifier, destination, payslip, leave, attendance,
performance, disciplinary data, document, credential or MFA data. The canonical bytes (UTF-8 JSON, sorted keys, no
whitespace, integers only) are built once at authorization from the frozen request evidence, stored as a digest only
(`payload_sha256`, plus the shared-category list as a closed enumerated array), and rebuilt byte-for-byte for every
attempt. The canonical bytes are not persisted.

## 5. Connector, SSRF boundary and signing (D120B-4)

`SalaryAdvancePartnerGateway` is a payroll-owned port (`payroll.application`); `HttpsSalaryAdvancePartnerGateway`
(`payroll.internal`) is its only adapter and shares the transport primitives of ADR 0037 §3: exact allow-listed host and
port from operator configuration (`divalhr.payroll.partner.profiles`; one profile, `SYNTHETIC-TEST`, host
`partners.hooks.test`, port 8444, CA file mounted read-only, enabled only in the dev/hr-dev profile), HTTPS only with
hostname verification, no redirects, DNS resolved per attempt and the connection pinned to the validated address
(public global-unicast only, except the exact controlled receiver host and port), explicit connect/write/read deadlines,
a 4 KiB response cap. Tenants and employees supply no URL, header or credential.

A **separate** protected HMAC key (`secrets/core/DIVALHR_PARTNER_HANDOFF_SIGNING_KEY`, uid 10001, mode 0400, created once by
`ops/hr-dev/init-secrets.sh`, never printed, versioned in `key_version`) signs outbound deliveries. The callback key is a
third, separate secret (`DIVALHR_PARTNER_CALLBACK_SIGNING_KEY`), so a leaked delivery key cannot forge callbacks.
Outbound headers: `X-Divalhr-Application-Id`, `X-Divalhr-Key-Version`, `X-Divalhr-Timestamp` (epoch seconds),
`X-Divalhr-Signature: v1=<hex>` over `"v1\n" + timestamp + "\n" + applicationId + "\n" + canonicalBytes`. The receiver
verifies timestamp (±300 s), signature, application id against the body, payload schema, and a replay id. Headers,
signatures, payloads, amounts, consent details and response bodies are never logged.

## 6. Lifecycle and delivery semantics (D120B-5)

Delivery states (closed): `AUTHORIZED`, `QUEUED`, `SENDING`, `RECEIVED`, `DELIVERY_UNKNOWN`, `FAILED`, plus `WITHDRAWN`
(added by this ADR because D120B-1 and D120B-7 require a pre-receipt withdrawal that must win over delivery). The row is
inserted in `QUEUED`; its first two evidence events record `AUTHORIZED` and `AUTHORIZED -> QUEUED` in the same transaction.
Review states (separate column, `NULL` until receipt): `RECEIVED`, `UNDER_REVIEW`, `MORE_INFORMATION_REQUIRED`, `DECLINED`.
There is no `APPROVED`, `FUNDED`, `DISBURSED`, `REPAID` or `PAID` state, enforced by a database check.

Legal delivery transitions: `QUEUED -> SENDING | WITHDRAWN`; `SENDING -> RECEIVED | QUEUED | DELIVERY_UNKNOWN | FAILED`;
`DELIVERY_UNKNOWN -> RECEIVED | QUEUED` (only through the administrator reconcile, which needs proof of non-acceptance
for `QUEUED`) `| FAILED`; `FAILED -> QUEUED` (administrator retry, bounded); `RECEIVED`, `WITHDRAWN` terminal. Legal review
transitions: `RECEIVED -> UNDER_REVIEW`, `UNDER_REVIEW -> MORE_INFORMATION_REQUIRED | DECLINED`,
`MORE_INFORMATION_REQUIRED -> UNDER_REVIEW | DECLINED`; `DECLINED` terminal. Review only exists after `RECEIVED`.

| Observation                                                                                         | Result                                            |
| --------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| 2xx with an authenticated acknowledgement echoing the application id and a valid reference           | `RECEIVED`, review `RECEIVED`                     |
| 400/409/422 with an allow-listed reject code (nothing accepted)                                      | `FAILED` (retry only through the administrator)   |
| refused address, DNS, connect or TLS failure before any byte was written                            | back to `QUEUED` with the retry schedule (bounded) |
| any 429 or 5xx response (unsigned: no partner signs one); timeout or connection loss after bytes were written; unparsable or oversized response; 2xx without a valid acknowledgement | `DELIVERY_UNKNOWN` (never resent automatically) |
| lease expired in `SENDING` (worker crash)                                                           | `DELIVERY_UNKNOWN`                                |
| attempts exhausted (6) after transient failures                                                      | `FAILED` / `RETRIES_EXHAUSTED`                    |

A 429 or 5xx is deliberately **not** a retry signal. The request has already been written, and a partner that stored the
application and then failed (or throttled) would answer exactly like one that stored nothing, so the sender cannot tell
the two apart. Only a failure before any byte was written proves nothing was disclosed. Every other answer that is not a
signed acknowledgement or a signed, allow-listed rejection is `DELIVERY_UNKNOWN`, and the only way back to `QUEUED` is a
signed, application-bound `NOT_RECEIVED` inquiry answer (reconcile, below). A `RECEIVED` answer ends the handoff as
`RECEIVED`; an unsigned, wrong-application, malformed, timed-out or `UNKNOWN` answer changes nothing.

The application id never changes and is reused by every attempt. A duplicate application id at the receiver returns the
original acknowledgement and never a second receipt. **Reconcile** (administrator, exact MFA, `expectedVersion`,
`confirmed=true`, `Idempotency-Key`) asks the receiver for the status of the application id (a signed inquiry): an
acknowledgement moves the handoff to `RECEIVED` (no resend); a signed "never received" proof moves it to `QUEUED` (the
only case in which a resend is allowed); anything else leaves `DELIVERY_UNKNOWN` unchanged. Reconcile is bounded to 3 per
handoff and is idempotent. A withdrawal that commits before receipt wins: it takes the same row lock as the claim and the
acknowledgement, so exactly one of `WITHDRAWN` and `RECEIVED` can win; after receipt there is no withdrawal: the
request stays `EMPLOYER_CONFIRMED` because marking it `WITHDRAWN` would misrepresent an external disclosure that already
occurred (founder decision, Issue #206). The UI says future partner processes are governed by the partner agreement and
applicable law. A separate partner-cancellation request is a future story and never rewrites this history.

## 7. Callbacks (D120B-6)

`POST /public/partners/synthetic-test/callbacks` carries `{applicationId, reviewState, reasonCode, issuedAt}` and the
headers `X-Divalhr-Timestamp`, `X-Divalhr-Callback-Id` (replay id) and `X-Divalhr-Signature: v1=<hex>` over
`"v1\n" + timestamp + "\n" + callbackId + "\n" + canonicalBytes`, using the callback key. Closed reason codes only
(`ADDITIONAL_DOCUMENTS`, `CLARIFICATION`, `PARTNER_POLICY`, `SYNTHETIC_TEST_SCENARIO`); no free-form partner note exists in
the schema or the database. Verification order, all before any state read: size, content type, timestamp window,
signature (constant time), schema. Then one transaction: replay evidence insert (unique callback id), tenant derived from
the stored application, legal transition check, state and event write, audit, outbox. Unknown application ids, invalid
signatures, stale timestamps, replays and illegal transitions fail closed with the same generic problem body and leave no
state change.

## 8. API (D120B-8)

All sensitive responses are `Cache-Control: private, no-store`.

Employee: `POST /me/salary-advance-requests/{requestId}/handoff/preview`; `POST /me/salary-advance-requests/{requestId}/handoff`
(`Idempotency-Key`); `GET /me/salary-advance-requests/{requestId}/handoff`; `GET /me/salary-advance-handoffs`;
`POST /me/salary-advance-requests/{requestId}/handoff/withdraw` (`Idempotency-Key`). Administrator:
`GET /salary-advance-handoffs`; `GET /salary-advance-handoffs/{handoffId}`;
`POST /salary-advance-handoffs/{handoffId}/reconcile` (`Idempotency-Key`). Partner: the callback path above.

Stable codes: `SALARY_ADVANCE_HANDOFF_NOT_FOUND` 404, `SALARY_ADVANCE_EMPLOYER_CONFIRMATION_REQUIRED` 409,
`SALARY_ADVANCE_REQUEST_TERMINAL` 409, `SALARY_ADVANCE_HANDOFF_EXISTS` 409, `SALARY_ADVANCE_HANDOFF_CONSENT_REQUIRED` 422,
`SALARY_ADVANCE_HANDOFF_CONSENT_STALE` 409, `SALARY_ADVANCE_HANDOFF_PREVIEW_STALE` 409,
`SALARY_ADVANCE_HANDOFF_TRANSITION_NOT_ALLOWED` 409, `SALARY_ADVANCE_HANDOFF_VERSION_CONFLICT` 409,
`SALARY_ADVANCE_PARTNER_UNAVAILABLE` 409, `SALARY_ADVANCE_HANDOFF_DELIVERY_UNKNOWN` 409 (reconcile or retry refused while the
outcome is unknown), `SALARY_ADVANCE_PARTNER_NOT_ENABLED` 409, `PARTNER_CALLBACK_INVALID` 400 (also used for stale, replayed
and unknown; one generic body), `RATE_LIMITED` 429 (existing). Delivery outcomes are states and allow-listed outcome
codes in the read model, not HTTP errors. No error carries an amount, digest, signature, endpoint, credential, internal
response or employee identity.

## 9. Persistence (V53, D120B-9)

Tables (all `payroll.`, tenant-owned, composite tenant foreign keys): `salary_advance_handoff` (one per request, forever;
state, review state, version, attempt counters, payload digest, key version, lease), `salary_advance_handoff_consent`
(immutable), `salary_advance_handoff_event` (append-only evidence, one row per transition, deferred exactness constraint),
`salary_advance_handoff_attempt` (append-only), `salary_advance_handoff_ack` (immutable; at most one per handoff),
`salary_advance_handoff_review_event` (append-only), `salary_advance_handoff_callback` (replay evidence, unique callback id).
Guards and deferred triggers enforce: tenant ownership; one handoff per request; the source request is
`EMPLOYER_CONFIRMED` at insert and not withdrawn afterward unless the handoff itself is `WITHDRAWN` in the same
transaction; a consent row before authorization; authorizing subject is the employee's own linked membership and differs
from the confirming reviewer; closed transitions; immutable evidence; no approval/funding states. A request with a live
handoff cannot be withdrawn through the MVP-120A route (`SALARY_ADVANCE_HANDOFF_EXISTS`); the handoff withdrawal moves
both. Rollback (`db/rollback/V53__rollback.sql`) refuses while any row exists and never deletes evidence.

## 10. Operations and limits (D120B-4, rate limits)

Reads 60/subject/hour; employee previews and authorizations 10/subject and 100/tenant per hour; reconciliations 20/subject
and 200/tenant per hour; callbacks 600/hour per application and a global 6000/hour guard; request body 16 KiB (callback
4 KiB); list default 25, maximum 100; statement, transaction and network timeouts explicit. A bounded worker (default
off outside dev/hr-dev) claims due `QUEUED` rows with `FOR UPDATE SKIP LOCKED` and a 2-minute lease. The limiter remains
per Core API instance (disclosed). The synthetic receiver (`infrastructure/docker/partner-receiver`) runs in Compose, CI,
the AWS rehearsal and hr-dev behind the same internal network arrangement as the payment provider; it publishes no port.

## 11. Public and home surfaces (D120B-10 to D120B-15)

The landing page, administrator home and employee home are reconciled to everything delivered through MVP-120A and this
story; **Loans & Salary Advances remains "Coming next" / « Prochainement »**. A truthful "Available foundation" statement
and the bilingual **Financial Partner Program** section are added; "Become a financial partner" opens a structured email to
`contact@dival.ai`. No institution name or logo, no partner count, no rate, no "loan available", "approved", "instant cash"
or "guaranteed". A database-backed form, upload, portal, partner directory and any automated approval are out of scope.
`docs/BACKLOG.md` and the truth tests are reconciled (MVP-120A merged as PR #205, `4b947a6`, V52, accepted).

## 12. Not included

Real partners, endpoints, credentials or logos; underwriting, affordability, scoring or any DivalHR credit decision; interest,
APR, fees, terms, offers; KYC/AML; approved loan, binding offer, employee acceptance; disbursement; deduction, repayment,
collections, bureau reporting; a public application database, upload or portal; production enablement; real DRC statutory
rules.

---

# Amendment — MVP-120C (Issue #209): controlled partner lifecycle completion

Status: proposed with the MVP-120C pull request. Scope is unchanged: **SYNTHETIC-TEST partner only**, no real lender,
bank, endpoint, credential, bureau, price, approval, disbursement, repayment or payroll deduction. Everything below is
additive to sections 1–12 above (migration **V54**, rollback `db/rollback/V54__rollback.sql`).

## 13. Decisions D120C-1 to D120C-16

- **D120C-1 Closed partner-review catalogue.** `RECEIVED`, `UNDER_REVIEW`, `MORE_INFORMATION_REQUIRED`, `DECLINED`,
  `CANCELLED_BY_PARTNER`. No approved, funded, paid or disbursed status exists in the type, the schema or the contract.
- **D120C-2 Closed transitions.** `RECEIVED` → `UNDER_REVIEW`, `MORE_INFORMATION_REQUIRED`, `DECLINED`,
  `CANCELLED_BY_PARTNER`; `UNDER_REVIEW` → `MORE_INFORMATION_REQUIRED`, `DECLINED`, `CANCELLED_BY_PARTNER`;
  `MORE_INFORMATION_REQUIRED` → `UNDER_REVIEW`, `DECLINED`, `CANCELLED_BY_PARTNER`. `DECLINED` and
  `CANCELLED_BY_PARTNER` are terminal. Enforced by the domain enum, the handoff guard trigger and the review-event check.
- **D120C-3 Replay safety.** The partner event id is the callback id (`X-Divalhr-Callback-Id`). A callback whose id was
  already accepted for the same application and the same content is acknowledged (204) and changes nothing. The same id
  with different content, a transition already reached (new id, same status), a stale transition, an invalid transition
  and any step out of a terminal status are refused with the one generic `PARTNER_CALLBACK_INVALID` and change nothing.
  The generic answer is deliberate: the callback door never becomes an oracle for foreign or unknown applications.
- **D120C-4 Ordering.** The order of history is the server-controlled review sequence assigned under the handoff row lock.
  The partner timestamp (`issuedAt`) is stored, bounded by the 300 s window, and never trusted for ordering.
- **D120C-5 Signature first.** Unchanged from section 7: the timestamp format, the signature version (`v1` only; any
  other version is refused as invalid) and the HMAC are checked over the exact bytes before any business field is read.
  Nothing from the request (signature, body, identifiers) is logged. A callback never triggers an outbound request or a
  redirect.
- **D120C-6 Immutable status history.** One append-only `salary_advance_handoff_review_event` row per accepted transition,
  now also carrying the safe digest of the event id (SHA-256 of the lower-case id; the raw id stays only in the replay
  evidence), the server received timestamp and the correlation id. No personal data, amount, free text, consent,
  signature, payload or bank detail. Read through the view `payroll.salary_advance_handoff_status_history`.
- **D120C-7 Partner cancellation is not employee withdrawal.** The request stays `EMPLOYER_CONFIRMED`; the employee still
  cannot withdraw after receipt; the UI states that the review stopped and that no loan was approved and no funds were
  disbursed. `MORE_INFORMATION_REQUIRED` is informational only (no upload, reply or notification).
- **D120C-8 Evidence retention scope.** The only purgeable evidence is *delivery diagnostics*: the per-attempt rows
  (`salary_advance_handoff_attempt`: instant, closed outcome code, optional HTTP status) of a handoff that is **terminal**
  (`WITHDRAWN`, or review `DECLINED`/`CANCELLED_BY_PARTNER`) and whose history contains no `DELIVERY_UNKNOWN` (that history
  is the proof that an ambiguous delivery was not resent and is kept forever). Never purged: the request, the employer
  confirmation, the consent receipt, the current status, the status history, delivery events, acknowledgement, replay
  evidence, audit, security events, idempotency evidence.
- **D120C-9 Policy.** Disabled by default (no policy row, nothing is ever eligible). An exact-MFA tenant administrator
  configures a bounded organization policy of 30 to 3,650 days. Policies are versioned and immutable
  (`salary_advance_evidence_retention_policy`: days, effective instant, version, actor subject, created instant). The
  number is an operator choice, not a legal recommendation, and the product makes no claim about a DRC legal period.
- **D120C-10 Prospective only.** When a handoff becomes terminal, a database trigger stamps one immutable
  `salary_advance_handoff_retention` row with `retention_until` = terminal instant + days of the policy version then in
  force. A later policy change never shortens or lengthens a stamped date; handoffs that became terminal before any policy
  exist are never eligible.
- **D120C-11 Controlled purge, never automatic.** Preview lists eligible evidence (counts only), stores a short-lived
  (15 minutes) server-generated preview with a digest over the exact sorted handoff set, attempt counts and policy
  version. Confirm names the preview id and digest, re-verifies eligibility of every previewed row under row locks, and
  deletes only those previewed attempt rows. Any eligibility or policy change makes the preview stale
  (`SALARY_ADVANCE_PURGE_PREVIEW_STALE`, nothing deleted). The database refuses an attempt deletion that is not covered by
  a confirmed purge record, a previewed handoff and a stamped, elapsed `retention_until`.
- **D120C-12 Idempotent purge.** Re-confirming a completed preview replays the stored result (200, same counts, nothing
  deleted); confirming a completed preview with a different digest is `SALARY_ADVANCE_PURGE_ALREADY_COMPLETED`.
- **D120C-13 Audit.** Operations `salary-advance-handoff.partner-status` (accepted), `salary-advance-handoff.partner-status-refused`,
  `salary-advance-retention.configure`, `.purge-preview`, `.purge-confirm`, each with counts and safe identifiers only
  (never an amount, identity, consent, signature or secret). The refused-transition audit carries no request content.
- **D120C-14 Authorization.** Employee: own status and history only. Tenant administrator with exact MFA: tenant status
  and history, retention policy read/configure, purge preview and confirm. Manager: none. Callback: signed partner
  endpoint only. Unknown, foreign and unauthorized ids answer the same 404. All responses `Cache-Control: private, no-store`.
- **D120C-15 Truth.** Landing page, employee home, administrator home, capability catalogue and `docs/BACKLOG.md` state
  the "Available foundation" (employee request, employer confirmation, consented synthetic partner handoff, transparent
  partner-review status and history) and the "Not available" list (real lender, eligibility or credit decision, pricing or
  interest, approval, disbursement, repayment, payroll deduction). Loans & Salary Advances is not presented as launched;
  the Financial Partner Program stays an invitation to discuss (the application form is MVP-120D).
- **D120C-16 Rollback.** `db/rollback/V54__rollback.sql` refuses while any 120C business or policy row exists
  (`CANCELLED_BY_PARTNER` history, policy, retention stamp, preview, purge record) and otherwise restores V53 exactly.
  120A/120B rows and any genuine `DELIVERY_UNKNOWN` evidence are preserved by V54.

## 14. API additions

- `GET /salary-advance-handoffs?reviewState=` closed-catalogue filter (tenant administrator, exact MFA).
- `GET /salary-advance-retention-policy`, `PUT /salary-advance-retention-policy` (idempotent by `Idempotency-Key`).
- `POST /salary-advance-evidence-purge/preview`, `POST /salary-advance-evidence-purge/{previewId}/confirm`.
- Callback body `reviewState` gains `CANCELLED_BY_PARTNER`; no other callback change.
- Stable errors (bilingual on the client): `SALARY_ADVANCE_RETENTION_POLICY_MISSING`,
  `SALARY_ADVANCE_RETENTION_PERIOD_INVALID` (a 400 field error), `SALARY_ADVANCE_PURGE_PREVIEW_STALE`,
  `SALARY_ADVANCE_PURGE_ALREADY_COMPLETED`, `SALARY_ADVANCE_PURGE_PREVIEW_NOT_FOUND`. The partner-facing refusals share
  `PARTNER_CALLBACK_INVALID` by design (D120C-3).
