# ADR 0046: Privacy governance, consent retention and employee data-rights foundation (LAUNCH-001, Issue #226)

Status: proposed with the PR for Issue #226. Builds on ADR 0045 (consented partner referral). This is a governance framework, not legal advice or a certification.

## 1. Boundary (read first)

DivalHR can now hold the retention rule of each kind of partner-referral data, apply a reviewed rule to a referral, honour a legal hold, receive an employee's data-rights request, give the employee their own access package, and carry out a previewed, confirmed minimization or erasure with value-free evidence.

- **No invented legal rule.** The product ships no retention duration for any country. A country policy is entered by an operator from a reviewed source, stays a draft until a governance checklist is complete, and only an approved version can become active. Without an active reviewed policy a referral stays `POLICY_PENDING` and nothing can be purged.
- **Scope is MVP-120F partner-referral data only.** Payroll, payments, salary advances, attendance, leave, documents and employee master data are untouched.
- **No automatic deletion.** There is no scheduler, timer, background job or database event that deletes anything. Every purge is an operator action.
- **The employer stays out.** Tenant administrators, managers and approvers have no route, navigation entry, API access, notification or audit trail that reveals a private referral choice or a privacy request.
- **No public claim.** The public landing page and role homes are unchanged. No screen says that DivalHR "complies with" or is "certified for" any law.

## 2. Decisions

- **L1 No invented legal rule.** No seed, default, migration value or test fixture carries a real country duration. Tests use clearly synthetic policies for a synthetic country.
- **L2 Initial scope.** Four data classes: `PARTNER_REFERRAL_DISCLOSURE`, `PARTNER_REFERRAL_CONSENT`, `PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS`, `PARTNER_REFERRAL_LIFECYCLE_EVIDENCE`, each with exactly one purpose. Actions are `HOLD`, `MINIMIZE`, `ERASE`.
- **L3 Country-aware policy packs.** One policy version per country and effective period, exactly one rule per class, a duration (1 to 36500 days) or none for `HOLD`, counted from referral creation or closure. A rule set is refused when it contradicts itself: an `ERASE` of consent needs the disclosure and diagnostics purged no later than the consent from the same trigger, and lifecycle evidence can never be erased.
- **L4 Governance lifecycle.** `DRAFT` → `APPROVED` → `SCHEDULED` → `ACTIVE` → `RETIRED`, with a closed transition table in Core and in the database. A draft needs an eight-item checklist plus a review reference before approval; only a future-dated version can be scheduled; one active version per country and period; an approved, scheduled, active or retired version is immutable.
- **L5 Existing and new referrals.** Existing referrals are backfilled `POLICY_PENDING`. A new referral is stamped with the active version for its organization's country at creation, or stays pending. A stamped referral keeps the rules it was stamped with: later policy changes never shorten or rewrite a stamped decision. Moving a pending referral to a stamp is a previewed, confirmed policy application.
- **L6 Legal or privacy hold.** A hold has a reason code, a review date, an optional external reference and a sealed internal note. Holds are append-only; releasing one is a new event. Any active hold blocks every purge of that referral.
- **L7 Employee data-rights requests.** `ACCESS`, `ERASURE`, `RESTRICTION`, `OBJECTION` with an eight-state lifecycle, one open request per employee, type and referral, five creations per employee per day, and an idempotent cancel. The platform moves a request along a closed table; a refusal or partial approval requires a bounded reason code.
- **L8 Access package.** For an approved `ACCESS` request the employee downloads a deterministic JSON package of only their own partner-referral data, stored encrypted (AAD `privacy-export:<tenant>:<requestId>`), valid for seven days, at most three generations per request per day, with a manifest SHA-256 that equals the `X-Content-SHA256` header. No email attachment is ever sent.
- **L9 Minimization and erasure.** A purge is a preview (what each class would do, no personal value, a digest) followed by an exact-MFA confirmation that carries an idempotency key, revalidates under locks, deletes in a fixed order (disclosure, diagnostics, consent last) and writes immutable value-free evidence. `MINIMIZE` leaves a value-free residue (counts and fixed labels); `ERASE` leaves none. The database refuses a purge item that is not closed, has no stamped rule, is on hold, or whose duration has not elapsed.
- **L10 Privacy boundary.** Platform reads and mutations need a platform administrator with exact MFA. Employee routes serve only the caller's own requests: another employee's reference and an unknown one return the same not-found. Tenant administrators see nothing.
- **L11 Notifications.** None. No email, push or in-product notification is sent by this story.
- **L12 Retention of the governance evidence itself.** Policy history, holds, requests, exports metadata, purge evidence and residue are append-only and are never purged by this story.

## 3. Rate limits (fixed windows, deterministic tests)

Employee requests 5 per employee per day, employee reads 60 per subject per minute, platform reads 60 per subject per minute, platform mutations 20 per subject per hour (and 20 per minute at the edge), package generation 3 per request per day, purge preview 10 per administrator per hour, purge confirmation 5 per administrator per hour. The limits are held by the Core instance and driven by an injectable clock; they are never weakened for tests. An excess is `429 PRIVACY_RATE_LIMITED` with `Retry-After`.

## 4. Data model (V58, `integrations` schema)

`privacy_policy` (+ `_rule`, `_checklist`, `_event`) with lifecycle, immutability and overlap guards; `privacy_referral_stamp` (+ `_rule`) with the country and the stamped rules; `privacy_hold` (+ `_event`); `privacy_request` (+ `_event`); `privacy_export` (+ `_event`); `privacy_preview` (+ `_item`); `privacy_policy_application` (+ `_item`); `privacy_purge` and `privacy_purge_item` with a guard; `privacy_referral_residue` with allow-listed keys. `partner_referral_evidence_guard()` replaces the four V57 immutability triggers so evidence rows can be deleted only inside an executing purge with a matching guarded item (session setting `divalhr.privacy_purge`); consent is deleted last. `db/rollback/V58__rollback.sql` refuses while any governance evidence exists and otherwise restores V57 exactly.

## 5. API

Twenty operations under `/api/v1/me/privacy/...` and `/api/v1/platform/privacy/...` (see `docs/API-SPEC.yaml`) and twenty-three `PRIVACY_*` error codes in English and French. Every write is idempotent (`Idempotency-Key`; a reused key with a different request is `PRIVACY_IDEMPOTENCY_REUSED`), every response is `Cache-Control: private, no-store`, and bodies are parsed strictly (unknown fields, wrong types and over-long text are refused with field-only problems).

## 6. Web

My space → Privacy requests (employees) and Platform → Privacy governance (platform administrators), English and French, usable at 320, 390 and 640 px. The platform screen states that it is a governance framework, not legal advice or a certification. State is held in memory only.

## 7. Known limitations

- Lifecycle evidence (`MINIMIZE` or `HOLD` only) deletes nothing in this increment; a purge leaves only a residue event count.
- A policy application for a country with several active versions creates one application per version; the result reports the first application and the total applied.
- Rate-limit counters are per Core instance; a restart resets them.
- Legal content of any policy is the operator's responsibility: see the legal decision worksheet.
