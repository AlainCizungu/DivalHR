# Receiving DivalHR webhooks

For the developers who operate a customer endpoint. Administrators create webhooks in **Webhooks**
(tenant administrator, multi-factor session). Decisions and limits: ADR 0028. The machine-readable
payload contract is `packages/shared-contracts/schemas/webhook-event.schema.json`, with one example
per event in `packages/shared-contracts/webhooks/v1/examples/`.

## What you receive

An HTTPS `POST` to the endpoint you registered, with a small JSON body (identifiers and a closed
status; never a name, address, free text, time of work, pay or payment data) and these headers:

| Header                                           | Meaning                                                                      |
| ------------------------------------------------ | ---------------------------------------------------------------------------- |
| `Content-Type: application/json`                 |                                                                              |
| `User-Agent: DivalHR-Webhooks/1`                 |                                                                              |
| `X-DivalHR-Event-Id`                             | Stable per business event. **Use it to deduplicate.**                        |
| `X-DivalHR-Delivery-Id`                          | Stable per delivery to your endpoint (identical on every attempt).           |
| `X-DivalHR-Attempt`                              | 1 to 6.                                                                      |
| `X-DivalHR-Timestamp`                            | Unix seconds when this attempt was signed.                                   |
| `X-DivalHR-Signature`                            | `v1=<hex>`; two comma-separated values while a rotated secret still overlaps. |

Events (all `.v1`): `leave.request.approved`, `leave.request.rejected`, `timesheet.approved`,
`attendance.corrected`, `payroll.input.locked`, `payroll.input-export.created`, and `webhook.test`
(only from the test button). The body carries `eventType`, `occurredAt`, `tenantId`, a `resource`
(`kind`, `id`, `status`, `version`) and at most one relative `links` entry that needs a DivalHR
sign-in. Fetch details from DivalHR with your own authorized session: the webhook never contains them.

## Verify every delivery

The signing secret is shown **once**, as URL-safe base64 of 32 random bytes. Decode it to get the
HMAC key.

1. Read the **raw** body bytes before any JSON parsing or re-serialization.
2. Compute `expected = "v1=" + hex(HMAC_SHA256(key, timestamp + "\n" + rawBody))`, where `timestamp`
   is the header value as sent (ASCII digits).
3. Compare with each comma-separated value of `X-DivalHR-Signature` in **constant time**. Accept if
   any matches.
4. Reject when `abs(now - timestamp) > 300` seconds (replay protection).
5. Deduplicate on `X-DivalHR-Event-Id`: delivery is **at least once**, so a retry or a late duplicate
   must be harmless. Order is not guaranteed.
6. Answer any `2xx` quickly (the sender waits 3 s to connect and 10 s in total, follows no
   redirect, and never reads your response). Anything else, or a timeout, is retried.

Test vector (verified by the Core unit test `WebhookSignerTest`):

```
secret (base64url) AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
timestamp          1792746000
body               {"schemaVersion":1,"eventId":"6f1c1f0e-8a8b-4b43-9a3e-0f6f7b0c2a07","eventType":"webhook.test.v1","occurredAt":"2026-10-20T09:30:00Z","tenantId":"00000000-0000-4000-8000-00000000000a","resource":{"kind":"webhook-test","id":"7a2d3c4e-1b5f-4c6d-8e7f-9a0b1c2d3e07","status":"TEST","version":1},"links":[]}
signature          v1=99ec1c16b5c79c2cba0e53ae1ed1eab7820e68109c642793a5888b03fdf6f98a
```

### Node.js

```js
import { createHmac, timingSafeEqual } from 'node:crypto';

export function verify(secret, headers, rawBody) {
  const timestamp = headers['x-divalhr-timestamp'];
  if (Math.abs(Date.now() / 1000 - Number(timestamp)) > 300) return false;
  const key = Buffer.from(secret, 'base64url');
  const expected = Buffer.from(
    'v1=' +
      createHmac('sha256', key).update(`${timestamp}\n`).update(rawBody).digest('hex'),
  );
  return headers['x-divalhr-signature'].split(',').some((candidate) => {
    const given = Buffer.from(candidate.trim());
    return given.length === expected.length && timingSafeEqual(given, expected);
  });
}
```

### Python

```python
import base64, hashlib, hmac, time

def verify(secret: str, headers: dict, raw_body: bytes) -> bool:
    timestamp = headers["X-DivalHR-Timestamp"]
    if abs(time.time() - int(timestamp)) > 300:
        return False
    key = base64.urlsafe_b64decode(secret + "=" * (-len(secret) % 4))
    expected = "v1=" + hmac.new(key, timestamp.encode() + b"\n" + raw_body, hashlib.sha256).hexdigest()
    return any(
        hmac.compare_digest(candidate.strip(), expected)
        for candidate in headers["X-DivalHR-Signature"].split(",")
    )
```

## Delivery behaviour

Six attempts: immediately, then 1 minute, 5 minutes, 30 minutes, 2 hours and 12 hours after the
previous failure (up to 10 % jitter). The body is byte-identical on every attempt. After 10
consecutive deliveries that failed all six attempts, DivalHR **suspends** the webhook; fixing the
endpoint and enabling it again resets the counter and does **not** replay missed events. The
history shows only outcomes (state, attempts, HTTP status, a closed failure class, latency); no
payload, response body or header is stored.

Your endpoint must be a public HTTPS name on port 443 (or an allow-listed port), without credentials,
fragment or IP literal. Every address it resolves to is checked at creation, edit, test and
**every attempt**; any private, loopback, link-local, metadata or otherwise reserved answer refuses
the destination (`WEBHOOK_DESTINATION_BLOCKED`, or a `DNS_BLOCKED` delivery failure). Redirects are
never followed. DivalHR does not send from fixed addresses and does not support mutual TLS.

## Rotating the secret

Rotate in **Webhooks > Rotate secret**. A new secret is shown once. For the next 24 hours every
delivery carries two signatures (new first); accept either, deploy the new secret, and the old one
stops signing and is erased at the end of the overlap. A lost secret cannot be recovered: rotate.
