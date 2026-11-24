# ADR 0045: Employee partner-product discovery and consented referral (MVP-120F, Issue #224)

Status: proposed with the PR for Issue #224. Builds on ADR 0042 (controlled partner handoff), ADR 0043 (partner applications) and ADR 0044 (partner accounts and catalogue).

## 1. Boundary (read first)

An employee can discover an approved partner product that is available to their organization's country, see exactly what DivalHR would disclose, consent explicitly, and send one controlled referral. DivalHR records the referral and the truth about its delivery.

A referral is an introduction only. It is **not** an application, an enrollment, a request for credit or insurance, or a decision of any kind.

- **No real partner.** Delivery goes only to the in-process `SYNTHETIC-TEST` provider. There is no partner endpoint, no credential, no signing key for a real party and no outbound network call in this story. The database enforces `partner_code = 'SYNTHETIC-TEST'`.
- **No money movement.** DivalHR decides no eligibility, underwriting, price, coverage or credit; calculates no advance or repayment; creates no payroll deduction; enrols no insurance or benefit; activates no telecom product; moves no money; and marks nothing approved, funded, active or paid. No partner decision or callback is accepted.
- **The employer stays out.** A tenant administrator, manager or approver has no route, navigation entry, API access, notification or audit trail that reveals an individual referral or the product chosen.
- **No public claim.** The public landing page and the role-home progress panels are unchanged. The commercial truth refresh is a separate pre-launch story.

## 2. Decisions

- **D120F-1 Eligible catalogue only.** An employee reads only an `ACTIVE`, currently effective product version of an `ACTIVE` partner that is offered in the employee's country (the organization's country, there being no separate employee country). Anything else (unknown, malformed, foreign, paused, retired, future-dated) is the same `404 PARTNER_REFERRAL_PRODUCT_UNAVAILABLE`. Reads are `Cache-Control: private, no-store`.
- **D120F-2 No category shortcuts.** The eight partner categories of MVP-120D/E use one flow. A category may change explanatory copy, never the disclosed data.
- **D120F-3 Preview, then commit.** `POST /me/partner-referrals/preview` is stateless and read-only. It returns every field that would be disclosed with its source and purpose, and a digest. The digest is an HMAC-SHA256 (`PartnerCrypto.digest`) over the employee, organization, partner, product version, disclosure values, language, consent wording version and the expiry, so a client cannot extend an expiry. The preview lives 15 minutes. Commit rebuilds the disclosure from the live records and refuses an expired (`PARTNER_REFERRAL_PREVIEW_EXPIRED`), changed (`PARTNER_REFERRAL_PREVIEW_STALE`) or already used (`PARTNER_REFERRAL_PREVIEW_ALREADY_USED`) preview. No personal value is placed in a URL, log, audit or outbox record, or error body.
- **D120F-4 Minimum disclosure.** Exactly twelve allow-listed fields exist, two of them optional: `REFERRAL_REFERENCE`, `PRODUCT_REFERENCE`, `PRODUCT_NAME`, `PARTNER_NAME`, `EMPLOYEE_LEGAL_NAME`, `EMPLOYEE_EMAIL` (the verified membership email), `EMPLOYEE_PHONE` (only if typed and included), `EMPLOYEE_COUNTRY`, `PREFERRED_LANGUAGE`, `ORGANIZATION_LEGAL_NAME` (only if shown and consented), `CONSENT_TIMESTAMP`, `CONSENT_VERSION`. The preview also lists what is never shared (salary, compensation, payroll, attendance, leave, performance, documents, national identifiers, bank or mobile-money details, deductions, credit history, medical information, dependants, manager, site, cost centre, employment history). There is no "all my data" permission. A missing legal name or verified email is a preview blocker that carries no digest.
- **D120F-5 Privacy boundary.** The employee reads only their own referrals; unknown and foreign references return the same `404 PARTNER_REFERRAL_NOT_FOUND`. Platform administrators with exact MFA read an operational view with a masked email (`a***@domain`) and a masked phone (`+243*****789`) and no free text. Audit and outbox records carry identifiers, lifecycle state, field names and digests only.
- **D120F-6 Lifecycle truth.** States: `SUBMITTED`, `DELIVERY_PENDING`, `DELIVERED`, `DELIVERY_UNKNOWN`, `DELIVERY_FAILED`, `WITHDRAWN`, with a closed transition matrix enforced by a database CHECK and guard triggers. History is append-only. Withdrawal is allowed from `SUBMITTED`, from `DELIVERY_PENDING` while no attempt is in flight, and from `DELIVERY_FAILED`; never from `DELIVERED` or `DELIVERY_UNKNOWN`, and never while an attempt holds a lease. Nothing is hard-deleted.
- **D120F-7 Synthetic delivery only.** A claim transaction (`FOR UPDATE SKIP LOCKED`, 2-minute lease, attempt count and event recorded) is followed by a pre-send liveness check, the send through `ReferralGateway` **outside** any transaction, and a resolve transaction that records the attempt, the acknowledgement (accepted only), the state move and the event atomically. Outcomes: accepted becomes `DELIVERED`; rejected becomes `DELIVERY_FAILED`; an uncertain answer, a gateway exception or an expired lease becomes `DELIVERY_UNKNOWN` and is **never resent**; a certain non-delivery is retried after 1 minute, 5 minutes, 30 minutes, 2 hours and 12 hours with no jitter, then fails (`RETRIES_EXHAUSTED`). Delivery is at least once before acceptance and never after it. The payload is exactly the previewed disclosure.
- **D120F-8 No decision in DivalHR.** The receipt and status pages state in English and French that DivalHR makes no decision and that no money moves. The schema has no state that means approved, eligible, enrolled, funded, active or paid.
- **D120F-9 Idempotency and concurrency.** Commit and withdrawal require an `Idempotency-Key`. A completed command replays exactly (with `Idempotent-Replayed`) without revalidation, key use or provider access. A key reused with different input is `409 PARTNER_REFERRAL_IDEMPOTENCY_REUSED`. A unique `(tenant, employee, preview digest)` makes concurrent commits of one preview create one referral. **Lock order: the `partner_referral` row first, then its children.** Every guard that touches a child locks the referral row first, so withdrawal, claim and acknowledgement serialize on one lock and a referral can never be both delivered and withdrawn.
- **D120F-10 Retention.** No automatic deletion. Consent evidence and history are durable. Any retention or erasure policy needs a separate legal and privacy decision. `db/rollback/V57__rollback.sql` refuses while any referral, disclosure, consent, attempt, acknowledgement or event row exists, with no override.

## 3. Cross-module access

The module reads other modules only through `platform.*` ports: `EmployeeAccessLinks.linkedEmployee` (the caller's own active link, a mandatory-transaction call, so employee paths never use a read-only transaction), `EmployeeLegalNames`, `MembershipContacts` (verified email), `OrganizationDirectory` (legal name and country), `AuditRecorder` and `OutboxWriter`. It reads partner and product rows of the same `integrations` schema through `PartnerReferralRepository`. It never reads payroll, attendance, leave, compensation, documents or identifiers.

## 4. Data model (V57, `integrations` schema)

| Table | Role |
| --- | --- |
| `partner_referral` | identity and current state; the only mutable table (state, row version, attempt count, due time, lease) |
| `partner_referral_disclosure` | immutable manifest: allow-listed field names, AES-GCM ciphertext (AAD `partner-referral:<tenant>:<referralId>`), canonical digest |
| `partner_referral_consent` | explicit consent evidence and wording version |
| `partner_referral_event` | append-only status history (events equal row version) |
| `partner_referral_attempt` | one row per finished delivery attempt: bounded outcome, no payload, no body |
| `partner_referral_ack` | the provider receipt, at most one |

Database invariants: composite tenant foreign keys; `partner_code = 'SYNTHETIC-TEST'`; one referral per preview digest and employee; a referral is recorded `SUBMITTED` with its disclosure, consent and first event in one transaction by the employee's own active linked subject for a product version that is active and effective in the employee's country; closed transitions each with one event; a deferred completeness check (events equal row version, attempts equal attempt count minus the one in flight); immutability and no delete or truncate on every child.

## 5. API

Ten operations, defined first in `docs/API-SPEC.yaml` and checked by `ApiContractDriftTest`, with a regenerated `packages/api-client`.

- **Employee:** `GET /me/partner-products`, `GET /me/partner-products/{productId}`, `POST /me/partner-referrals/preview`, `POST /me/partner-referrals` (201), `GET /me/partner-referrals`, `GET /me/partner-referrals/{referralId}`, `POST /me/partner-referrals/{referralId}/withdraw`.
- **Platform (exact MFA):** `GET /platform/partner-referrals`, `GET /platform/partner-referrals/{referralId}`, `GET /platform/partner-referrals/{referralId}/history`.

Bodies are strict and bounded, cursors are opaque and signed, errors are RFC 7807 with the closed `PARTNER_REFERRAL_*` catalogue (eleven codes, complete English and French), and every response is `private, no-store`. No personal value is sent in a query string.

**Limits.** Per-subject per-minute buckets (`partner-referral-read` 60, `-preview` 20, `-commit` 5, `-withdraw` 10, `-platform-read` 60) and per-employee hourly quotas of 20 previews, 5 commits and 10 withdrawals (`PartnerReferralQuotas`). Commit and withdrawal quotas are counted from committed rows, so they hold across instances and restarts and never count a replay or refusal. The preview counter is held by the instance (see section 8). Production and hr-dev limits were not changed for browser tests.

## 6. Web

My space gains "Partner offers", the product page, the exact preview with an initially unchecked consent, the confirmation (opaque reference, time, next step, disclaimer), "My partner requests" with detail and history, and a confirmed withdrawal when allowed. The platform queue (`Partner referrals`) is read-only: no approve, edit, resend, override or delete. Tenant administrators have no route or navigation entry. English and French have key parity. The phone is the only typed value; a refusal preserves what was typed; nothing is kept in the URL or browser storage.

## 7. Evidence map

| Decision | Enforced in | Proven by |
| --- | --- | --- |
| D120F-1 | `PartnerReferralRepository.eligibleIdentity`, catalogue service | `PartnerReferralDiscoveryIntegrationTest.anEmployeeSeesOnlyTheProductsAvailableInTheirOrganizationsCountry`, `onlyAnEmployeeHasARouteHere` |
| D120F-3 | `PartnerReferralPreviewService`, `PartnerReferralService.submit` | `aChangedExpiredOrReusedPreviewIsRefused`, `aMissingVerifiedEmailIsABlockerAndCarriesNoDigest` |
| D120F-4 | `PartnerReferralDisclosures`, `PartnerReferralDomain.Field` | `theAllowListedDisclosureIsExactAndShowsWhatIsNeverShared`, `aConsentedSubmissionDisclosesExactlyThePreviewAndStoresNothingInTheClear`, browser journey 1 (exact field set) |
| D120F-5 | `MyPartnerReferralController`, `PlatformReferralService`, `@PlatformScoped`, `PartnerReferralRecorder` | `onlyTheOwningEmployeeCanReadAReferralAndEveryoneElseGetsTheSameNotFound`, `PartnerReferralPlatformIntegrationTest`, browser boundaries |
| D120F-6 | V57 CHECK and guard triggers, `ReferralStore` | `PartnerReferralMigrationIntegrationTest`, `aReferralCanBeWithdrawnOnceBeforeDeliveryAndNeverAfterwards` |
| D120F-7 | `PartnerReferralDelivery`, `SyntheticReferralProvider`, `PartnerReferralDeliveryStore` | `PartnerReferralDeliveryIntegrationTest` (once and exact; retry; bounded; uncertain never repeated; rejected; withdrawn never sent; claimed not withdrawable) |
| D120F-8 | schema (no such state), page copy, `ReferralNotice` | landing and role homes unchanged; browser journeys |
| D120F-9 | `IdempotentOperation`, unique preview key, lock order | `anIdenticalRetryReplaysExactlyAndAReusedKeyWithDifferentInputIsRefused`, `concurrentCommitsOfOnePreviewCreateOneReferral`, `aClaimedAttemptCannotBeWithdrawnAndAnExpiredLeaseIsUncertainAndNeverSent` |
| D120F-10 | `V57__rollback.sql` | `theRollbackRefusesOnceReferralsExistAndOtherwiseRestoresV56Exactly` |
| Web and accessibility | `apps/web/src/features/partners/*Referral*` | `partner-referral.spec.ts` (two journeys, no retry), unit tests, axe at 320, 390 and 640 px |

Mutation evidence (remove consent, widen disclosure, expose employer access, allow withdrawal after uncertain delivery, resend an uncertain referral) is recorded in section 9 once produced.

## 8. Deviations and known limitations

- **Preview quota is per instance.** The hourly preview count is held in memory because a preview writes nothing; the per-minute subject limit is shared. A multi-instance deployment could allow up to the quota on each instance. Commit and withdrawal quotas do not have this limit.
- **Wall-clock rate-limit tests.** Fourteen older rate-limit tests elsewhere still use the wall clock; the new quotas are deterministic.
- **Withdrawal while leased.** A referral whose attempt is in flight is not withdrawable (stricter than the story text, which lists `DELIVERY_PENDING`): a claimed attempt may already be on the wire. Before the lease is taken it is withdrawable.
- **Expired lease means unknown.** A lease that expires is treated as an uncertain delivery and is never resent.
- **Employee country** is the organization's country.
- **Single synthetic mode switch.** `divalhr.integrations.partner-referral.synthetic-mode` (`ACCEPT` by default) exists only for tests and diagnosis.

## 9. Mutation evidence

To be recorded with the focused gates (see the PR completion report).

## 10. Deliberately not decided here

A real partner connection, a partner decision or callback, any decision on eligibility, price, coverage or credit, notifications to the employee or employer, retention and erasure, and the public commercial wording.
