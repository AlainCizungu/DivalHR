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

// MVP-095B (R187-1): the synthetic payment provider on hr-dev reads its signing key from the protected
// file, which ends with a newline. Core reads the same file through Spring's config tree, which strips
// that newline, so the provider must sign and verify with the trimmed value. Synthetic key only.

const SERVER = new URL('../../infrastructure/docker/payment-provider/server.mjs', import.meta.url);
const KEY = 'test-only-payment-submission-signing-key-0001';
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

const sign = (key, id, body) =>
  'v1=' +
  createHmac('sha256', key).update('v1\n').update(id).update('\n').update(body).digest('hex');

function post(id, signature, body) {
  return new Promise((resolve, reject) => {
    const req = request(
      {
        host: '127.0.0.1',
        port: httpsPort,
        path: '/v1/payment-requests',
        method: 'POST',
        rejectUnauthorized: false,
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(body),
          'X-Divalhr-Request-Id': id,
          'X-Divalhr-Key-Version': '1',
          'X-Divalhr-Signature': signature,
        },
      },
      (res) => {
        res.resume();
        res.on('end', () => resolve(res.statusCode));
      },
    );
    req.on('error', reject);
    req.end(body);
  });
}

function control(path, method = 'GET') {
  return new Promise((resolve, reject) => {
    const req = httpRequest({ host: '127.0.0.1', port: controlPort, path, method }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))));
    });
    req.on('error', reject);
    req.end();
  });
}

before(async () => {
  dir = mkdtempSync(join(tmpdir(), 'payment-provider-key-'));
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

test('a key file that ends with a newline is used trimmed, as Core reads it', async () => {
  await control('/control/reset', 'POST');
  const id = randomUUID();
  const body = JSON.stringify({
    requestId: id,
    lineCount: 1,
    totalMinor: 100,
    currency: 'CDF',
    lines: [],
  });
  await post(id, sign(KEY, id, body), body);
  const [entry] = await control('/control/received');
  assert.equal(entry.requestId, id);
  assert.equal(entry.signatureValid, true);
});

test('the raw file content, newline included, is not the signing key', async () => {
  await control('/control/reset', 'POST');
  const id = randomUUID();
  const body = JSON.stringify({ requestId: id });
  await post(id, sign(`${KEY}\n`, id, body), body);
  const [entry] = await control('/control/received');
  assert.equal(entry.signatureValid, false);
  assert.equal(entry.outcome, 'REJECTED_SIGNATURE');
});
