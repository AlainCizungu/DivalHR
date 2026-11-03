// MVP-095B: a CONTROLLED SYNTHETIC payment provider for the development stack, the browser suite and
// hr-dev. It is NOT a bank or a mobile-money operator and moves no money. It verifies the DivalHR
// request signature exactly as docs/OPS-HR-DEV.md describes, honours the stable request ID
// (a replay returns the first outcome and never a second acceptance), signs every answer it
// decides, and keeps in memory only metadata: the request ID, counts, totals, flags and the
// outcome. It never keeps, prints or returns an account, a phone number, a holder name or a
// signature, and it never logs a body.
import { createHash, createHmac, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer as createHttpServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';

const TLS_DIR = process.env.PROVIDER_TLS_DIR ?? '/tls';
const HTTPS_PORT = Number(process.env.PROVIDER_HTTPS_PORT ?? 8443);
const CONTROL_PORT = Number(process.env.PROVIDER_CONTROL_PORT ?? 8099);
const KEY_FILE =
  process.env.PROVIDER_SIGNING_KEY_FILE ?? '/run/secrets/provider/payment-signing-key';
const MAX_BODY = 2 * 1024 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;
const SCENARIOS = new Set(['accept', 'reject', 'unknown', 'server-error-once', 'rate-limit-once']);

/** @type {{scenario: string, onceUsed: boolean, requests: Map<string, object>, received: object[]}} */
const state = { scenario: 'accept', onceUsed: false, requests: new Map(), received: [] };

function key() {
  // Development stack only: the key may come from PROVIDER_SIGNING_KEY; hr-dev uses the protected file.
  const inline = process.env.PROVIDER_SIGNING_KEY;
  if (inline) return Buffer.from(inline, 'utf8');
  return readFileSync(KEY_FILE);
}

function sign(requestId, body) {
  return (
    'v1=' +
    createHmac('sha256', key())
      .update('v1\n')
      .update(requestId)
      .update('\n')
      .update(body)
      .digest('hex')
  );
}

function verify(requestId, body, presented) {
  const expected = Buffer.from(sign(requestId, body));
  const given = Buffer.from(String(presented ?? ''));
  return given.length === expected.length && timingSafeEqual(given, expected);
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

/** Checks the payload against the contract; returns metadata only. */
function inspect(requestId, parsed) {
  const lines = Array.isArray(parsed.lines) ? parsed.lines : [];
  let sum = 0;
  let linesOk = true;
  const refs = new Set();
  for (const line of lines) {
    const bank = line.method === 'BANK_ACCOUNT';
    const mobile = line.method === 'MOBILE_MONEY';
    const amount =
      Number.isSafeInteger(line.amountMinor) && line.amountMinor > 0 ? line.amountMinor : 0;
    sum += amount;
    const destinationOk =
      (bank &&
        typeof line.account === 'string' &&
        line.account.length > 2 &&
        line.phone === null) ||
      (mobile && typeof line.phone === 'string' && line.phone.length > 2 && line.account === null);
    if (
      !(bank || mobile) ||
      amount === 0 ||
      !destinationOk ||
      typeof line.holder !== 'string' ||
      line.holder.length < 2 ||
      typeof line.provider !== 'string' ||
      typeof line.country !== 'string' ||
      typeof line.lineRef !== 'string' ||
      refs.has(line.lineRef)
    ) {
      linesOk = false;
    }
    refs.add(line.lineRef);
  }
  return {
    schemaVersion: parsed.schemaVersion,
    requestIdMatches: parsed.requestId === requestId,
    lineCount: lines.length,
    lineCountMatches: parsed.lineCount === lines.length,
    totalMinor: sum,
    totalMatches: parsed.totalMinor === sum,
    currency: typeof parsed.currency === 'string' ? parsed.currency : null,
    linesOk,
  };
}

function answer(response, status, requestId, value) {
  const text = JSON.stringify({ requestId, ...value });
  response.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(text),
    'X-Divalhr-Response-Signature': sign(requestId, Buffer.from(text)),
    Connection: 'close',
  });
  response.end(text);
}

function reference(requestId) {
  return 'SYNTH-' + createHash('sha256').update(requestId).digest('hex').slice(0, 12).toUpperCase();
}

const provider = createHttpsServer(
  { key: readFileSync(`${TLS_DIR}/server.key`), cert: readFileSync(`${TLS_DIR}/server.pem`) },
  async (request, response) => {
    if (request.method !== 'POST' || request.url !== '/v1/payment-requests') {
      response.writeHead(request.method === 'POST' ? 404 : 405).end();
      return;
    }
    let body;
    try {
      body = await readBody(request, MAX_BODY);
    } catch {
      response.writeHead(413).end();
      return;
    }
    const requestId = String(request.headers['x-divalhr-request-id'] ?? '');
    const record = {
      requestId: UUID.test(requestId) ? requestId : null,
      keyVersion: Number(request.headers['x-divalhr-key-version'] ?? 0),
      signatureValid: false,
      replay: false,
      outcome: null,
      lineCount: 0,
      totalMinor: 0,
      checks: null,
    };
    state.received.push(record);
    if (!UUID.test(requestId)) {
      record.outcome = 'MALFORMED';
      response.writeHead(400).end();
      return;
    }
    record.signatureValid = verify(requestId, body, request.headers['x-divalhr-signature']);
    if (!record.signatureValid) {
      record.outcome = 'REJECTED_SIGNATURE';
      return answer(response, 400, requestId, { result: 'REJECTED', code: 'SIGNATURE' });
    }
    let parsed;
    try {
      parsed = JSON.parse(body.toString('utf8'));
    } catch {
      record.outcome = 'REJECTED_INVALID_REQUEST';
      return answer(response, 400, requestId, { result: 'REJECTED', code: 'INVALID_REQUEST' });
    }
    record.checks = inspect(requestId, parsed);
    record.lineCount = record.checks.lineCount;
    record.totalMinor = record.checks.totalMinor;
    const digest = createHash('sha256').update(body).digest('hex');
    const known = state.requests.get(requestId);
    if (known) {
      record.replay = true;
      if (known.digest !== digest) {
        record.outcome = 'REJECTED_DUPLICATE_MISMATCH';
        return answer(response, 409, requestId, { result: 'REJECTED', code: 'DUPLICATE_MISMATCH' });
      }
      record.outcome = known.outcome + '_REPLAY';
      if (known.hang) return; // the same silence as the first time
      return answer(response, known.status, requestId, known.value);
    }
    const c = record.checks;
    const valid =
      c.requestIdMatches && c.lineCountMatches && c.totalMatches && c.linesOk && c.lineCount > 0;
    let decision;
    if (!valid) {
      decision = {
        status: 400,
        value: { result: 'REJECTED', code: 'INVALID_REQUEST' },
        outcome: 'REJECTED_INVALID_REQUEST',
      };
    } else if (state.scenario === 'reject') {
      decision = {
        status: 422,
        value: { result: 'REJECTED', code: 'UNSUPPORTED' },
        outcome: 'REJECTED_UNSUPPORTED',
      };
    } else if (state.scenario === 'unknown') {
      decision = { hang: true, outcome: 'ACCEPTED_SILENT' };
    } else if (state.scenario === 'server-error-once' && !state.onceUsed) {
      state.onceUsed = true;
      record.outcome = 'SERVER_ERROR_ONCE';
      response.writeHead(503).end();
      return; // not remembered: the retry is a first delivery
    } else if (state.scenario === 'rate-limit-once' && !state.onceUsed) {
      state.onceUsed = true;
      record.outcome = 'RATE_LIMITED_ONCE';
      response.writeHead(429, { 'Retry-After': '1' }).end();
      return;
    } else {
      decision = {
        status: 200,
        value: { result: 'ACCEPTED', reference: reference(requestId) },
        outcome: 'ACCEPTED',
      };
    }
    state.requests.set(requestId, { digest, ...decision });
    record.outcome = decision.outcome;
    if (decision.hang) return; // never answers: the caller times out after sending
    answer(response, decision.status, requestId, decision.value);
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
      state.requests = new Map();
      state.scenario = 'accept';
      state.onceUsed = false;
      return json(200, { ok: true });
    }
    if (request.method === 'POST' && url.pathname === '/control/scenario') {
      const parsed = JSON.parse((await readBody(request, 256)).toString('utf8'));
      if (!SCENARIOS.has(parsed.scenario)) return json(400, {});
      state.scenario = parsed.scenario;
      state.onceUsed = false;
      return json(200, { scenario: state.scenario });
    }
    return json(404, {});
  } catch {
    return json(400, {});
  }
});

provider.listen(HTTPS_PORT, '0.0.0.0');
control.listen(CONTROL_PORT, '0.0.0.0');
