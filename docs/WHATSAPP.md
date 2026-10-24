# Connecting WhatsApp Business notifications

This guide is for the tenant administrator who connects WhatsApp and for the operator who prepares
the Meta account and the platform. It describes MVP-111 (ADR 0029). DivalHR sends a short, generic
WhatsApp message that something needs attention, with a link to sign in. **It never sends names,
dates, amounts or any HR data in the message.**

> **Not implemented:** STOP and other opt-out keywords are not processed, and DivalHR does not read
> or answer replies. Inbound conversations, bots, media, marketing and bulk sends do not exist. To
> stop messages to a person, disable that recipient (or the whole connector) in DivalHR. A person who
> messages the business number gets no answer from DivalHR.

## What you need

1. A Meta Business account with a **WhatsApp Business Account (WABA)** and a phone number registered
   on the WhatsApp Business **Cloud API**. Note the **WABA id** and the **phone-number id** (digits
   only, 5 to 20).
2. A **system user** access token with the `whatsapp_business_messaging` and
   `whatsapp_business_management` permissions (a long-lived token; do not use a personal token).
3. **Approved message templates**, one per notification type and language you want (below).
4. A **Meta app** with the WhatsApp product, whose **app secret** DivalHR uses to verify callbacks.
5. The recipients' **opt-in** (below), obtained and kept by your organization.

Production use also needs Meta's business verification and app review, and your own legal and privacy
review (ADR 0029, "Production readiness gaps"). Until then use the synthetic provider (last section).

## Template shape (exact)

DivalHR verifies every template with Meta and refuses any other shape. For each notification type and
language (`en`, `fr`) create a template whose components are exactly:

| Component | Rule |
| --- | --- |
| `BODY` | Plain text with **no** variable, or only the variable `{{1}}`, once. |
| `FOOTER` | Optional; static text, no variable. |
| `BUTTONS` | Exactly **one** button, type `URL`, whose URL is your DivalHR address followed by `/{{1}}`. |
| Header | **Not allowed** (it could carry media or variables). |

The button URL must be your DivalHR public origin and nothing else, for example
`https://hr.example.com/{{1}}` (the same origin as the web application, `DIVALHR_WEB_BASE_URL`;
`https`, no path, no query). DivalHR fills `{{1}}` with the page path, for example
`admin/leave-approvals`, so the person opens an authenticated page and signs in as usual.

If the body has `{{1}}`, DivalHR fills it with a fixed generic label in the recipient's language, for
example « Décision sur un congé » / "Leave decision". Suggested wording (the body is yours to
write, keep it generic):

- English: `DivalHR: you have an update waiting. {{1}}` and button text `Open DivalHR`.
- Français : `DivalHR : une mise à jour vous attend. {{1}}` et bouton `Ouvrir DivalHR`.

The notification types and their pages:

| Type | Sent when | Page |
| --- | --- | --- |
| `LEAVE_DECISION` | A leave request is approved or rejected | `/admin/leave-approvals` |
| `TIMESHEET_APPROVED` | A timesheet is approved | `/admin/timesheets` |
| `ATTENDANCE_CORRECTED` | An attendance record is corrected | `/admin/attendance-review` |
| `PAYROLL_INPUT_LOCKED` | A payroll input run is locked | `/admin/payroll-preparation` |
| `PAYROLL_EXPORT_READY` | A payroll input export is ready | `/admin/payroll-preparation` |
| `CONNECTOR_TEST` | An administrator sends a test message | `/admin/integrations/whatsapp` |

Template names are lower-case letters, digits and underscores, starting with a letter (for example
`divalhr_leave_decision_fr`). The name is configuration; it is never shown in logs or the audit trail.

## Connect in DivalHR

Sign in as a tenant administrator (second factor required) and open
**Integrations → WhatsApp Business** (« Intégrations → WhatsApp Business »).

1. **Connect.** Enter the WABA id, the phone-number id, the display number, the access token and the
   template names (type by language). The connector starts as a **draft**. The token is sealed and
   never shown again.
2. **Verify.** DivalHR checks the token against the phone-number id and each template against Meta.
   Every template is reported as `OK`, missing, not approved, wrong language or wrong shape. When all
   pass, the connector becomes **active**.
3. **Register recipients** (up to 25). Give a label, the number in international format
   (`+243...`), a language, and the notification types they should receive. You must confirm the
   **opt-in**: how it was obtained (written, web form, verbal and recorded outside DivalHR, or other
   documented), when, and that you attest it. Without it the recipient is refused.
4. **Send a test** to a recipient. Limits: 3 per recipient and 10 per tenant per hour.
5. **Watch delivery.** The history lists each message as pending, retrying, accepted, delivered, read,
   failed, cancelled or status unknown, with its attempts (closed failure class, status code and
   duration only). Numbers are always masked.

Only events that happen **after** the connector is active and the recipient is registered are sent.
Old events are never replayed.

### Changing and stopping

- Changing an identifier or a template returns the connector to draft until you verify again.
- **Rotate the token** with a new token; the old one is erased.
- **Disable** the connector to stop everything at once (open messages are cancelled); **enable** it to
  resume (verify first if it was changed). **Disable a recipient** to stop messages to one person.
- After 10 consecutive terminal failures DivalHR **suspends** the connector automatically. Fix the
  cause (token, template, number), then enable it. A delivered or read message resets the count.

### Retries

Five attempts: immediately, then after 1 minute, 5 minutes, 30 minutes and 2 hours. Connection,
timeout, throttling and provider-side (5xx) problems are retried. A refused token, template or number
is not. Once WhatsApp has accepted a message it is never sent again. If the last attempt ends without
an answer, the message is **status unknown**: it may or may not have arrived.

## Configure the platform (operator)

| Setting | Meaning |
| --- | --- |
| `DIVALHR_MESSAGING_ENCRYPTION_KEY` | Key material (at least 32 bytes) sealing tokens and numbers. **Never rotate or lose it.** |
| `DIVALHR_MESSAGING_APP_SECRET` | The Meta app secret (at least 16 characters); verifies callback signatures. |
| `DIVALHR_MESSAGING_VERIFY_TOKEN` | A random string (at least 16 characters) you also enter in Meta as the callback verify token. |
| `DIVALHR_MESSAGING_GRAPH_VERSION` | Pinned Graph API version, `vNN.N` (default `v22.0`). |
| `DIVALHR_WEB_BASE_URL` | The public origin used in deep links (`https://...`). |

Without the key the connector answers `503 MESSAGING_SECRET_UNAVAILABLE` and the worker idles; without
the app secret or the verify token the callback endpoint refuses everything. The Graph origin is
always `https://graph.facebook.com` in production and cannot be changed by configuration.

In the hr-dev environment the three secrets are generated once by `ops/hr-dev/init-secrets.sh` as
root-only files (see `docs/OPS-HR-DEV.md`). In production provision the same three files the same way
and put the real app secret and verify token in them; never put them in the repository, an
environment file or a chat.

### Callback URL in Meta

In the Meta app, WhatsApp, Configuration, set:

- **Callback URL:** `https://<your DivalHR API origin>/api/v1/public/messaging/whatsapp/callback`
- **Verify token:** the value of `DIVALHR_MESSAGING_VERIFY_TOKEN`
- Subscribe the **`messages`** field (DivalHR reads delivery statuses only; any inbound message is
  ignored without being read or stored).

Meta checks the URL with a `GET`; DivalHR answers when the verify token matches. Every callback `POST`
is signed with the app secret (`X-Hub-Signature-256`) and DivalHR rejects anything unsigned or
wrongly signed (`403`). Allow only Meta's published addresses at your edge where possible, and rate
limit `/api/v1/public/**` at the ingress.

## Troubleshooting

| You see | Likely cause |
| --- | --- |
| `MESSAGING_SECRET_UNAVAILABLE` | The messaging key is not provisioned. |
| `MESSAGING_PROVIDER_CREDENTIAL_INVALID` | Wrong token or phone-number id, or missing permissions. |
| `MESSAGING_TEMPLATE_INVALID` | A template is missing, not approved, in the wrong language, or not the exact shape. |
| `MESSAGING_PROVIDER_UNAVAILABLE` | Meta could not be reached; try again. |
| `MESSAGING_CONSENT_REQUIRED` | The opt-in attestation is incomplete. |
| `MESSAGING_PHONE_INVALID` | The number is not valid E.164 (`+` and 7 to 15 digits). |
| `MESSAGING_TEST_RATE_LIMITED` | Too many test messages this hour (try after one hour). |
| `MESSAGING_CONNECTOR_SUSPENDED` | Ten consecutive terminal failures; fix and enable. |
| Messages stay pending | The connector is not active, or the worker cannot reach Meta. |
| Accepted but never delivered | The recipient's WhatsApp, a block, or Meta's policy; check the status history. |

## Try it without Meta (development and hr-dev only)

The synthetic provider (`infrastructure/docker/meta-provider`) imitates the three Graph requests
DivalHR makes and can emit signed callbacks. It is **not Meta**, uses throwaway data and is never used
in production, where DivalHR refuses any Graph origin other than Meta's. Its control endpoint is
`http://127.0.0.1:8099` inside the container (published on `127.0.0.1:8098` in the development stack;
in hr-dev use `docker compose exec meta-provider wget`).

```sh
C=http://127.0.0.1:8098   # development stack; in hr-dev run the same paths from inside the container
curl -s -X POST $C/control/account   -d '{"phoneNumberId":"1000000000001","wabaId":"2000000000002"}'
curl -s -X POST $C/control/templates -d '{"names":["divalhr_notice"],"languages":["en","fr"],"bodyParameters":1,"buttonUrl":"http://localhost:5173/{{1}}"}'
# ... connect and verify in DivalHR with those ids, any token, template name divalhr_notice ...
curl -s -X POST $C/control/scenario  -d '{"mode":"throttle429","count":1}'   # next send is throttled
curl -s          $C/control/sends                                             # what was sent (masked)
curl -s -X POST $C/control/emit-callback -d '{"statuses":[{"id":"last","status":"delivered"}]}'
curl -s -X POST $C/control/emit-callback -d '{"statuses":[{"id":"last","status":"read"}],"signature":"invalid"}'  # 403
curl -s -X POST $C/control/emit-verify -d '{}'                                # GET handshake
```

The button URL must equal your `DIVALHR_WEB_BASE_URL` plus `/{{1}}` (in the development stack
`http://localhost:5173`). Scenarios: `accept`, `throttle429`, `throttle-code`, `server500`,
`credential401`, `recipient-rejected`, `template-error`, `no-id`, `timeout`, `close`. The provider
keeps only in-memory records (template name, language, last two digits, parameter count, button
suffix) and never a token or a full number.
