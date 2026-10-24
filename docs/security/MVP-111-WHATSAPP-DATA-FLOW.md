# MVP-111 WhatsApp privacy, data flow and threat model

Scope: the WhatsApp Business Cloud API connector (ADR 0029). Nothing here involves a real Meta
account; acceptance uses the synthetic provider.

## Path

1. A tenant administrator (exact MFA, active membership) connects the connector and registers
   recipients in the web application. Core stores the sealed token, sealed numbers, masked numbers,
   opt-in attestations and template names.
2. A business event (leave decision, timesheet approval, attendance correction, payroll lock or
   export) commits to the platform outbox. The messaging fan-out copies the event id once into a local
   ledger and creates one delivery per subscribed, opted-in recipient. **No event payload is read**:
   only the event type and the resource id decide the delivery.
3. A worker opens the sealed token and number in memory, calls Meta's Graph API over TLS with a
   template message (see below), and records the closed outcome.
4. Meta sends signed delivery-status callbacks to the public callback door. They are verified and
   applied to the matching delivery.
5. The recipient taps the button and signs in to DivalHR; the page enforces the person's own
   authorization.

## Exact data that crosses the boundary

| To Meta | Value |
| --- | --- |
| Credential | The system-user token, in the `Authorization` header only. |
| Recipient | The E.164 number (digits) of a person who opted in. |
| Template | The template name and the language code (`en` or `fr`). |
| Body parameter | At most one generic label from a closed list (for example "Leave decision"). |
| Button parameter | An allow-listed page path without its leading slash (for example `admin/leave-approvals`). |

**Never sent:** names, employee identifiers, dates, hours, amounts, pay, reasons, free text, the tenant
name, ids of any DivalHR object, or a token in a link. The link only names a page; the page needs a
sign-in.

| From Meta (callback) | Read | Stored |
| --- | --- | --- |
| Provider message id | yes (allow-listed shape) | on the delivery (needed to match) |
| Phone-number id | yes (must equal the connector's) | not stored again |
| Status (`sent`, `delivered`, `read`, `failed`) and time | yes | status, bounded time |
| Error code (failed) | yes, numeric | reduced to a closed class |
| Everything else, including inbound messages, contacts, profile names and message text | **not read** | **not stored** |

## What DivalHR stores

- **Token:** AES-256-GCM ciphertext only (HKDF subkey; AAD binds provider, tenant, connector and
  credential version). One current credential; the previous ciphertext is erased on rotation.
- **Recipient number:** ciphertext (AAD binds tenant, connector, recipient), a keyed HMAC digest (to
  forbid duplicates without a readable column) and a masked display form. Disabling a recipient keeps
  the row (no hard delete) but stops all sends.
- **Opt-in:** source, confirmation instant, attesting administrator.
- **Evidence:** deliveries, attempts (closed failure class, status code, duration) and callbacks
  (status, time) are append-only. Audit and outbox lifecycle events carry ids, versions and closed
  values only.
- **Never stored:** response bodies or headers from Meta, message text, template parameters, URLs,
  provider error text.
- **Retention:** no purge job exists in MVP-111 and the tables forbid delete, so evidence is kept; recipient removal in the
  sense of erasure is an operator action outside the MVP (see Residual risks).

## Roles and flows

| Party | Role | Basis |
| --- | --- | --- |
| The customer (tenant) | Controller of the recipients' data and of the decision to notify | Its own opt-in and notice to recipients |
| DivalHR | Processor: stores and sends on the customer's instruction | Customer agreement |
| Meta (WhatsApp) | Sub-processor, receives the recipient number and the generic message | Meta's terms; cross-border transfer needs review before production |

## Threats and controls

| Threat | Control |
| --- | --- |
| A non-administrator, or an administrator without MFA, manages the connector | `@TenantAdminOperation`: exact MFA, active membership; denials audited |
| Cross-tenant access to a connector, recipient or delivery | Tenant from the verified token only; indistinguishable 404 for unknown, malformed and foreign ids; tenant-consistent foreign keys |
| Token disclosure | Accepted on connect/rotate only; never returned, logged, audited or placed in the idempotency store (keyed digests); sealed at rest; `toString` of requests and records masked |
| Number disclosure | Sealed at rest, masked in every response, absent from logs, audit and metrics |
| Database read exposes secrets | Ciphertext only; the key is a protected file outside the database and backups of the database |
| Key loss or substitution | Documented as non-rotatable in place; AAD makes a swapped ciphertext unreadable |
| SSRF through the Graph URL | Origin and version are operator configuration validated at start-up; production accepts only `https://graph.facebook.com`; the synthetic provider is allowed only in development/test at one exact host and port; no redirects, no proxy |
| Forged callback | HMAC-SHA256 over the exact bytes with the app secret, constant time, checked before any parsing |
| Callback replay or reordering | Duplicates recorded once; statuses monotonic; a lower status never moves state back; unknown messages get a neutral `200` |
| Callback for another connector's message | Match requires message id **and** the connector's stored phone-number id |
| Callback flood or oversized body | 64 KiB bound, provider-wide ceiling after the signature check, ingress rate limit required |
| Callback used to inject data | Allow-listed fields and bounded shapes only; inbound messages ignored; nothing from the body is stored except the closed status |
| Consent bypass | Opt-in attestation required on create and on change of number or consent; disabled recipients are never messaged |
| Spam / abuse via test messages | 3 per recipient and 10 per tenant per hour (database-enforced), one indistinguishable 429 |
| Message carries personal data | Template shape verified exactly; parameters are closed enums and an allow-listed path; arbitrary links and templates cannot be configured |
| Phishing through the link | The base is the operator's own origin, fixed in the template at Meta; DivalHR sends only the path |
| Duplicate or lost messages | At-least-once; unique (connector, recipient, event); never re-sent after a provider id exists; unknown outcomes shown as `STATUS_UNKNOWN` |
| One failing recipient takes everyone offline | Ten consecutive terminal failures suspend the connector (accepted trade-off, ADR 0029) |
| Secrets in logs | Logs carry operation, type, outcome, attempt, status class, failure class and latency band only |
| Synthetic provider leaks into production | Core refuses the test-provider setting and any non-Meta origin outside development and test; the provider exists only in the dev stack and hr-dev compose |

## Residual risks (accepted for MVP-111, closed before production)

- Meta sees the recipient's number and the fact that a business messaged them. Legal and privacy
  review of the opt-in wording and of cross-border transfer is required.
- STOP and opt-out keywords are not processed; a recipient who replies is not recognised. Opt-out is
  an administrator action.
- Erasure of a recipient's number on request is not automated (rows are disabled, not deleted).
- A single repeatedly rejected number can suspend the tenant's connector.
- Callback availability depends on ingress rate limiting and, ideally, source filtering to Meta.
- Provider-side throttle semantics are inferred from error codes (ADR 0029, flagged for review).
