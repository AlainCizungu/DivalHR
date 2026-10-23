// MVP-110: a CONTROLLED SYNTHETIC webhook receiver for the development stack and the browser suite.
// It exists only in infrastructure/docker/compose.yaml (never in hr-dev or any shared
// environment), holds nothing but in-memory counters and event identifiers, and verifies the
// DivalHR signature exactly as docs/WEBHOOKS.md tells customers to. It never logs or stores a
// body, a header value, a secret or a signature.
import { createHmac, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer as createHttpServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';

const TLS_DIR = process.env.RECEIVER_TLS_DIR ?? '/tls';
const HTTPS_PORT = Number(process.env.RECEIVER_HTTPS_PORT ?? 8443);
const CONTROL_PORT = Number(process.env.RECEIVER_CONTROL_PORT ?? 8099);
const TOLERANCE_SECONDS = 300;
const MAX_BODY = 8192;

/** @type {{secrets: Buffer[], status: number, received: object[]}} */
const state = { secrets: [], status: 204, received: [] };

function verify(headers, body) {
  const timestamp = Number(headers['x-divalhr-timestamp']);
  const header = String(headers['x-divalhr-signature'] ?? '');
  if (!Number.isInteger(timestamp) || state.secrets.length === 0) return false;
  if (Math.abs(Date.now() / 1000 - timestamp) > TOLERANCE_SECONDS) return false;
  const signatures = header.split(',').map((value) => value.trim());
  return state.secrets.some((key) => {
    const expected = Buffer.from(
      'v1=' +
        createHmac('sha256', key)
          .update(String(timestamp) + '\n')
          .update(body)
          .digest('hex'),
    );
    return signatures.some((candidate) => {
      const given = Buffer.from(candidate);
      return given.length === expected.length && timingSafeEqual(given, expected);
    });
  });
}

function readBody(request, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('too large'));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => {
      resolve(Buffer.concat(chunks));
    });
    request.on('error', reject);
  });
}

const receiver = createHttpsServer(
  {
    key: readFileSync(`${TLS_DIR}/server.key`),
    cert: readFileSync(`${TLS_DIR}/server.pem`),
  },
  async (request, response) => {
    if (request.method !== 'POST') {
      response.writeHead(405).end();
      return;
    }
    let body;
    try {
      body = await readBody(request, MAX_BODY);
    } catch {
      response.writeHead(413).end();
      return;
    }
    let eventType = null;
    try {
      eventType = JSON.parse(body.toString('utf8')).eventType ?? null;
    } catch {
      eventType = null;
    }
    state.received.push({
      eventId: request.headers['x-divalhr-event-id'] ?? null,
      deliveryId: request.headers['x-divalhr-delivery-id'] ?? null,
      attempt: Number(request.headers['x-divalhr-attempt'] ?? 0),
      eventType,
      signatureValid: verify(request.headers, body),
      userAgent: request.headers['user-agent'] ?? null,
      status: state.status,
    });
    response.writeHead(state.status).end();
  },
);

const control = createHttpServer(async (request, response) => {
  const url = new URL(request.url ?? '/', 'http://control');
  const json = (status, value) => {
    response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(value));
  };
  try {
    if (request.method === 'GET' && url.pathname === '/health') return json(200, { ok: true });
    if (request.method === 'GET' && url.pathname === '/control/received') {
      return json(200, state.received);
    }
    if (request.method === 'POST' && url.pathname === '/control/reset') {
      state.received = [];
      state.secrets = [];
      state.status = 204;
      return json(200, { ok: true });
    }
    if (request.method === 'POST' && url.pathname === '/control/secrets') {
      const parsed = JSON.parse((await readBody(request, 2048)).toString('utf8'));
      const list = Array.isArray(parsed.secrets) ? parsed.secrets : [];
      state.secrets = list.map((value) => Buffer.from(String(value), 'base64url'));
      return json(200, { count: state.secrets.length });
    }
    if (request.method === 'POST' && url.pathname === '/control/status') {
      const parsed = JSON.parse((await readBody(request, 256)).toString('utf8'));
      const status = Number(parsed.status);
      if (!Number.isInteger(status) || status < 200 || status > 599) return json(400, {});
      state.status = status;
      return json(200, { status });
    }
    return json(404, {});
  } catch {
    return json(400, {});
  }
});

receiver.listen(HTTPS_PORT, '0.0.0.0');
control.listen(CONTROL_PORT, '0.0.0.0');
