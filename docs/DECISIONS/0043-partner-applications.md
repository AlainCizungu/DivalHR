# ADR 0043: Partner applications and controlled onboarding (MVP-120D, Issue #215)

Status: accepted for implementation (decisions D120D-1 to D120D-16 of Issue #215 are binding; this ADR records the
design points the issue leaves to the implementation). Independent of ADR 0041 and ADR 0042: it connects nothing to the
salary-advance tables. Migration: **V55**. Base: `main` at `c047a7b15c934483ea8881d0601e9a11dcc7f6f9`.

## 1. Boundary (read first)

A **partner application** is a prospective partner's expression of interest. **Approval means only "approved for
onboarding discussions"** (`APPROVED_FOR_ONBOARDING`). It never publishes the applicant as a DivalHR partner, creates a
login or API credential, activates an integration, exposes employer or employee data, permits lending, underwriting or
payment processing, represents regulatory verification or represents endorsement. The statement is shown in English and
French on the application form ("Applying opens a conversation only. It does not create a listing, credentials, an
integration or access to any employee data" / « Candidater ouvre seulement une conversation. Cela ne crée ni
référencement, ni identifiants, ni intégration, ni accès aux données des employés »), on the landing section, and on the
administrator queue and detail of an approved application ("No listing, credentials, integration or access to employee
data was created, and none is implied." / « Aucun référencement, aucun identifiant, aucune intégration et aucun accès
aux données des employés n’a été créé, et aucun n’est sous-entendu. »). The confirmation page shows only the opaque
reference, the submission time and the next-step message (D120D-16). Capabilities, regulator names and licence references are
applicant assertions: recorded as supplied and **unverified**; the checklist is an internal workflow, not legal
verification.

Nothing in this story sends email, WhatsApp or SMS, offers applicant status lookup, a portal, uploads, contracts, a public
partner directory, credentials, pricing or any automatic retention or deletion.

## 2. Decisions

**D120D-1 Generic domain.** Applications are not stored in the payroll or salary-advance tables. They live in the bounded
`integrations` schema (a generic partner domain; the issue allows a dedicated schema "or appropriately bounded
integrations schema"), Java package `com.divalhr.core.integrations.partner`. A later story may reference an approved
partner; this one connects none.

**D120D-2 and D120D-3 Catalogues.** Closed, enforced by `CHECK` constraints and mirrored in Java and OpenAPI enums:
eight categories (one primary, at most three additional, never equal to the primary, never repeated) and twelve
capabilities (at least one). Capabilities are applicant assertions only.

**D120D-4 Lifecycle.** `SUBMITTED → UNDER_REVIEW | WITHDRAWN`; `UNDER_REVIEW → MORE_INFORMATION_REQUIRED |
APPROVED_FOR_ONBOARDING | DECLINED | WITHDRAWN`; `MORE_INFORMATION_REQUIRED → UNDER_REVIEW | DECLINED | WITHDRAWN`;
three terminal states. The database enforces it with a `BEFORE UPDATE` guard that also freezes every submitted column
(only `status`, `row_version`, `updated_at` change, the version advancing by one) and refuses any change to a terminal
application or its checklist. History is append-only and server-timestamped.

**D120D-5 Meaning of approval.** See section 1. Approval writes one history event and one audit row. No outbox event is published,
since nothing downstream consumes partner applications.

**D120D-6 and D120D-7 Fields and conditional fields.** Only the fields of the issue are accepted; the request body is
strict (unknown properties refused) and no field can carry a password, key, account number, employee data, customer
list, credit file, identity document, contract or attachment. The regulator name and licence/reference number are
required when the **primary category or any additional category** is `BANK`, `MICROFINANCE`, `INSURANCE`, `TELECOM`,
`MOBILE_MONEY_PAYMENT` or `PENSION_SAVINGS` (amendment R216-2; the first implementation looked at the primary category
only). Core enforces this independently of the web form, so a crafted request with a nonregulated primary and a
regulated additional category is refused with `400 PARTNER_APPLICATION_INVALID` (`regulatorName` and `licenceReference`
`REQUIRED`). The database constraint on the regulator name covers the primary category only (the additional categories
live in another table and the licence number is inside the ciphertext), so the service is the enforcement point for the
additional ones.

**D120D-8 Receipt.** The response carries only an opaque reference (`PA-` plus twelve Crockford base-32 characters from a
cryptographic random source), the server submission time, the status `SUBMITTED` and a bilingual next-step message
selected by the request language. The internal id never leaves the server. There is no public status lookup.

**D120D-9 Withdrawal.** No public withdrawal endpoint. The applicant writes to contact@dival.ai; a platform
administrator records `WITHDRAWN` with the single bounded reason `APPLICANT_REQUEST` after verifying the request outside
the application. Email content is never stored.

**D120D-10 Duplicate and spam protection.**

- `Idempotency-Key` is required. Only the keyed HMAC digest of the key is stored (`partner_application_submission`),
  with the digest of the normalized request; an exact replay returns the original receipt, the same key with a changed
  request is refused (`PARTNER_APPLICATION_IDEMPOTENCY_REUSED`), and concurrent same-key submissions are serialized by
  the primary key so exactly one application is created.
- A keyed digest of the normalized business email and a keyed organization fingerprint (legal name, country of
  registration and registration reference, normalized) are stored. A submission that matches an existing fingerprint is
  **accepted as an ordinary new application with an identical receipt**: the response never reveals that an application,
  email or registration reference exists. Administrators see the possible-duplicate count by fingerprint in the detail.
  `PARTNER_APPLICATION_DUPLICATE` is therefore never returned by the public route; it is reserved for internal and
  protected administration use. A public request racing a submission with the same key waits on the unique key and
  replays that receipt.
- Public rate limits (existing in-process `PublicRateLimiter`): one bucket per client address pseudonym and one bucket
  keyed by the normalized business-email digest; both reduce the identifier to an HMAC under a per-window ephemeral key.
  Raw addresses and emails are not stored in business tables, audit metadata or logs.
- The request body is capped (16 KiB), a hidden honeypot property that must stay empty is answered with the same
  receipt shape as a success (a decoy that stores nothing, so it never reveals the trap), and no external CAPTCHA service
  is used.

**D120D-11 Administration boundary.** Every administration route is `@PlatformScoped` (verified `platform-admin` with
exact MFA), `Cache-Control: private, no-store`, rate limited per subject, with opaque cursors and capped page sizes. A
tenant administrator, manager or employee is refused by the existing gate; an unknown application reference answers the
same safe 404 (`PARTNER_APPLICATION_NOT_FOUND`) as a malformed one. There is no bulk export.

**D120D-12 Checklist.** Seven checks are created `PENDING` with the application: `BUSINESS_IDENTITY`,
`REGISTRATION_REFERENCE`, `REGULATORY_INFORMATION`, `SERVICE_SCOPE`, `PRIVACY_SECURITY_CONTACT`,
`TECHNICAL_API_READINESS` (the six **mandatory** checks) and `COMMERCIAL_DISCUSSION` (advisory: it records whether a
commercial discussion is required, and does not gate approval; this is an interpretation recorded for review).
Results are `PENDING`, `SATISFIED`, `NOT_APPLICABLE`, `CONCERN`. `APPROVED_FOR_ONBOARDING` requires every mandatory
check `SATISFIED` or `NOT_APPLICABLE`, enforced in the database; `REGULATORY_INFORMATION` can never be
`NOT_APPLICABLE` when any selected category, primary or additional, is regulated. Each update records the actor, the server time, an optional
bounded internal note and an optimistic row version (`If-Match`-style `expectedVersion`); a stale version is refused,
which makes concurrent checklist updates safe. The per-check internal note is the "internal review note" of D120D-11.

**D120D-13 Data classification and encryption.** Contact name, business email, phone, registration reference, licence
number, privacy/security contact email and the internal check notes are confidential business-contact data. They are
stored only as AES-256-GCM ciphertext with an authenticated binding to the application (and check) id, using the existing
application encryption mechanism: the key derived by HKDF-SHA256 from `divalhr.integrations.encryption-key` with a
**distinct `info` string** for partner data, so the existing webhook ciphertexts and the partner ciphertexts never share
a subkey and **no new secret is introduced** (decision for review: a dedicated `divalhr.partner.encryption-key` would be
the alternative and needs hr-dev provisioning). Digests use a separate HKDF subkey and are one-way, for duplicate and
rate-limit checks only. The service refuses to start accepting applications (fails closed with a generic 503) when the
key is not configured. Confidential values never appear in URLs, query parameters, cursors, audit or outbox metadata,
logs, error bodies or analytics events. The legal name, trading name, website, regulator name, country, language, API
availability and service summary are business descriptions the applicant chose to publish and are kept in clear text for
filtering and review, and are still never copied to audit metadata or logs.

**D120D-14 Audit and history.** `partner_application_event` is the immutable history (`SUBMITTED`, `REVIEW_STARTED`,
`INFORMATION_REQUESTED`, `CHECKLIST_UPDATED`, `APPROVED_FOR_ONBOARDING`, `DECLINED`, `WITHDRAWAL_RECORDED`); a database
shape constraint ties each event type to its legal transition, and a guard enforces the contiguous sequence and that the
event records the status the application actually has. Reason codes are a closed bounded catalogue: `APPLICANT_REQUEST`,
`OUT_OF_SCOPE`, `INCOMPLETE_INFORMATION`, `REGULATORY_CONCERN`, `DUE_DILIGENCE_CONCERN`, `NOT_PURSUING`,
`CLARIFY_REGULATORY_INFORMATION`, `CLARIFY_SERVICE_SCOPE`, `CLARIFY_TECHNICAL_READINESS`, `OTHER`. The platform audit rows
(`partner-application.submit`, `.review-start`, `.request-information`, `.checklist-update`, `.approve`, `.decline`,
`.withdraw`) carry only the application reference, category codes, old and new status, checklist result codes, the actor
type and timestamps.

**D120D-15 Retention (limitation).** No legal retention period is invented and nothing is deleted automatically.
Applications and their history are immutable commercial records; the privacy-notice and consent versions are recorded
with each application so a later legal review can define archival and deletion policy. Until then the rollback script
refuses once any application exists and there is no purge.

**D120D-16 No communication.** No email, WhatsApp or SMS. The confirmation says that DivalHR will contact the submitted
business email after review. Notifications are a later story.

## 3. Data model (V55, `integrations` schema)

`partner_application` (frozen submission, mutable `status`/`row_version`/`updated_at` only),
`partner_application_category` (additional categories), `partner_application_capability`,
`partner_application_consent` (privacy-notice and consent versions, locale, acceptance time),
`partner_application_check` (the checklist), `partner_application_event` (immutable history),
`partner_application_submission` (idempotency evidence). A deferred constraint trigger makes a submission complete at
commit: consent, at least one capability, an additional category different from the primary one, the seven checklist
rows and the `SUBMITTED` event. No `GRANT` or `REVOKE` is issued; authority is in the triggers. The rollback
(`db/rollback/V55__rollback.sql`) restores V54 exactly and refuses once any partner-application row exists.

## 4. API (contract in `docs/API-SPEC.yaml`)

Public: `POST /api/v1/public/partner-applications` (`Idempotency-Key`, strict body, `Cache-Control: no-store`).
Platform (exact MFA, `private, no-store`): `GET /api/v1/platform/partner-applications` (filters: status, category,
capability, country, submission period; opaque cursor, capped page), `GET …/{reference}`, `GET …/{reference}/history`,
`PUT …/{reference}/checklist/{checkCode}`, `POST …/{reference}:transition`. Stable bilingual error codes:
`PARTNER_APPLICATION_INVALID`, `_RATE_LIMITED`, `_IDEMPOTENCY_REUSED`, `_NOT_FOUND`, `_TRANSITION_INVALID`, `_TERMINAL`,
`_CHECKLIST_INCOMPLETE`, `_DUPLICATE`, `_CONSENT_REQUIRED`.

## 5. Web (implementation notes)

Routes: the public bilingual form at `/partners/apply` (public frame, language switcher; reached from the landing
section "Partner with DivalHR" / « Devenir partenaire de DivalHR »); the platform queue at `/admin/partner-applications`
and detail at `/admin/partner-applications/:reference`, both behind the platform-admin route guard and a navigation
item that only a platform administrator sees (tenant administrators, managers and employees get neither the item nor the
page, and no request is made). The Core API stays the authority on every call.

The form uses the generated client only. It holds values in component state, so an ordinary validation error (client or
server) keeps what was typed; nothing is written to the address bar. Browser storage holds exactly one thing: the **safe
receipt** (amendment R216-4), in `sessionStorage` under `divalhr.partner.receipt`, with four fields only (opaque
reference, submission time, status, next-step code). Because the story sends no email and has no status lookup, a reload
of the same tab session restores the confirmation from it. Form fields, contact data, regulatory values, consent data,
the honeypot and internal review notes are never stored, in either storage area; every read is validated and anything
that is not a four-field receipt is discarded. The confirmation offers "Copy reference" and "Start another application"
(which clears the stored receipt and shows an empty form). One `Idempotency-Key` is minted per distinct payload: a retry
of the same payload reuses it, any edit or a reused-key refusal mints a new one. The honeypot `faxNumber` is an
off-screen, `inert`, `aria-hidden`, `tabindex="-1"`, `autocomplete="off"` input that is not part of the form state: it is
read from the DOM only when submitting (so a bot that fills it is sent to the server's identical decoy success) and is
never redisplayed or persisted. Regulatory fields are shown, and required, when the primary category or any additional
category is regulated. Countries are the assigned ISO 3166-1 alpha-2 codes (amendment R216-1), named through
`Intl.DisplayNames` in the interface language; up to 30 countries of operation may be selected (the contract limit). The
privacy-notice and consent identifiers sent are `PARTNER-PRIVACY-1` and `PARTNER-APPLICATION-CONSENT-1` (amendment
R216-3).

Review notes are typed into a form and sent in the request body only: never in a URL, analytics event, browser storage or log,
and the idempotent response body never carries one (Core). Approval is offered only when `allowedTransitions` contains it
and `approvalBlockedBy` is empty, behind an explicit confirmation that repeats what approval does not create. All
`PARTNER_APPLICATION_*` and shared platform codes render as finished EN/FR sentences; English and French parity is checked
by the localization parity test and by tests that every enum value and error code has a message in both languages.

Open items for review: the form has no CAPTCHA by decision D120D-10 and relies on the honeypot, per-IP and per-email
limits; and the queue keeps filters in memory, so a reload resets them.

## 6. Deliberately not decided here

Notification of the applicant, applicant status lookup, a partner portal, document upload, contracts, credentials,
a public directory, the legal retention period, and any connection of an approved partner to a product. Each is a later
story with its own decision.

## 7. Amendments after architecture review (PR #216)

**R216-1 Country catalogue.** The curated 22-country list is replaced by one authoritative Core catalogue: the 249
assigned ISO 3166-1 alpha-2 codes in `apps/core-api/src/main/resources/catalogues/iso-3166-1-alpha-2.txt`, read by
`PartnerCountries`. Core refuses any other value (unknown, lower case, `OTHER`, user-assigned or exceptionally reserved
codes such as `XK`, `EU`, `UN`) with `400 PARTNER_APPLICATION_INVALID` and the constraint `FORMAT` on the field; the
value is never echoed. There is no `OTHER` country. The web catalogue
(`apps/web/src/features/partners/countryCodes.generated.ts`) is generated from the same file by
`scripts/ops/partner-countries.mjs` and `scripts/ops/partner-countries.test.mjs` fails when it drifts, when the file is not
the 249 sorted unique codes, or when a code has no English or French name in `Intl.DisplayNames`. No country name is
stored or hand-translated: each client derives names with `Intl.DisplayNames`. A Core test checks the catalogue against
the JDK's `Locale.getISOCountries()`.

**R216-2 Regulatory fields.** See D120D-6 and D120D-7 above: the primary category or any additional category makes the
regulator name and licence reference mandatory, in Core and in the web form, and the `REGULATORY_INFORMATION` check can
never be `NOT_APPLICABLE` in that case.

**R216-3 Consent provenance.** `2026-10` is not an identifier of any published notice and is no longer recorded. The
identifiers are stable semantic constants: `PARTNER-PRIVACY-1` (the privacy notice shown above the acknowledgment,
`partners.notice.*`) and `PARTNER-APPLICATION-CONSENT-1` (the acknowledgment text, `partners.apply.fields.acknowledgment.*`),
in English and French. Core accepts exactly these two values (`PartnerConsentVersions`); any other client-supplied value
is refused with `400 PARTNER_APPLICATION_INVALID` (`FORMAT`) and is not recorded; the contract restricts both properties
to a one-value enumeration. A web test pins each identifier to a digest of the exact bilingual wording, so editing the
wording fails the test until a new identifier is introduced in Core, the contract, the web constants and this ADR. The
wording of the notice is product copy that the founder should confirm before any production use.

**R216-4 Safe receipt.** Described in section 5.

**Accepted disclosed decisions (architecture review).** Reusing the integrations encryption key is accepted because
partner data uses a domain-specific authenticated context and the crypto tests prove that ciphertext cannot be moved
between contexts. `COMMERCIAL_DISCUSSION` stays advisory. A possible duplicate is accepted as a new application (the
public behaviour stays non-enumerating and rate-limited). The signed cursor may contain the internal UUID and clients
treat it as opaque. `PARTNER_APPLICATION_DUPLICATE` stays reserved and unused publicly. The honeypot receipt stays
database-free. No screenshots are a merge gate.
