# ADR 0029 WhatsApp Business notifications: one connector per tenant, generic template messages, deep link only

## Status

Decisions D111-1 to D111-16 approved by the architect on Issue #163; implemented by the MVP-111 pull
request, pending architecture review of the implementation. Builds on ADR 0028 (the `integrations`
module, the public-event mapper and the fan-out ledger), ADR 0007 (transactional outbox), ADR 0006
(idempotent commands), ADR 0016 (neutral wording) and ADR 0002 (bilingual first class).

**Nothing in this change contacts Meta or uses a real WhatsApp account.** Acceptance uses a synthetic
provider with throwaway data (decision 4). Production enablement needs the gates in "Production
readiness gaps".

## Context

The first design partner wants approvers and payroll preparers to hear on WhatsApp that something
needs them, without personal or HR data leaving DivalHR. WhatsApp Business Cloud API only allows a
business-initiated message outside a 24 hour customer window as a pre-approved template, to a person
who opted in. The feature is therefore a privacy boundary first: who may connect it, which person
may be messaged, what a message may say, where a secret lives, and how a provider callback is trusted.

## Decision

- **Who (D111-1, D111-12).** A tenant administrator with exact MFA and an active membership
  (`@TenantAdminOperation`) connects one WhatsApp Business Cloud API connector per tenant. Unknown,
  malformed and foreign ids are the same `404`. Disclosure reads write a fail-closed audit event,
  commands are privileged and audited, every response (errors and denials included) is
  `Cache-Control: private, no-store` (`MessagingCacheControlFilter`).
- **Resource (D111-2, D111-3).** `integrations.messaging_connector`: one per tenant (unique), unique
  phone-number id, state `DRAFT | ACTIVE | DISABLED | SUSPENDED`, `configVersion` and
  `credentialVersion`. Never deleted. Changing identifiers or templates returns the connector to
  `DRAFT`; `verify` checks the credential and every template with the provider and activates.
- **Credential (D111-4).** The system-user access token is accepted on connect and rotate only. It is
  stored as AES-256-GCM ciphertext (HKDF-SHA256 subkey from `DIVALHR_MESSAGING_ENCRYPTION_KEY`; AAD binds
  provider, tenant, connector, credential version and algorithm version). It is never returned,
  audited, logged, placed in the idempotency store (keyed digests only) or sent anywhere but the
  `Authorization` header of the Graph request. Rotation is atomic; the previous ciphertext is erased.
- **Recipients and consent (D111-5).** Up to 25 per tenant. Each carries a label, language
  (`en`/`fr`), the selected notification types, and an attestation of opt-in: the source
  (`WRITTEN`, `WEB_FORM`, `VERBAL_RECORDED_OUTSIDE_DIVALHR`, `OTHER_DOCUMENTED`), the confirmation instant
  and the attesting administrator. Any gap is `422 MESSAGING_CONSENT_REQUIRED`. The E.164 number is stored
  encrypted (AAD binds tenant, connector, recipient) plus a keyed HMAC digest for uniqueness; only a
  masked form such as `+2 *** ** 42` is ever shown. A recipient is not an employee record and no
  link to one is stored.
- **Catalogue (D111-6).** Closed: `LEAVE_DECISION`, `TIMESHEET_APPROVED`, `ATTENDANCE_CORRECTED`,
  `PAYROLL_INPUT_LOCKED`, `PAYROLL_EXPORT_READY` and the administrator-only `CONNECTOR_TEST`. Events
  reach the catalogue only through the MVP-110 `WebhookEventType.fromInternal` mapper, so no module
  gains a second path to the outbox. Old events are never replayed: a recipient is notified only
  about events recorded after the connector was activated and the recipient registered.
- **Message content (D111-7).** Generic, never personal. A template (operator-created in Meta, one
  per type and language, name matching `^[a-z][a-z0-9_]{0,511}$`) has exactly one `BODY` with no
  variable or only `{{1}}`, an optional static `FOOTER`, no header, and exactly one `BUTTONS`
  component holding one URL button whose URL is the operator-controlled public base URL (the
  existing `DIVALHR_WEB_BASE_URL`) plus `{{1}}`. `{{1}}` of the body is a closed-enum generic label
  (English or French, from `NotificationType`); `{{1}}` of the button is the allow-listed path of the
  type without its leading slash. The recipient lands on an authenticated page and signs in. The base
  must be `https://host[:port]` (`http://localhost` only in development and test).
- **Provider boundary (D111-8).** `MessagingProvider` is implemented by `GraphApiProvider` only. The
  origin is exactly `https://graph.facebook.com` in production and a pinned `vNN.N` version, both
  operator configuration validated at start-up; no tenant input chooses a host. No redirects, no proxy
  inheritance, 3 s connect and 10 s request deadlines, a 64 KiB bounded response read, and only
  allow-listed fields parsed (message id, numeric error code, template metadata). A response body or
  header is never stored or logged.
- **Delivery (D111-9).** At-least-once. One delivery per (connector, recipient, event) (unique).
  States `PENDING | RETRYING | ACCEPTED | DELIVERED | READ | FAILED | CANCELLED | STATUS_UNKNOWN`.
  Five attempts: immediate, 1 minute, 5 minutes, 30 minutes, 2 hours (jitter in production; a provider
  `Retry-After` can lengthen a delay up to 2 hours, never shorten it). Retried: DNS, connect, TLS,
  request timeout, connection failure, throttling and 5xx. Not retried: credential, template and
  recipient rejections and other 4xx. **Once a provider message id exists the message is never sent
  again** (database guard). A last retryable failure whose acceptance is unknown ends in
  `STATUS_UNKNOWN`, not `FAILED`. Ten consecutive terminal failures suspend the connector and cancel
  open deliveries; a delivered or read callback resets the counter. Attempt rows hold only the closed
  failure class, the status code and the duration.
- **Provider callbacks (D111-10).** `GET/POST /api/v1/public/messaging/whatsapp/callback`, the only
  unauthenticated door, on the token-free public chain. `GET` echoes the challenge when
  `hub.verify_token` matches in constant time. `POST` verifies `X-Hub-Signature-256` (HMAC-SHA256 of the
  exact raw bytes with the app secret, constant time) before parsing, reads at most 64 KiB, enforces a
  provider-specific ceiling (600 per minute per instance), and reads allow-listed fields only. A message
  is found by provider message id **and** the connector's stored phone-number id. Unknown messages,
  duplicates and lower statuses get a neutral `200`; inbound customer messages are ignored without
  being read or stored. Status moves are monotonic and `READ` implies delivered. A bad signature is
  `403`, an unconfigured door `503`, an excess `429`, an oversized body `413`.
- **Test messages (D111-11).** A test goes to one recipient as a `CONNECTOR_TEST` delivery with the
  same template and retry path. At most 3 per recipient and 10 per tenant per hour, enforced by a
  database trigger and answered with one indistinguishable `429 MESSAGING_TEST_RATE_LIMITED`
  (`Retry-After: 3600`).
- **Audit, outbox and logs (D111-13).** Every command writes a bounded closed audit record (no
  phone, WABA or phone-number id, template name, token, provider message id or URL) and a lifecycle
  outbox event (`integrations.messaging-connector.created|updated|token-rotated|disabled|enabled.v1`,
  `integrations.messaging-recipient.created|updated|disabled|enabled.v1`) in the command's
  transaction. Logs and metrics carry operation, notification type, outcome, attempt number, status
  class, failure class and latency band only.
- **UI (D111-14).** « Integrations → WhatsApp Business » for tenant administrators, fully bilingual
  (`messaging.*` keys, `errors.MESSAGING_*`), with exactly two Playwright journeys (English and
  French administrators). The page states that STOP automation and inbound conversations are not
  implemented.
- **Operations (D111-15).** The encryption key, the Meta app secret and the callback verify token are
  protected files (hr-dev) or `.env` development values, never repository values and never environment
  values in hr-dev. Stable error codes exist for every refusal. The synthetic provider
  (`infrastructure/docker/meta-provider`) is the acceptance counterpart of Meta.
- **Out of scope (D111-16).** Teams, Slack, SMS, email and push; inbound conversations, bots and
  replies; **STOP and opt-out automation (not implemented: opt-out is the administrator disabling the
  recipient)**; media, marketing and bulk sends; arbitrary templates or links; any personal data in a
  message; more than one connector per tenant; replay of old events; production enablement.

## Implementation decisions to flag

### Meta throttle classification (needs architect confirmation)

Meta documents throttling for WhatsApp Cloud API by error **code** (4 application request limit,
80007 WhatsApp Business Account rate limit, 130429 Cloud API throughput limit, 131056 business-to-user
pair rate limit) and does not document a dedicated HTTP status or a `Retry-After` header for them.
`GraphApiProvider.classify` therefore treats HTTP `429` **or** any of those four codes as
`RATE_LIMITED` (retryable, honoring `Retry-After` when present, capped at 2 hours), codes 132000 to
132999 as `TEMPLATE_INVALID`, a closed set of codes plus HTTP `401` as `CREDENTIAL_INVALID`, a closed
set as `RECIPIENT_REJECTED`, and any other 4xx as `HTTP_RESPONSE` (not retried). The sets are in
`GraphApiProvider` and are unit tested. They were taken from Meta's public documentation during design
and could not be re-verified from this change, because contacting Meta is out of scope; re-verify the
code lists against the current error-codes page before production enablement, and widen or narrow
only with a test.

### Suspension counts recipient rejections

Per the specification, ten consecutive terminal failures suspend the connector, and a terminal
`RECIPIENT_REJECTED` counts. One bad number repeated ten times can therefore suspend the connector
for the whole tenant. This follows the approved text; a per-recipient breaker is a possible follow-up.

### Callback door uses its own limiter

`PublicRateLimiter` is built for per-client anonymous flows. Meta calls from many addresses, so the
callback door uses a provider-wide in-process ceiling applied after the signature check (so a flood of
unsigned requests cannot exhaust the ceiling and starve genuine callbacks). Ingress rate limiting of
`/api/v1/public/**` remains a deployment requirement.

### hr-dev reuses the `hooks` network

The synthetic Meta provider joins the existing internal `hooks` network (alias `graph.meta.test`) next
to the synthetic webhook receiver, instead of adding a network: Core's network list, the count of
internal networks and the isolation tests stay unchanged. The development stack gets a separate
internal `meta` network.

## Consequences

- A tenant's token and recipients' numbers are unreadable without the messaging key. Losing or
  changing it requires rotating the token and re-registering recipients. Back it up with the secrets
  directory.
- Message text is fixed by templates in Meta; changing wording is a Meta template change plus a
  `verify`, not a DivalHR release.
- Delivery is at-least-once: a timeout after the provider accepted can produce a duplicate on retry.
  The message is generic and idempotent in meaning ("something awaits you").
- `db/rollback/V40__rollback.sql` refuses while any connector, recipient, delivery or fan-out data exists.

## Production readiness gaps (not MVP-111)

- Meta App Review, business verification, display-name approval and template approval for the real
  account; legal and privacy review of the opt-in wording and of cross-border transfer to Meta.
- Egress from the production network to `graph.facebook.com` and ingress for the callback with
  edge rate limiting and a published callback URL; a key-rotation procedure for the messaging key.
- STOP and opt-out handling, inbound messages, delivery-window policy and quiet hours.
- Monitoring and alerting on suspension, `STATUS_UNKNOWN` growth and callback signature failures.

## Alternatives rejected

- **Free-text or data-bearing messages.** Would put personal and HR data in a third party's hands and
  needs the 24 hour window.
- **A link without authentication, or a one-time token in the message.** The deep link is only a
  path; sign-in protects the data.
- **One connector per recipient or per team.** More secrets, more review surface, no partner need.
- **Trusting callbacks by shared secret in the URL.** A signature over the exact bytes is the provider's
  own scheme and binds the content.
- **Matching callbacks by message id alone.** The phone-number binding stops a message id from one
  connector being replayed at another.
- **Storing phone numbers in plain text with a masked copy.** Rejected: ciphertext plus digest gives
  uniqueness without a readable column.
