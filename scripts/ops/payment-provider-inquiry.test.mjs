import assert from 'node:assert/strict';
import { createHmac, randomUUID } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { request } from 'node:https';
import { request as httpRequest } from 'node:http';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';

// MVP-095C (ADR 0038 section 13): the synthetic provider answers signed status inquiries. Synthetic key only.

const SERVER = new URL('../../infrastructure/docker/payment-provider/server.mjs', import.meta.url);
const KEY = 'test-only-payment-submission-signing-key-0002';
let dir;
let child;
let httpsPort;
let controlPort;

function freePort() {
  return new Promise((resolve) => {
    const probe = createServer();
    probe.listen(0, '127.0.0.1', () => {
      const { port } = probe.address();
      probe.close(() => resolve(port));
    });
  });
}

const sign = (key, requestId, inquiryId, body) =>
  'v1=' +
  createHmac('sha256', key)
    .update('v1-inquiry\n')
    .update(requestId)
    .update('\n')
    .update(inquiryId)
    .update('\n')
    .update(body)
    .digest('hex');

const canonical = (inquiryId, requestId) =>
  JSON.stringify({ inquiryId, requestId, schemaVersion: 1 });

/** Sends an inquiry; every header and the body can be overridden. */
function inquire({ requestId, inquiryId, body, signature, headerRequestId, headerInquiryId } = {}) {
  requestId ??= randomUUID();
  inquiryId ??= randomUUID();
  body ??= canonical(inquiryId, requestId);
  const hRequest = headerRequestId ?? requestId;
  const hInquiry = headerInquiryId ?? inquiryId;
  signature ??= sign(KEY, hRequest, hInquiry, body);
  return new Promise((resolve, reject) => {
    const req = request(
      {
        host: '127.0.0.1',
        port: httpsPort,
        path: '/v1/payment-status-inquiries',
        method: 'POST',
        rejectUnauthorized: false,
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(body),
          'X-Divalhr-Request-Id': hRequest,
          'X-Divalhr-Inquiry-Id': hInquiry,
          'X-Divalhr-Key-Version': '1',
          'X-Divalhr-Signature': signature,
        },
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () =>
          resolve({
            status: res.statusCode,
            signature: res.headers['x-divalhr-response-signature'],
            text: Buffer.concat(chunks).toString('utf8'),
            requestId,
            inquiryId,
          }),
        );
      },
    );
    req.on('error', reject);
    req.end(body);
  });
}

function control(path, method = 'GET', payload) {
  return new Promise((resolve, reject) => {
    const req = httpRequest({ host: '127.0.0.1', port: controlPort, path, method }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))));
    });
    req.on('error', reject);
    req.end(payload === undefined ? undefined : JSON.stringify(payload));
  });
}

const scenario = (name, code) =>
  control(
    '/control/inquiry-scenario',
    'POST',
    code ? { scenario: name, code } : { scenario: name },
  );

const signedOk = (r) => r.signature === sign(KEY, r.requestId, r.inquiryId, r.text);

before(async () => {
  dir = mkdtempSync(join(tmpdir(), 'payment-provider-inquiry-'));
  const made = spawnSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-keyout',
      join(dir, 'server.key'),
      '-out',
      join(dir, 'server.pem'),
      '-days',
      '1',
      '-subj',
      '/CN=localhost',
    ],
    { stdio: 'ignore' },
  );
  assert.equal(made.status, 0, 'openssl is required for this test');
  writeFileSync(join(dir, 'key'), `${KEY}\n`, { mode: 0o600 });
  httpsPort = await freePort();
  controlPort = await freePort();
  child = spawn(process.execPath, [SERVER.pathname], {
    env: {
      PATH: process.env.PATH,
      PROVIDER_TLS_DIR: dir,
      PROVIDER_HTTPS_PORT: String(httpsPort),
      PROVIDER_CONTROL_PORT: String(controlPort),
      PROVIDER_SIGNING_KEY_FILE: join(dir, 'key'),
    },
    stdio: 'ignore',
  });
  for (let i = 0; i < 100; i++) {
    try {
      await control('/health');
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 100));
    }
  }
  assert.fail('the synthetic provider did not start');
});

after(() => {
  child?.kill();
  if (dir) rmSync(dir, { recursive: true, force: true });
});

test('accepted: a signed 200 round trip with matching identifiers', async () => {
  await control('/control/reset', 'POST');
  await scenario('accepted');
  const r = await inquire();
  assert.equal(r.status, 200);
  assert.ok(signedOk(r));
  assert.deepEqual(JSON.parse(r.text), {
    inquiryId: r.inquiryId,
    requestId: r.requestId,
    result: 'ACCEPTED_FOR_PROCESSING',
  });
  const [entry] = await control('/control/inquiries');
  assert.deepEqual(entry, {
    inquiryId: r.inquiryId,
    requestId: r.requestId,
    signatureValid: true,
    replay: false,
    mismatch: false,
    result: 'ACCEPTED_FOR_PROCESSING',
  });
});

test('rejected carries the allow-listed code; default is OTHER; unknown codes are refused', async () => {
  await control('/control/reset', 'POST');
  for (const code of ['LIMIT', 'UNSUPPORTED']) {
    await scenario('rejected', code);
    const r = await inquire();
    assert.equal(r.status, 200);
    assert.ok(signedOk(r));
    assert.equal(JSON.parse(r.text).result, 'REJECTED');
    assert.equal(JSON.parse(r.text).code, code);
  }
  await scenario('rejected');
  assert.equal(JSON.parse((await inquire()).text).code, 'OTHER');
  const refused = await scenario('rejected', 'NOT_A_CODE');
  assert.deepEqual(refused, {});
  assert.deepEqual(await scenario('nonsense'), {});
});

test('pending, not-found and unknown are signed and not stored', async () => {
  await control('/control/reset', 'POST');
  const inquiryId = randomUUID();
  const requestId = randomUUID();
  for (const [name, result] of [
    ['pending', 'PENDING'],
    ['not-found', 'NOT_FOUND'],
    ['unknown', 'UNKNOWN'],
  ]) {
    await scenario(name);
    const r = await inquire({ inquiryId, requestId });
    assert.equal(r.status, 200);
    assert.ok(signedOk(r));
    assert.equal(JSON.parse(r.text).result, result);
    assert.equal(JSON.parse(r.text).code, undefined);
  }
  // recomputed from the scenario: now a final answer for the same inquiry
  await scenario('accepted');
  assert.equal(
    JSON.parse((await inquire({ inquiryId, requestId })).text).result,
    'ACCEPTED_FOR_PROCESSING',
  );
});

test('the default scenario is pending', async () => {
  await control('/control/reset', 'POST');
  assert.equal(JSON.parse((await inquire()).text).result, 'PENDING');
});

test('unsigned: valid JSON but the response signature does not verify', async () => {
  await control('/control/reset', 'POST');
  await scenario('unsigned');
  const r = await inquire();
  assert.equal(r.status, 200);
  assert.ok(JSON.parse(r.text).result);
  assert.ok(r.signature?.startsWith('v1='));
  assert.ok(!signedOk(r));
});

test('malformed: a signed non-JSON 200 body', async () => {
  await control('/control/reset', 'POST');
  await scenario('malformed');
  const r = await inquire();
  assert.equal(r.status, 200);
  assert.ok(signedOk(r));
  assert.throws(() => JSON.parse(r.text));
});

test('rate-limit-once then recovery; server-error-once then recovery', async () => {
  await control('/control/reset', 'POST');
  const inquiryId = randomUUID();
  const requestId = randomUUID();
  await scenario('rate-limit-once');
  const first = await inquire({ inquiryId, requestId });
  assert.equal(first.status, 429);
  assert.equal(first.text, '');
  assert.equal((await inquire({ inquiryId, requestId })).status, 200);
  await scenario('server-error-once');
  assert.equal((await inquire()).status, 503);
  const after = await inquire();
  assert.equal(after.status, 200);
  assert.equal(JSON.parse(after.text).result, 'ACCEPTED_FOR_PROCESSING');
});

test('drop destroys the socket without an answer', async () => {
  await control('/control/reset', 'POST');
  await scenario('drop');
  await assert.rejects(inquire());
  const log = await control('/control/inquiries');
  assert.equal(log.length, 1);
  assert.equal(log[0].signatureValid, true);
});

test('a final answer is replayed identically; a changed requestId is a 409 with no state change', async () => {
  await control('/control/reset', 'POST');
  await scenario('rejected', 'LIMIT');
  const inquiryId = randomUUID();
  const requestId = randomUUID();
  const first = await inquire({ inquiryId, requestId });
  await scenario('accepted'); // a later scenario must not change the stored answer
  const again = await inquire({ inquiryId, requestId });
  assert.equal(again.text, first.text);
  assert.equal(again.signature, first.signature);
  const other = await inquire({ inquiryId, requestId: randomUUID() });
  assert.equal(other.status, 409);
  assert.equal(other.text, '');
  const third = await inquire({ inquiryId, requestId });
  assert.equal(third.text, first.text);
  const log = await control('/control/inquiries');
  assert.deepEqual(
    log.map((e) => [e.replay, e.mismatch, e.signatureValid]),
    [
      [false, false, true],
      [true, false, true],
      [false, true, true],
      [true, false, true],
    ],
  );
  assert.equal(log[2].result, null);
});

test('a bad request signature is 401, produces no result and is logged', async () => {
  await control('/control/reset', 'POST');
  await scenario('accepted');
  const r = await inquire({ signature: sign('wrong-key', randomUUID(), randomUUID(), 'x') });
  assert.equal(r.status, 401);
  assert.equal(r.text, '');
  assert.equal(r.signature, undefined);
  const [entry] = await control('/control/inquiries');
  assert.equal(entry.signatureValid, false);
  assert.equal(entry.result, null);
  // a payment-request style signature (prefix v1) is not an inquiry signature
  const requestId = randomUUID();
  const inquiryId = randomUUID();
  const body = canonical(inquiryId, requestId);
  const paymentStyle =
    'v1=' +
    createHmac('sha256', KEY)
      .update('v1\n')
      .update(requestId)
      .update('\n')
      .update(body)
      .digest('hex');
  assert.equal((await inquire({ requestId, inquiryId, signature: paymentStyle })).status, 401);
});

test('header and body identifiers must agree; shape must be exact; oversize and bad uuids refused', async () => {
  await control('/control/reset', 'POST');
  await scenario('accepted');
  const requestId = randomUUID();
  const inquiryId = randomUUID();
  // body names a different inquiry id than the (validly signed) headers
  const swapped = canonical(randomUUID(), requestId);
  assert.equal(
    (
      await inquire({
        requestId,
        inquiryId,
        body: swapped,
        signature: sign(KEY, requestId, inquiryId, swapped),
      })
    ).status,
    400,
  );
  // extra field / whitespace / wrong schema version, all correctly signed
  for (const body of [
    JSON.stringify({ inquiryId, requestId, schemaVersion: 1, extra: 1 }),
    JSON.stringify({ inquiryId, requestId, schemaVersion: 2 }),
    JSON.stringify({ inquiryId, requestId, schemaVersion: 1 }, null, 1),
  ]) {
    const r = await inquire({
      requestId,
      inquiryId,
      body,
      signature: sign(KEY, requestId, inquiryId, body),
    });
    assert.equal(r.status, 400);
    assert.equal(r.text, '');
  }
  const big = canonical(inquiryId, requestId) + ' '.repeat(600);
  const oversize = await inquire({
    requestId,
    inquiryId,
    body: big,
    signature: sign(KEY, requestId, inquiryId, big),
  }).catch(() => ({ status: 413 }));
  assert.equal(oversize.status, 413);
  const bad = await inquire({ headerInquiryId: 'not-a-uuid' });
  assert.equal(bad.status, 400);
  assert.equal(bad.text, '');
  // none of these produced an answer or a stored result
  assert.equal(
    JSON.parse((await inquire({ requestId, inquiryId })).text).result,
    'ACCEPTED_FOR_PROCESSING',
  );
});

test('the log holds identifiers, flags and the result only; payment received log is untouched', async () => {
  await control('/control/reset', 'POST');
  await scenario('rejected', 'OTHER');
  const r = await inquire();
  const log = await control('/control/inquiries');
  assert.deepEqual(Object.keys(log[0]).sort(), [
    'inquiryId',
    'mismatch',
    'replay',
    'requestId',
    'result',
    'signatureValid',
  ]);
  const text = JSON.stringify(log);
  assert.ok(!text.includes('schemaVersion'));
  assert.ok(!text.includes(r.signature));
  assert.ok(!text.includes('v1='));
  assert.deepEqual(await control('/control/received'), []);
});

test('reset clears the log, the stored answers and the scenario', async () => {
  await control('/control/reset', 'POST');
  await scenario('rejected', 'LIMIT');
  const inquiryId = randomUUID();
  const requestId = randomUUID();
  await inquire({ inquiryId, requestId });
  await control('/control/reset', 'POST');
  assert.deepEqual(await control('/control/inquiries'), []);
  // same inquiry id with a new request id is no longer a mismatch, and the scenario is pending again
  const r = await inquire({ inquiryId, requestId: randomUUID() });
  assert.equal(r.status, 200);
  assert.equal(JSON.parse(r.text).result, 'PENDING');
});
