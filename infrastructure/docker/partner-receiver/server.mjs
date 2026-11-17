// MVP-120B: a CONTROLLED SYNTHETIC partner receiver for the development stack, the browser suite
// and hr-dev. It is NOT a lender, a bank or a credit bureau and decides nothing about anyone. It
// verifies the DivalHR handoff request exactly as docs/DECISIONS/0042-controlled-partner-handoff.md
// describes (timestamp window, HMAC-SHA256 over the exact bytes, application id as replay id,
// closed payload schema), acknowledges with a signed answer, answers signed status inquiries and
// emits signed review-status callbacks to Core. It keeps in memory only metadata: the application
// id, counts, flags and outcome codes. It never keeps, prints or returns an amount, a consent
// detail, a signature or a body, and it never logs one.
import { createHash, createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer as createHttpServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';

const TLS_DIR = process.env.PARTNER_TLS_DIR ?? '/tls';
const HTTPS_PORT = Number(process.env.PARTNER_HTTPS_PORT ?? 8444);
const CONTROL_PORT = Number(process.env.PARTNER_CONTROL_PORT ?? 8098);
const DELIVERY_KEY_FILE =
  process.env.PARTNER_SIGNING_KEY_FILE ?? '/run/secrets/partner/handoff-signing-key';
const CALLBACK_KEY_FILE =
  process.env.PARTNER_CALLBACK_KEY_FILE ?? '/run/secrets/partner/callback-signing-key';
const CALLBACK_URL =
  process.env.PARTNER_CALLBACK_URL ??
  'http://core-api:8080/api/v1/public/partners/synthetic-test/callbacks';
const MAX_BODY = 4096;
const WINDOW_SECONDS = 300;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;
const TIMESTAMP = /^[0-9]{10}$/u;

// Scenarios for the NEXT deliveries: the synthetic partner is told how to behave, never inferred.
//   accept            acknowledge every valid delivery (default)
//   reject            answer a signed REJECTED
//   silent-stored     keep the application and never answer (the ambiguous timeout; an inquiry
//                     later proves it was received)
//   silent-not-stored never keep it and never answer (an inquiry later proves it was not received)
//   server-error-once one 503 before anything is kept, then accept
//   rate-limit-once   one 429 before anything is kept, then accept
// A requested amount whose minor units end in 77 behaves as silent-stored and ending in 78 as
// silent-not-stored, so one browser journey can exercise the ambiguous path without a control call.
const SCENARIOS = new Set([
  'accept',
  'reject',
  'silent-stored',
  'silent-not-stored',
  'server-error-once',
  'rate-limit-once',
]);
const INQUIRY_SCENARIOS = new Set(['truth', 'inconclusive', 'timeout']);
const REVIEW_STATES = new Set(['UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED', 'DECLINED']);
const REASONS = new Set([
  'ADDITIONAL_DOCUMENTS',
  'CLARIFICATION',
  'PARTNER_POLICY',
  'SYNTHETIC_TEST_SCENARIO',
]);
const KEYS = [
  'applicationId',
  'ceilingMinor',
  'consentSha256',
  'consentVersion',
  'correlationId',
  'currency',
  'employerConfirmed',
  'employmentActive',
  'employmentStart',
  'issuedAt',
  'legalEntityRef',
  'payrollReferenceMonth',
  'requestId',
  'requestedMinor',
  'schemaVersion',
];

const state = {
  scenario: 'accept',
  inquiryScenario: 'truth',
  onceUsed: false,
  applications: new Map(), // applicationId -> { digest, outcome, hang, status, text, reference }
  received: [], // metadata only
  inquiries: [], // metadata only
  seenCallbackIds: new Set(),
};

function trimmedKey(inline, file) {
  const value = process.env[inline];
  if (value) return Buffer.from(value, 'utf8');
  return Buffer.from(readFileSync(file, 'utf8').trim(), 'utf8');
}
const deliveryKey = () => trimmedKey('PARTNER_SIGNING_KEY', DELIVERY_KEY_FILE);
const callbackKey = () => trimmedKey('PARTNER_CALLBACK_KEY', CALLBACK_KEY_FILE);

function mac(key, domain, id, body, timestamp) {
  const h = createHmac('sha256', key).update(domain + '\n');
  if (timestamp !== undefined) h.update(String(timestamp) + '\n');
  return (
    'v1=' +
    h
      .update(id + '\n')
      .update(body)
      .digest('hex')
  );
}

function equal(expected, presented) {
  const a = Buffer.from(expected);
  const b = Buffer.from(String(presented ?? ''));
  return a.length === b.length && timingSafeEqual(a, b);
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

function reference(applicationId) {
  return (
    'SYNTH-' + createHash('sha256').update(applicationId).digest('hex').slice(0, 12).toUpperCase()
  );
}

/** Closed schema check; returns booleans only, never a value. */
function inspect(applicationId, parsed) {
  const keys = parsed !== null && typeof parsed === 'object' ? Object.keys(parsed).sort() : [];
  const closed = keys.length === KEYS.length && keys.every((k, i) => k === KEYS[i]);
  const integer = (v) => Number.isSafeInteger(v) && v > 0;
  const uuid = (v) => typeof v === 'string' && UUID.test(v);
  const ok =
    closed &&
    parsed.schemaVersion === 1 &&
    parsed.applicationId === applicationId &&
    uuid(parsed.requestId) &&
    uuid(parsed.legalEntityRef) &&
    uuid(parsed.correlationId) &&
    parsed.employerConfirmed === true &&
    typeof parsed.employmentActive === 'boolean' &&
    (parsed.currency === 'CDF' || parsed.currency === 'USD') &&
    integer(parsed.requestedMinor) &&
    integer(parsed.ceilingMinor) &&
    parsed.requestedMinor <= parsed.ceilingMinor &&
    typeof parsed.consentVersion === 'string' &&
    /^[0-9a-f]{64}$/u.test(String(parsed.consentSha256)) &&
    typeof parsed.issuedAt === 'string' &&
    typeof parsed.employmentStart === 'string' &&
    typeof parsed.payrollReferenceMonth === 'string';
  return { closed, schemaOk: ok };
}

function signedAnswer(response, status, applicationId, value) {
  const text = JSON.stringify({ applicationId, ...value });
  response.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(text),
    'X-Divalhr-Response-Signature': mac(deliveryKey(), 'v1-ack', applicationId, Buffer.from(text)),
    Connection: 'close',
  });
  response.end(text);
}

function refuse(response, status, extra = {}) {
  response.writeHead(status, { 'Content-Length': 0, Connection: 'close', ...extra }).end();
}

function verifyRequest(request, body, domain, record) {
  const applicationId = String(request.headers['x-divalhr-application-id'] ?? '');
  const timestamp = String(request.headers['x-divalhr-timestamp'] ?? '');
  record.applicationId = UUID.test(applicationId) ? applicationId : null;
  if (!UUID.test(applicationId) || !TIMESTAMP.test(timestamp)) return null;
  const skew = Math.abs(Math.floor(Date.now() / 1000) - Number(timestamp));
  record.timestampFresh = skew <= WINDOW_SECONDS;
  record.signatureValid = equal(
    mac(deliveryKey(), domain, applicationId, body, timestamp),
    request.headers['x-divalhr-signature'],
  );
  return record.timestampFresh && record.signatureValid ? applicationId : null;
}

async function handleDelivery(request, response) {
  let body;
  try {
    body = await readBody(request, MAX_BODY);
  } catch {
    return refuse(response, 413);
  }
  const record = {
    applicationId: null,
    timestampFresh: false,
    signatureValid: false,
    replay: false,
    schemaOk: false,
    outcome: null,
  };
  state.received.push(record);
  const applicationId = verifyRequest(request, body, 'v1', record);
  if (applicationId === null) {
    record.outcome = 'REJECTED_AUTH';
    return refuse(response, record.applicationId === null ? 400 : 401);
  }
  let parsed;
  try {
    parsed = JSON.parse(body.toString('utf8'));
  } catch {
    record.outcome = 'REJECTED_INVALID_REQUEST';
    return signedAnswer(response, 400, applicationId, {
      result: 'REJECTED',
      code: 'INVALID_REQUEST',
    });
  }
  const checks = inspect(applicationId, parsed);
  record.schemaOk = checks.schemaOk;
  if (!checks.schemaOk) {
    record.outcome = 'REJECTED_INVALID_REQUEST';
    return signedAnswer(response, 400, applicationId, {
      result: 'REJECTED',
      code: 'INVALID_REQUEST',
    });
  }
  const digest = createHash('sha256').update(body).digest('hex');
  const known = state.applications.get(applicationId);
  if (known) {
    // The application id is the replay id: the same bytes return the first outcome, never a second
    // acceptance; different bytes are a mismatch.
    record.replay = true;
    if (known.digest !== digest) {
      record.outcome = 'REJECTED_DUPLICATE_MISMATCH';
      return signedAnswer(response, 409, applicationId, {
        result: 'REJECTED',
        code: 'DUPLICATE_MISMATCH',
      });
    }
    record.outcome = known.outcome + '_REPLAY';
    if (known.hang) return;
    return signedAnswer(response, known.status, applicationId, known.value);
  }
  const marker = parsed.requestedMinor % 100;
  let scenario = state.scenario;
  if (scenario === 'accept' && marker === 77) scenario = 'silent-stored';
  if (scenario === 'accept' && marker === 78) scenario = 'silent-not-stored';
  if (scenario === 'server-error-once' && !state.onceUsed) {
    state.onceUsed = true;
    record.outcome = 'SERVER_ERROR_ONCE';
    return refuse(response, 503);
  }
  if (scenario === 'rate-limit-once' && !state.onceUsed) {
    state.onceUsed = true;
    record.outcome = 'RATE_LIMITED_ONCE';
    return refuse(response, 429, { 'Retry-After': '1' });
  }
  if (scenario === 'silent-not-stored') {
    record.outcome = 'SILENT_NOT_STORED';
    return; // never kept, never answered: the caller times out
  }
  let decision;
  if (scenario === 'reject') {
    decision = {
      status: 422,
      value: { result: 'REJECTED', code: 'UNSUPPORTED' },
      outcome: 'REJECTED_UNSUPPORTED',
    };
  } else if (scenario === 'silent-stored') {
    decision = { hang: true, outcome: 'ACCEPTED_SILENT' };
  } else {
    decision = {
      status: 200,
      value: { result: 'RECEIVED', reference: reference(applicationId) },
      outcome: 'RECEIVED',
    };
  }
  state.applications.set(applicationId, { digest, ...decision });
  record.outcome = decision.outcome;
  if (decision.hang) return; // kept, never answered
  signedAnswer(response, decision.status, applicationId, decision.value);
}

async function handleInquiry(request, response) {
  let body;
  try {
    body = await readBody(request, 512);
  } catch {
    return refuse(response, 413);
  }
  const record = {
    applicationId: null,
    timestampFresh: false,
    signatureValid: false,
    result: null,
  };
  state.inquiries.push(record);
  const applicationId = verifyRequest(request, body, 'v1-inquiry', record);
  if (applicationId === null) {
    return refuse(response, record.applicationId === null ? 400 : 401);
  }
  const canonical = JSON.stringify({ applicationId, schemaVersion: 1 });
  if (body.toString('utf8') !== canonical) return refuse(response, 400);
  if (state.inquiryScenario === 'timeout') {
    record.result = 'TIMEOUT';
    return;
  }
  const known = state.applications.get(applicationId);
  let value;
  if (state.inquiryScenario === 'inconclusive') {
    value = { applicationId, result: 'UNKNOWN' };
  } else if (known) {
    value = { applicationId, result: 'RECEIVED', reference: reference(applicationId) };
  } else {
    value = { applicationId, result: 'NOT_RECEIVED' };
  }
  record.result = value.result;
  const text = JSON.stringify(value);
  response.writeHead(200, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(text),
    'X-Divalhr-Response-Signature': mac(
      deliveryKey(),
      'v1-inquiry-answer',
      applicationId,
      Buffer.from(text),
    ),
    Connection: 'close',
  });
  response.end(text);
}

const receiver = createHttpsServer(
  { key: readFileSync(`${TLS_DIR}/server.key`), cert: readFileSync(`${TLS_DIR}/server.pem`) },
  async (request, response) => {
    const path = request.url;
    if (path !== '/v1/salary-advance-handoffs' && path !== '/v1/salary-advance-handoff-inquiries') {
      response.writeHead(request.method === 'POST' ? 404 : 405).end();
      return;
    }
    if (request.method !== 'POST') {
      response.writeHead(405).end();
      return;
    }
    return path === '/v1/salary-advance-handoffs'
      ? handleDelivery(request, response)
      : handleInquiry(request, response);
  },
);

/** Emits one signed review-status callback to Core; returns only the HTTP status. */
async function emitCallback(parsed) {
  if (!UUID.test(String(parsed.applicationId))) return { error: 'applicationId' };
  if (!REVIEW_STATES.has(parsed.reviewState)) return { error: 'reviewState' };
  if (parsed.reasonCode !== undefined && !REASONS.has(parsed.reasonCode)) {
    return { error: 'reasonCode' };
  }
  const issued = new Date(Date.now() - Number(parsed.issuedSecondsAgo ?? 0) * 1000);
  const payload = {
    applicationId: parsed.applicationId,
    issuedAt: issued.toISOString().replace(/\.\d{3}Z$/u, 'Z'),
    reviewState: parsed.reviewState,
  };
  if (parsed.reasonCode !== undefined) payload.reasonCode = parsed.reasonCode;
  const text = JSON.stringify(payload);
  const callbackId =
    typeof parsed.callbackId === 'string' && UUID.test(parsed.callbackId)
      ? parsed.callbackId
      : randomUUID();
  const timestamp = Math.floor(issued.getTime() / 1000);
  let signature = mac(callbackKey(), 'v1', callbackId, Buffer.from(text), timestamp);
  if (parsed.badSignature === true) signature = 'v1=' + '0'.repeat(64);
  const result = await fetch(CALLBACK_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Divalhr-Timestamp': String(timestamp),
      'X-Divalhr-Callback-Id': callbackId,
      'X-Divalhr-Signature': signature,
    },
    body: text,
    redirect: 'manual',
    signal: AbortSignal.timeout(10_000),
  });
  return { status: result.status, callbackId };
}

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
    if (request.method === 'GET' && url.pathname === '/control/inquiries') {
      return json(200, state.inquiries);
    }
    if (request.method === 'POST' && url.pathname === '/control/scenario') {
      const parsed = JSON.parse((await readBody(request, 256)).toString('utf8'));
      if (!SCENARIOS.has(parsed.scenario)) return json(400, {});
      state.scenario = parsed.scenario;
      state.onceUsed = false;
      return json(200, { scenario: state.scenario });
    }
    if (request.method === 'POST' && url.pathname === '/control/inquiry-scenario') {
      const parsed = JSON.parse((await readBody(request, 256)).toString('utf8'));
      if (!INQUIRY_SCENARIOS.has(parsed.scenario)) return json(400, {});
      state.inquiryScenario = parsed.scenario;
      return json(200, { scenario: state.inquiryScenario });
    }
    if (request.method === 'POST' && url.pathname === '/control/callback') {
      const parsed = JSON.parse((await readBody(request, 1024)).toString('utf8'));
      const result = await emitCallback(parsed);
      return json(result.error ? 400 : 200, result);
    }
    if (request.method === 'POST' && url.pathname === '/control/reset') {
      state.scenario = 'accept';
      state.inquiryScenario = 'truth';
      state.onceUsed = false;
      state.applications = new Map();
      state.received = [];
      state.inquiries = [];
      state.seenCallbackIds = new Set();
      return json(200, { ok: true });
    }
    return json(404, {});
  } catch {
    return json(400, {});
  }
});

receiver.listen(HTTPS_PORT, '0.0.0.0');
control.listen(CONTROL_PORT, '0.0.0.0');
