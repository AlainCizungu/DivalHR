# MVP-120F partner referral: privacy, data flow and threat model

Issue #224, ADR 0045, migration V57. Synthetic provider only: **no real partner is connected and no money moves.**

## Path

Employee browser, then Core API (`/api/v1/me/partner-referrals`), then Postgres (`integrations.partner_referral*`), then the in-process `SYNTHETIC-TEST` provider. Nothing leaves the Core process: there is no partner endpoint, no credential and no outbound network call.

## Exact data that crosses the boundary

Only the fields the employee saw in the preview and consented to. Allow-list (twelve, two optional): opaque referral reference, product reference, product name, partner name, employee legal name, verified membership email, phone (only if typed and included), employee country, preferred language, organization legal name (only if included), consent timestamp, consent wording version.

Never disclosed: salary, compensation, payroll, attendance, leave, performance, documents, national identifiers, bank or mobile-money details, deductions, credit history, medical information, dependants, manager, site, cost centre, employment history. The preview lists these.

## What DivalHR stores

- **Referral row:** reference, tenant, employee, partner, product, version, language, state, version counters, due time and lease. No personal value.
- **Disclosure:** field names, the values sealed with AES-GCM (AAD `partner-referral:<tenant>:<referralId>`), and a canonical digest. Immutable.
- **Consent:** timestamp and wording version (`PR-REFERRAL-1`). Immutable.
- **History, attempts, acknowledgement:** states, bounded outcomes and the provider receipt. Append-only. No payload, no body.
- **Audit and outbox:** identifiers, lifecycle state, field names and digests only. Never a disclosed value, a phone number, an email or a name.
- **Not stored anywhere:** the preview (it is stateless; only a digest travels), URLs or logs containing personal values, browser storage.

## Roles and flows

| Who | May | May not |
| --- | --- | --- |
| Employee | read eligible products, preview, consent and send, read and withdraw (when allowed) their own referrals | read anyone else's referral |
| Tenant administrator, manager, approver | nothing about individual referrals | route, navigation entry, API access, notification, audit view |
| Platform administrator (exact MFA) | read the operational queue and history with masked email and phone | approve, edit, resend, override status, delete, see values |
| `SYNTHETIC-TEST` provider | receive exactly the previewed disclosure | receive anything else; make any decision |

Unknown and foreign references return the same safe 404 for every caller.

## Threats and controls

| Threat | Control |
| --- | --- |
| Disclosure wider than shown | one builder for preview and commit; the digest binds the values, so any change is `PREVIEW_STALE`; the allow-list is closed |
| Consent bypass | the commit requires the affirmed consent object with the current wording version; no consent creates nothing |
| Employer learns an employee's choice | no employer route, notification or audit exposure; per-employee scoping from the verified link, never from the request |
| Replay or double submit | `Idempotency-Key`; unique `(tenant, employee, preview digest)`; the preview is single use |
| Personal values in logs, audit or errors | allow-listed recorder; refusals echo nothing; privacy scans in the tests |
| Delivered and withdrawn at once | lock order (referral row first); a claimed attempt is not withdrawable |
| Resend after acceptance or uncertainty | the claim query selects only `SUBMITTED` or a retry-due `DELIVERY_PENDING`; accepted and unknown are terminal for delivery |
| Tampering with history | immutable and append-only tables; no delete or truncate |
| Abuse | per-minute subject limits and hourly per-employee quotas; platform reads 60 per minute |

## Residual risks and limits

- The preview hourly counter is per instance (ADR 0045, section 8).
- Retention is undecided: nothing is deleted, and rollback refuses while any referral exists. A legal and privacy decision is required before production.
- A real partner needs a new story: transport, signing, credentials, a data-processing agreement, SSRF review, and the partner's own decision flow.
