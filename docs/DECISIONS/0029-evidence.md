# ADR 0029 evidence map (MVP-111, Issue #163)

Reusable in the pull request body. The pull request must contain `Closes #163`. The paths below are
the implementation; tests are named by area and must be reconciled with the final suite before the
body is posted (a test not yet listed here must not be claimed).

Numbering note: D111-1 and D111-3 to D111-10, D111-12 and D111-15 match the references already in the
code. D111-2, D111-11, D111-13, D111-14 and D111-16 are mapped by subject and need the architect's
original numbering confirmed.

| Decision | Summary | Code and artefacts | Evidence (test areas) |
| --- | --- | --- | --- |
| D111-1 | One WhatsApp Business Cloud API connector, tenant administrators only | `MessagingController`, `MessagingService`, `MessagingProvider` | Authorization (role, MFA, membership denials per handler: `AuthorizationDenialAuditIntegrationTest` inventory updated); tenant isolation |
| D111-2 | Resource model, one per tenant, never deleted | `V40__whatsapp_messaging_connector.sql`, `Connector` | V40 constraint and guard tests; rollback refusal |
| D111-3 | Connector lifecycle `DRAFT/ACTIVE/DISABLED/SUSPENDED`, verify, enable, disable | `ConnectorState`, `MessagingService`, `JdbcMessagingRepository.applyOutcome` | Service/API tests for each transition and stable `MESSAGING_*` errors |
| D111-4 | Token sealed, never returned, rotated atomically | `MessagingCipher`, `MessagingSecrets`, `messaging_credential` | Cipher unit tests (AAD binding, tamper), secret-leak scans of responses, logs, audit, idempotency store |
| D111-5 | Recipients: 25 max, E.164, sealed number, digest, masked, opt-in attestation | `PhoneNumber`, `Recipient`, `ConsentSource`, `messaging_recipient` triggers | Phone/consent validation tests, limit and duplicate tests, masked-only disclosure |
| D111-6 | Closed notification catalogue via the MVP-110 mapper | `NotificationType`, `MessagingJobs.fanOut` | Mapping tests, fan-out tests (no replay, only after activation) |
| D111-7 | Generic template messages, exact template shape, deep link only | `DeepLink`, `TemplateNames`, `GraphApiProvider.judge` | Template-shape matrix tests, deep-link tests, payload-content assertion |
| D111-8 | Operator-pinned Graph origin and version, bounded client | `GraphApiProvider`, `MessagingProperties.validate` | Properties validation tests, classify/judge tests, synthetic provider acceptance |
| D111-9 | At-least-once delivery, retries, no resend after provider id, suspension | `MessagingRetrySchedule`, `MessagingJobs`, `messaging_delivery_guard` | Retry schedule tests, worker tests, V40 guard tests |
| D111-10 | Signed callback door, monotonic statuses, phone-number binding | `MessagingCallbackController`, `MessagingCallbackService`, `MetaSignature`, `applyCallback`, `SecurityConfig` | Signature tests, callback boundary integration tests (valid, invalid, missing, replay, unknown, oversize, rate) |
| D111-11 | Test messages with database-enforced limits | `MessagingService.testDelivery`, delivery quota trigger | Quota tests (3 per recipient, 10 per tenant, identical 429) |
| D111-12 | Management API, no-store, idempotent, 404-uniform | `MessagingController`, `MessagingCacheControlFilter`, `docs/API-SPEC.yaml` | `ApiContractDriftTest`, cache-header test, idempotency replay tests |
| D111-13 | Closed audit and outbox lifecycle events, safe logs | `MessagingService` audit/outbox helpers, `MessagingJobs` logging | Audit-content tests, log-leak scan |
| D111-14 | Bilingual UI, two journeys | `apps/web` (Integrations → WhatsApp Business), `packages/localization` | Locale parity, UI tests, exactly two Playwright journeys (EN, FR admin) |
| D111-15 | Ops: protected secrets, synthetic provider, stable error codes | `ops/hr-dev/init-secrets.sh`, `lib.sh`, `remote-deploy.sh`, both compose files, `infrastructure/docker/meta-provider`, `docs/OPS-HR-DEV.md`, `docs/WHATSAPP.md` | `scripts/ops/hr-dev.test.mjs` (MVP-111 case), hr-dev rehearsal |
| D111-16 | Out of scope (no STOP automation, inbound, bulk, multi-connector, replay, production enablement) | `docs/WHATSAPP.md`, ADR 0029 | Documented; negative API tests where behaviour exists (no inbound persistence) |

## Deliverables checklist

ADR 0029; `ARCHITECTURE.md`, `SECURITY.md`, `I18N.md`, `DATA-MODEL.md`, `BACKLOG.md`; operator guide
`docs/WHATSAPP.md`; hr-dev runbook section in `docs/OPS-HR-DEV.md`; threat model
`docs/security/MVP-111-WHATSAPP-DATA-FLOW.md`; `.env.example` without secrets; `docs/API-SPEC.yaml`
and the generated client (owned by the API work).

## Tests deliberately not repeated

- No live Meta call and no real WhatsApp account: the Graph contract is exercised against the
  synthetic provider and against canned response fixtures only.
- The MVP-110 outbox reader, fan-out ledger mechanics and public-event mapper tests are not duplicated;
  messaging tests assert only the messaging mapping on top of them.
- Platform idempotency, cursor, rate-limit and audit infrastructure tests are not repeated; the
  messaging tests assert only that these operations are wired to them.
- Generic cipher primitives (AES-GCM, HKDF) are tested through `MessagingCipher` only, not re-proved.
- No Playwright coverage beyond the two journeys; validation matrices, error codes and French/English
  labels are covered by unit and component tests, not by additional browser journeys.
- No AWS verification, merge or deployment is run for this change; the browser-stage repetitions and
  the single `full` run belong to the approved-head step of the verification sequence.
