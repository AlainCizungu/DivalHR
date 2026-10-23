# ADR 0028 Secure outbound webhooks: signed, SSRF-safe, at-least-once delivery

## Status

Decisions D110-1 to D110-16 approved by the architect on Issue #161; implemented by the MVP-110 pull
request, pending architecture review of the implementation. The three implementation decisions
below (leave-event mapping, the hr-dev synthetic receiver, test-delivery rate limiting) were raised
before coding and confirmed by the architect with the clarifications recorded here. Builds on
ADR 0007 (transactional outbox), ADR 0006 (idempotent commands), ADR 0016 (neutral wording) and
ADR 0002 (bilingual first class).

## Context

Customers need DivalHR to tell their own systems that something was approved or locked, without
DivalHR becoming an open proxy, leaking personal data, or trusting a customer-supplied URL. Every
webhook is a server-side request to an address an administrator typed, so the feature is mostly a
security boundary: who may configure it, what may be sent, where it may go, and what is kept.

## Decision

- **Who (D110-1).** Tenant administrator, exact MFA and an active membership only (`@TenantAdminOperation`).
  Unknown, malformed and foreign ids are the same `404 WEBHOOK_NOT_FOUND`. Disclosure reads write a
  fail-closed audit event; every command is privileged and audited. Every response, errors and
  denials included, is `Cache-Control: private, no-store` (`WebhookCacheControlFilter`).
- **Resource (D110-2).** `integrations.webhook`: name (1 to 100 characters, trimmed plain text), one
  normalized HTTPS endpoint, a closed event list, status `ACTIVE | DISABLED | SUSPENDED`,
  `configVersion` (optimistic concurrency) and `secretVersion`. At most 20 per tenant (database
  trigger; the 21st is `409 WEBHOOK_LIMIT_REACHED`). There is no hard delete anywhere.
- **API (D110-3).** `/api/v1/integrations/webhooks`: create, keyset list, detail, PATCH, `rotate-secret`,
  `disable`, `enable`, `test-deliveries`, `deliveries` and `deliveries/{id}`. Commands need an
  `Idempotency-Key`, an explicit `confirm: true` and (except create) the current `configVersion`.
- **Secrets at rest (D110-4).** A random 32-byte secret is generated per version and returned once,
  as URL-safe base64, in the response of the request that created it. It is sealed with AES-256-GCM
  under a key derived by HKDF-SHA256 from `DIVALHR_WEBHOOK_ENCRYPTION_KEY` (a protected file in
  hr-dev; at least 32 bytes; `dev-only-` values refused outside development; no default; the
  feature answers `503 WEBHOOK_SECRET_UNAVAILABLE` and delivers nothing without it). The AAD binds
  tenant, webhook, secret version and algorithm version, so a ciphertext cannot be moved. Rotation
  keeps the previous secret for 24 hours (both signatures are sent), then the ciphertext is erased
  (`erased_at`); the row remains as evidence. The plaintext is never stored, logged, audited, put in
  an event, or replayed: an idempotent replay of create or rotate returns the webhook **without**
  the secret (the secret leaves the command only through an out-of-band holder, not through the
  idempotency record).
- **Replay independence (R162-1).** Create and update first look up a completed command by its key
  (read only; a different payload under the same key is still `IDEMPOTENCY_KEY_REUSED`). A completed
  command replays without any DNS lookup, network access or encryption-key check, so it cannot be
  changed by what DNS, the network or the key store look like later. Only a new key runs the secret
  availability and destination (DNS) validation, and it runs before, never inside, the
  state-changing transaction.
- **Signature (D110-5).** `X-DivalHR-Signature: v1=<hex HMAC-SHA256(key, timestamp "\n" body)>`, two
  values while rotated. The stored canonical body is byte-identical on every attempt. The headers
  are listed in `docs/WEBHOOKS.md`, which also carries a verified test vector.
- **Delivery (D110-6).** At least once, no ordering. Six attempts (immediately, then 1 minute,
  5 minutes, 30 minutes, 2 hours, 12 hours; plus or minus 10 % jitter in production). Connect 3 s,
  total 10 s, no redirect, any `2xx` is success, the response is never read or stored. After 10
  consecutive deliveries that exhausted all attempts the webhook is `SUSPENDED`; enabling resets the
  counter and never replays. A disabled webhook cancels its waiting deliveries.
- **Events (D110-7).** A closed catalogue of public, versioned events (`leave.request.approved.v1`,
  `leave.request.rejected.v1`, `timesheet.approved.v1`, `attendance.corrected.v1`,
  `payroll.input.locked.v1`, `payroll.input-export.created.v1`, `webhook.test.v1`) with a minimal
  envelope validated against `webhook-event.schema.json` (JSON Schema 2020-12). No names, addresses,
  free text, times of work, pay or payment data; at most one relative authenticated link.
- **Destinations (D110-8).** `https` only, port 443 or an allow-listed port, no userinfo, fragment or
  IP literal, a normalized host. Every A and AAAA answer (bounded to 16) must be public: private,
  loopback, link-local (including the cloud metadata address), carrier-grade NAT, multicast,
  documentation and reserved ranges, IPv4-mapped IPv6 and IPv6 equivalents are refused. The check
  runs at create, update, test **and every attempt**; the connection is made to the validated
  address while the host name is kept for SNI and certificate verification, so a DNS answer cannot
  change between check and use. Redirects are never followed.
- **Rate limits (D110-9, D110-12).** See decision 3 below.
- **History (D110-10).** `integrations.delivery` and `delivery_attempt`: state, attempt count, times,
  the exact HTTP status and one closed failure class (`DNS_BLOCKED`, `DNS_FAILED`, `CONNECT_TIMEOUT`,
  `REQUEST_TIMEOUT`, `TLS_FAILED`, `CONNECTION_FAILED`, `HTTP_RESPONSE`, `INTERNAL_FAILURE`), latency.
  No payload, response body, header, address or signature is exposed or kept beyond the stored body
  needed to retry.
- **UI (D110-11).** `Webhooks` under a new **Integrations** navigation group (tenant administrators):
  list, create, edit, test, rotate, disable and enable behind explicit confirmation, delivery
  history, receiver guidance. WCAG 2.2 AA, French and English. The one-time secret lives in component
  state only (no URL, history, storage, log or analytics), is selectable and copyable, and is dropped
  on "Done" or navigation.
- **Evidence and migration (D110-13).** V39 creates the schema with database guards: history and
  secrets are append-only, no row is deletable, state transitions and counters are checked, the
  20-webhook and 5-test limits are enforced by triggers. The rollback script restores V38 exactly and
  **refuses** once any webhook exists; there is no override.
- **Errors and audit (D110-14).** Thirteen stable codes (`WEBHOOK_*`) with English and French
  messages. Audit actions `webhook.create`, `.update`, `.rotate-secret`, `.disable`, `.enable`,
  `.test-delivery`, `.read` and `.delivery-read` with bounded metadata (ids, versions, counts, event
  list; never the endpoint, name or secret). A failed audit write rolls back the command or refuses
  the read.
- **Fan-out without touching the outbox (design).** The platform exposes a read-only `OutboxReader`
  port; the integrations module keeps its own idempotency ledger (`fanout_event`) and a singleton
  watermark (`fanout_state`, advisory lock, a two-minute late-commit rescan window). The platform
  outbox publisher is not modified and no other module reads integrations tables (enforced by
  `AccessPortArchitectureTest`). Workers claim deliveries with `FOR UPDATE SKIP LOCKED` and a lease
  token, so several instances never share a delivery.

### Decision 1: leave events (confirmed)

Normal approval, routing-exception approval and substitute approval all map to
`leave.request.approved.v1`; their rejection equivalents map to `leave.request.rejected.v1`. The
public payload does not expose which internal routing path produced the outcome (a test asserts the
words are absent). `WebhookEventType.INTERNAL_TYPES` lists the six internal types.

### Decision 2: synthetic receiver in hr-dev (confirmed, strict boundary)

Live acceptance needs a destination that is private by construction. A **synthetic HTTPS receiver**
may share a private Docker network with Core **only** through an explicit, test-only exception:

- disabled by default; `DIVALHR_WEBHOOK_TEST_RECEIVER_*` is refused unless the environment is
  `development` or `test` (start-up fails otherwise), so it cannot be activated in staging or
  production by accident;
- it names **one exact host name and port**; no CIDR, no general private-network allowance, and a
  different port or host is not covered;
- TLS and host-name verification are preserved: the receiver presents a throwaway certificate whose
  CA is the only extra trust anchor, used for that exact host only;
- every customer URL remains subject to all D110-8 rules; the boundary tests prove that other
  private, loopback and metadata destinations stay blocked while the receiver host is allowed
  (`HttpsDeliveryTransportTest`: `theReceiverExceptionIsOneExactHostAndPort`, `theReceiverExceptionDoesNotOpenMetadataOrPrivateRangesForOtherHosts`, `certificateAndHostnameVerificationStayOnForTheReceiver`; `AddressPolicyTest` for the ranges themselves);
- hr-dev gains a new `internal: true` network (`hooks`) shared by Core and the receiver only; no
  existing network loses `internal: true` (asserted by `scripts/ops/hr-dev.test.mjs`); the receiver
  publishes no port and keeps counters in memory only.

General outbound internet egress from hr-dev (and a production egress design: NAT, allow-listing,
logging, DNS) is **not part of MVP-110** and is recorded as a production-readiness gap below.

### Decision 3: test-delivery rate limits (confirmed)

- 20 accepted test deliveries per tenant per hour use the existing fixed-window limiter
  (`webhook-test-delivery`).
- 5 per webhook per hour are enforced **transactionally in the database**: the webhook row is locked
  (`FOR UPDATE`) before counting, so simultaneous commands cannot exceed five, and a trigger on
  `integrations.delivery` is the backstop that refuses a sixth.
- Accepted commands count, including an idempotently created delivery; a replay of the same
  `Idempotency-Key` returns the original answer and counts **once**.
- Both limits answer `429 WEBHOOK_TEST_RATE_LIMITED` with a safe `Retry-After`; the response never
  says which limit was reached.
- Validation, authorization, unknown-webhook and state (disabled, suspended) failures happen before
  the limits and consume nothing.

## Consequences

- A webhook can never reach an internal address through DNS tricks, redirects or rebinding, and a
  stolen database does not yield signing secrets without the key.
- Losing the encryption key makes stored secrets unreadable. Each webhook then needs a rotation
  (new secret, shown once). The key is never rotated in place, is part of the backed-up secrets
  directory, and a missing key stops delivery instead of sending unsigned or weakly signed requests.
- Delivery is at least once; receivers must deduplicate on `X-DivalHR-Event-Id`. Order is not
  guaranteed. Missed events are not replayed after suspension or while disabled.
- Webhooks cannot be deleted (evidence); the 20-webhook cap therefore counts disabled ones too.
- The first delivery latency is bounded by the worker interval (5 s).

## Production-readiness gaps (not MVP-110)

- Outbound internet egress for the worker in hr-dev and production (NAT or an egress proxy,
  destination allow-listing and logging, DNS policy). hr-dev networks stay `internal: true`.
- Retention and legal review of delivery history, and customer-facing SLA wording.
- Key management beyond a protected file (KMS or HSM, scheduled re-encryption on key change).
- Replay tooling, per-endpoint ordering, batching, mutual TLS, static source addresses and
  private-network destinations (all explicitly out of scope).

## Alternatives rejected

- Mutating `published_at` of platform outbox rows from the integrations module (couples modules and
  races the platform publisher); a read port plus a local ledger was chosen.
- Storing the plaintext secret in the idempotency record so replays can return it (turns every
  replay into a secret disclosure); replays return the webhook without the secret.
- Opening the whole private range for the test environment (breaks the SSRF invariant that tests
  must prove); one exact host and port was chosen.
- Following redirects or re-resolving DNS between check and connect (rebinding).
