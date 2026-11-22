# ADR 0044: Controlled partner accounts and product catalogue (MVP-120E, Issue #217)

Status: proposed with PR for Issue #217. Builds on ADR 0043 (partner applications, MVP-120D).

## 1. Boundary (read first)

An approved application is **not** an active partner. MVP-120E lets a platform administrator, with exact MFA, create
one controlled partner account from an application that is exactly `APPROVED_FOR_ONBOARDING`, activate it explicitly,
and publish a bilingual, versioned catalogue of information-only products for that partner. Tenant administrators and
employees can read the products that are currently active and eligible for their organization's country.

It does **not** connect the partner to any DivalHR system, share employee information, accept an application or
enrollment, originate credit, calculate deductions, move money, issue a credential or API key, store an endpoint,
call an external API, send an email, WhatsApp or SMS, or make anything purchasable. No Apply action exists.

## 2. Decisions

- **D120E-1 Source and uniqueness.** A partner is created only from an application whose status is exactly
  `APPROVED_FOR_ONBOARDING`. The application and its history are read with a share lock and never written. One
  application yields at most one partner: `UNIQUE (application_id)` and an insert guard that also compares the copied
  columns with the application. A concurrent second creation loses on the unique constraint and the service returns the
  existing partner.
- **D120E-2 Copy only the approved identity.** The partner holds legal name, trading name, registration country,
  primary and additional categories, capabilities, website and supported languages, plus the approved operating
  countries. It never receives contacts, emails, phones, licence or registration references, the privacy contact,
  checklist notes or the encrypted application payload. `application_id` is a private platform-audit link and is never
  returned to tenants or employees (nor, in this story, to anyone: platform detail links to the application by its
  `PA-` reference, which is not a database identifier).
- **D120E-3 Idempotent activation.** `POST /platform/partners/from-application/{applicationReference}` requires an
  `Idempotency-Key`. Same key and same request replays the original response; same key with a different request is
  `IDEMPOTENCY_KEY_REUSED`; a different key after the partner exists returns the existing partner (`created: false`)
  and creates nothing. Creation yields `DRAFT`; the later `ACTIVATE` transition is a second, explicit, confirmed action.
- **D120E-4 Partner lifecycle.** `DRAFT -> ACTIVE`, `ACTIVE -> SUSPENDED`, `SUSPENDED -> ACTIVE`, and
  `DRAFT|ACTIVE|SUSPENDED -> CLOSED`; `CLOSED` is terminal (database trigger). Suspension requires a reason code from
  the closed set (`COMPLIANCE_REVIEW`, `COMMERCIAL_HOLD`, `PARTNER_REQUEST`, `REGULATORY_RESTRICTION`,
  `SECURITY_CONCERN`, `PROGRAM_ENDED`, `OTHER`); `OTHER` requires a private note that is encrypted with the
  application-record cipher and never appears in audit metadata, URLs, cursors, logs or idempotent response bodies.
  Closure is confirmed and is refused while any product version is `ACTIVE`. Every transition takes a row lock,
  checks the expected `rowVersion` (409 `PARTNER_TRANSITION_INVALID` when stale) and appends an immutable event.
- **D120E-5 Products are identities with versions.** `partner_product` is the stable identity (product code unique
  within the partner, opaque `PP-` public reference, product type). `partner_product_version` holds the content and the
  lifecycle (`DRAFT`, `UNDER_REVIEW`, `ACTIVE`, `PAUSED`, `RETIRED`). A version's content is editable only while
  `DRAFT`; submitting freezes it (database trigger). Editing a product that has a live version creates the next
  draft version, never a mutation of the published one. At most one working (`DRAFT`/`UNDER_REVIEW`) and one live
  (`ACTIVE`/`PAUSED`) version exist per product. Publishing a new version retires the previous live version in the
  same transaction (history event `SUPERSEDED`). All versions stay readable to platform administrators.
- **D120E-6 Validation.** Salary advances and employee loans require monetary limits (per currency) and duration
  limits; telecom products may omit both; minimum never exceeds maximum; the end date never precedes the effective date;
  every bilingual field (name, short description, eligibility summary, disclosure) is required to submit, which
  subsumes the insurance disclosure rule; the information URL, when present, is HTTPS. Currencies are any ISO 4217 code
  at the storage level (`char(3)`); the service currently offers only `CDF` and `USD`, so adding a currency is a
  configuration change that rewrites no stored record. Countries use the MVP-120D ISO 3166-1 catalogue
  (`PartnerCountries`).
- **D120E-7 Country scope and its amendment.** A product's countries must be a subset of the partner's approved
  operating countries (database trigger). The scope can be extended only through a separately recorded
  `AMEND_SCOPE` partner transition (adds countries, appends a `SCOPE_AMENDED` event with a count only). Scope is
  never narrowed: that would orphan published versions. A closed partner cannot be amended.
- **D120E-8 Product transitions.** `SUBMIT` (`DRAFT -> UNDER_REVIEW`), `RETURN` (back to `DRAFT`), `PUBLISH`
  (`UNDER_REVIEW -> ACTIVE`, only while the partner is `ACTIVE`), `PAUSE` (reason code), `RESUME`, `RETIRE` (reason code).
  `RETIRED` is terminal. Pausing and retiring use the same reason codes as suspension, `OTHER` with an encrypted note.
- **D120E-9 Eligibility is deliberately simple.** A product is visible when the partner is `ACTIVE`, the version is
  `ACTIVE`, the organization country is among the product countries, and today is within the effective range.
  Nothing about the employee is read except the verified tenant, and nothing about the employee is sent anywhere.
  An organization without a reliable country sees no products and a safe localized explanation. Suspending a
  partner hides its products at once because visibility is computed at read time from the partner status.
- **D120E-10 Safe reads.** Tenant and employee reads return one safe shape (names and texts in both languages,
  type, countries, currencies with limits, effective dates, optional information URL and the `PP-` reference). They
  never contain partner or version identifiers, platform administrators, notes, history or application data.
  Unavailable, ineligible, unknown, paused, retired, malformed and cross-tenant references are one identical 404
  (`PARTNER_PRODUCT_NOT_AVAILABLE`).
- **D120E-11 Authorization.** Platform routes are `@PlatformScoped` (platform administrator, exact MFA), responses
  `Cache-Control: private, no-store`. Catalogue routes are tenant-scoped reads available to tenant administrators and
  employees only; a platform administrator has no tenant context there. The caller's tenant is never taken from the
  request.
- **D120E-12 Audit and privacy.** Successful platform mutations are audited with the operation, the opaque
  references, statuses and the reason code. Never in audit or outbox metadata, URLs, cursors or logs: notes, contact
  data, licence or registration references, monetary values, descriptions or disclosures, employee identifiers or
  attributes. Reads of platform lists and details are audited by reference and count only. No outbox event is emitted:
  this story delivers nothing to any partner.

## 3. Differences from earlier conventions

- ADR 0043 names transitions `.../{reference}:transition`. The MVP-120E brief specifies
  `.../{reference}/transitions` and `.../products/{code}/transitions`; the brief's paths are used.
- The brief lists products as one table family; the identity/version split (D120E-5) is the smallest design that gives
  stable public references and immutable published versions.

## 4. Data model (V56, `integrations` schema)

`partner`, `partner_category`, `partner_capability`, `partner_country`, `partner_event`, `partner_product`,
`partner_product_version`, `partner_product_country`, `partner_product_currency`, `partner_product_event`.
Idempotency uses the shared `platform.idempotency_record`. Constraints and triggers enforce application-to-partner
uniqueness and copy equality, status sets and transitions, version steps, terminal states, frozen content after
submission, country scope, one working and one live version per product, and append-only history (update, delete and
truncate are refused on every evidence table). Rollback (`db/rollback/V56__rollback.sql`) restores V55 exactly and
refuses while any partner or catalogue row exists; there is no override. No backfill: MVP-120D applications do not
yet have partners.

## 5. API

Contract first in `docs/API-SPEC.yaml`; platform operations under `/platform/partners`, safe reads under
`/catalogue`. References are opaque: `PT-` plus 16 characters (partner) and `PP-` plus 16 characters (public product),
Crockford base-32 without ambiguous letters, drawn from a secure random source. New error codes
(`PARTNER_SOURCE_APPLICATION_NOT_APPROVED`, `PARTNER_ALREADY_CREATED`, `PARTNER_NOT_FOUND`,
`PARTNER_TRANSITION_INVALID`, `PARTNER_HAS_ACTIVE_PRODUCTS`, `PARTNER_PRODUCT_NOT_FOUND`, `PARTNER_PRODUCT_INVALID`,
`PARTNER_PRODUCT_SCOPE_INVALID`, `PARTNER_PRODUCT_TRANSITION_INVALID`, `PARTNER_PRODUCT_PARTNER_INACTIVE`,
`PARTNER_PRODUCT_VERSION_STALE`, `PARTNER_PRODUCT_NOT_AVAILABLE`) carry safe parameters only and exist in English and
French. `PARTNER_ALREADY_CREATED` is reserved for a conflicting concurrent creation that cannot be resolved by
returning the existing partner.

## 6. Web

Platform (`/admin/partners`, platform administrator): ready-to-activate applications with an explicit, confirmed
creation, a partner list with status counts and a status filter, the partner detail (identity only, lifecycle actions
with a reason, an optional internal note and an in-form confirmation, additive scope amendment, product list, history),
and the product page (bilingual editor, versions, version actions, history). A standing note says a partner record is an
identity only: no credentials, connection or employee data. Confirmations are checkboxes inside the form rather than
modal dialogs, so keyboard and screen-reader behaviour is the browser's own.

Tenant administrator (`/admin/catalogue`, "Partner catalogue") and employee (`/me/catalogue`, "Benefits & offers") use one
read-only page. Every page and card says "Information only", "No application has been submitted" and "Availability and
final terms are determined by the provider". There is no apply, request, enrol or contact control; the optional
information link opens with `rel="noopener noreferrer"`. The single-product read (`getCatalogueProduct`) exists in the
contract for later use; the list already carries every field the page shows.

Landing page and role homes: Benefits & Insurance stays "Coming next" with a labelled, information-only foundation, the
landing page lists no partner or product, and each role home links only to routes that role can open.

## 7. Deliberately not decided here

Employee enrollment and application, partner portal and credentials, messaging and webhooks, eligibility by
employee attributes, pricing and commissions, retention and deletion policy for partner records, and real provider
integration.
