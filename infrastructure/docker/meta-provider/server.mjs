// MVP-111: a CONTROLLED SYNTHETIC Meta Graph API provider for the development stack and the hr-dev
// acceptance run. It is NOT Meta, never contacts Meta, and exists only to exercise Core's
// GraphApiProvider and callback boundary with throwaway data. It serves exactly the three Graph
// requests Core makes:
//   GET  /vNN.N/{phone_number_id}?fields=id
//   GET  /vNN.N/{waba_id}/message_templates?fields=...&limit=50&name=...
//   POST /vNN.N/{phone_number_id}/messages
// and, from its control port, emits Meta-style signed status callbacks to ONE fixed Core URL.
// State is in memory only. It never logs or stores a body, a token, a secret or a full number: the
// recorded sends keep the template name, language, the last two digits of the recipient, the
// parameter count and the URL-button suffix (a path), and nothing else.
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer as createHttpServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';

const TLS_DIR = process.env.PROVIDER_TLS_DIR ?? '/tls';
const HTTPS_PORT = Number(process.env.PROVIDER_HTTPS_PORT ?? 8443);
const CONTROL_PORT = Number(process.env.PROVIDER_CONTROL_PORT ?? 8099);
// The one place callbacks go. Fixed at start so the control port cannot be used to reach anything.
const CALLBACK_TARGET = process.env.PROVIDER_CALLBACK_TARGET ?? '';
const MAX_BODY = 8192;
const VERSION = /^v[0-9]{1,2}\.[0-9]{1,2}$/;

function secretFrom(fileVar, envVar) {
  const file = process.env[fileVar];
  if (file) {
    try {
      return readFileSync(file, 'utf8').trim();
    } catch {
      return '';
    }
  }
  return process.env[envVar] ?? '';
}

/**
 * Scenario modes for POST /messages. `count` of null means until changed.
 * accept, throttle429, throttle-code, server500, credential401, recipient-rejected,
 * template-error, no-id, timeout, close
 */
function fresh() {
  return {
    account: { phoneNumberId: '', wabaId: '' },
    tokens: [], // empty: any non-empty bearer token is accepted
    templates: [],
    scenario: { mode: 'accept', count: null },
    sends: [],
    messages: [], // { id, phoneNumberId }
    appSecret: null,
    verifyToken: null,
  };
}
let state = fresh();

function json(response, status, value, headers = {}) {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-store',
    ...headers,
  });
  response.end(body);
}

function graphError(response, status, code, message = 'synthetic') {
  json(response, status, { error: { message, type: 'OAuthException', code } });
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
    request.on('end', () => resolve(Buffer.concat(chunks)));
    request.on('error', reject);
  });
}

function authorized(request) {
  const header = String(request.headers.authorization ?? '');
  if (!header.startsWith('Bearer ') || header.length < 8) return false;
  const token = header.slice(7);
  if (state.tokens.length === 0) return true;
  return state.tokens.some((known) => {
    const a = Buffer.from(known);
    const b = Buffer.from(token);
    return a.length === b.length && timingSafeEqual(a, b);
  });
}

function nextMode() {
  const { mode, count } = state.scenario;
  if (count !== null) {
    state.scenario.count = count - 1;
    if (state.scenario.count <= 0) state.scenario = { mode: 'accept', count: null };
  }
  return mode;
}

function templatesFor(name) {
  return state.templates.filter((template) => template.name === name);
}

const provider = createHttpsServer(
  { key: readFileSync(`${TLS_DIR}/server.key`), cert: readFileSync(`${TLS_DIR}/server.pem`) },
  async (request, response) => {
    const url = new URL(request.url ?? '/', 'https://graph.synthetic');
    const parts = url.pathname.split('/').filter(Boolean);
    if (parts.length < 2 || !VERSION.test(parts[0])) return graphError(response, 404, 100);
    if (!authorized(request)) return graphError(response, 401, 190, 'invalid token');
    const id = parts[1];

    if (request.method === 'GET' && parts.length === 2) {
      if (id === state.account.phoneNumberId) return json(response, 200, { id });
      return graphError(response, 400, 100, 'unsupported get request');
    }

    if (request.method === 'GET' && parts.length === 3 && parts[2] === 'message_templates') {
      if (id !== state.account.wabaId) return graphError(response, 400, 100);
      const name = url.searchParams.get('name') ?? '';
      return json(response, 200, { data: templatesFor(name) });
    }

    if (request.method === 'POST' && parts.length === 3 && parts[2] === 'messages') {
      let payload;
      try {
        payload = JSON.parse((await readBody(request, MAX_BODY)).toString('utf8'));
      } catch {
        return graphError(response, 400, 100, 'malformed');
      }
      if (id !== state.account.phoneNumberId) return graphError(response, 400, 100);
      const mode = nextMode();
      const to = String(payload?.to ?? '');
      const components = payload?.template?.components ?? [];
      const body = components.find((c) => c.type === 'body');
      const button = components.find((c) => c.type === 'button');
      const record = {
        templateName: payload?.template?.name ?? null,
        language: payload?.template?.language?.code ?? null,
        toLast2: to.slice(-2),
        bodyParameters: body ? (body.parameters?.length ?? 0) : 0,
        buttonSuffix: button?.parameters?.[0]?.text ?? null,
        mode,
        providerMessageId: null,
      };
      state.sends.push(record);
      if (mode === 'throttle429') return graphError(response, 429, 4, 'throttled');
      if (mode === 'throttle-code') return graphError(response, 400, 131056, 'pair rate limit');
      if (mode === 'server500') return graphError(response, 500, 1, 'unavailable');
      if (mode === 'credential401') return graphError(response, 401, 190, 'invalid token');
      if (mode === 'recipient-rejected') return graphError(response, 400, 131026, 'undeliverable');
      if (mode === 'template-error') return graphError(response, 400, 132001, 'template');
      if (mode === 'no-id') return json(response, 200, { messaging_product: 'whatsapp' });
      if (mode === 'timeout') return; // hold the connection open
      if (mode === 'close') return request.socket.destroy();
      const messageId = 'wamid.SYN' + randomBytes(18).toString('base64url');
      record.providerMessageId = messageId;
      state.messages.push({ id: messageId, phoneNumberId: id });
      return json(response, 200, {
        messaging_product: 'whatsapp',
        contacts: [{ input: to, wa_id: to }],
        messages: [{ id: messageId }],
      });
    }
    return graphError(response, 404, 100);
  },
);

function sign(secret, body) {
  return 'sha256=' + createHmac('sha256', secret).update(body).digest('hex');
}

/** Builds a Meta-style statuses callback body from {id, status, timestamp?, errorCode?}. */
function callbackBody(phoneNumberId, statuses) {
  return JSON.stringify({
    object: 'whatsapp_business_account',
    entry: [
      {
        id: state.account.wabaId,
        changes: [
          {
            field: 'messages',
            value: {
              messaging_product: 'whatsapp',
              metadata: { display_phone_number: '000', phone_number_id: phoneNumberId },
              statuses: statuses.map((s) => ({
                id: s.id,
                status: s.status,
                timestamp: String(s.timestamp ?? Math.floor(Date.now() / 1000)),
                recipient_id: '000',
                ...(s.errorCode ? { errors: [{ code: s.errorCode }] } : {}),
              })),
            },
          },
        ],
      },
    ],
  });
}

async function emit(parsed) {
  if (!CALLBACK_TARGET) return { sent: false, reason: 'no-target' };
  const appSecret =
    state.appSecret ?? secretFrom('PROVIDER_APP_SECRET_FILE', 'PROVIDER_APP_SECRET');
  const requested = Array.isArray(parsed.statuses) ? parsed.statuses.slice(0, 50) : [];
  const statuses = requested.map((s) => ({
    id: s.id === 'last' ? (state.messages.at(-1)?.id ?? 'wamid.UNKNOWN0000') : String(s.id),
    status: String(s.status),
    timestamp: s.timestamp,
    errorCode: s.errorCode,
  }));
  const phoneNumberId = String(parsed.phoneNumberId ?? state.account.phoneNumberId);
  const body = Buffer.from(
    parsed.rawBody ? String(parsed.rawBody) : callbackBody(phoneNumberId, statuses),
  );
  const headers = { 'Content-Type': 'application/json' };
  const signature = parsed.signature ?? 'valid';
  if (signature === 'valid') headers['X-Hub-Signature-256'] = sign(appSecret, body);
  else if (signature === 'invalid')
    headers['X-Hub-Signature-256'] = sign('wrong-' + appSecret, body);
  // 'missing': no header at all.
  const answer = await fetch(CALLBACK_TARGET, { method: 'POST', headers, body });
  return { sent: true, status: answer.status };
}

async function emitVerify(parsed) {
  if (!CALLBACK_TARGET) return { sent: false, reason: 'no-target' };
  const token =
    state.verifyToken ?? secretFrom('PROVIDER_VERIFY_TOKEN_FILE', 'PROVIDER_VERIFY_TOKEN');
  const challenge = 'syn' + randomBytes(9).toString('hex');
  const query = new URLSearchParams({
    'hub.mode': 'subscribe',
    'hub.verify_token': parsed.wrongToken ? 'wrong-' + token : token,
    'hub.challenge': challenge,
  });
  const answer = await fetch(`${CALLBACK_TARGET}?${query}`);
  const text = await answer.text();
  return { sent: true, status: answer.status, echoed: text === challenge };
}

const control = createHttpServer(async (request, response) => {
  const url = new URL(request.url ?? '/', 'http://control');
  const reply = (status, value) => json(response, status, value);
  const input = async (limit = 8192) =>
    JSON.parse((await readBody(request, limit)).toString('utf8'));
  try {
    if (request.method === 'GET' && url.pathname === '/health') return reply(200, { ok: true });
    if (request.method === 'GET' && url.pathname === '/control/sends') {
      return reply(200, state.sends);
    }
    if (request.method === 'POST' && url.pathname === '/control/reset') {
      state = fresh();
      return reply(200, { ok: true });
    }
    // {phoneNumberId, wabaId, tokens?: [..]}
    if (request.method === 'POST' && url.pathname === '/control/account') {
      const parsed = await input();
      if (
        !/^[0-9]{5,20}$/.test(String(parsed.phoneNumberId)) ||
        !/^[0-9]{5,20}$/.test(String(parsed.wabaId))
      ) {
        return reply(400, {});
      }
      state.account = {
        phoneNumberId: String(parsed.phoneNumberId),
        wabaId: String(parsed.wabaId),
      };
      state.tokens = Array.isArray(parsed.tokens) ? parsed.tokens.map(String).slice(0, 5) : [];
      return reply(200, { ok: true });
    }
    // {names:[..], languages:["en","fr"], buttonUrl, bodyParameters:0|1, status?: "APPROVED"}
    if (request.method === 'POST' && url.pathname === '/control/templates') {
      const parsed = await input();
      const names = Array.isArray(parsed.names) ? parsed.names.map(String).slice(0, 30) : [];
      const languages = Array.isArray(parsed.languages)
        ? parsed.languages.map(String)
        : ['en', 'fr'];
      const bodyParameters = Number(parsed.bodyParameters ?? 1) === 1 ? 1 : 0;
      const status = String(parsed.status ?? 'APPROVED');
      state.templates = names.flatMap((name) =>
        languages.map((language) => ({
          name,
          language,
          status,
          components: [
            { type: 'BODY', text: bodyParameters ? 'Synthetic notice: {{1}}' : 'Synthetic notice' },
            { type: 'FOOTER', text: 'DivalHR' },
            {
              type: 'BUTTONS',
              buttons: [{ type: 'URL', text: 'Open', url: String(parsed.buttonUrl) }],
            },
          ],
        })),
      );
      return reply(200, { count: state.templates.length });
    }
    // {mode, count?}
    if (request.method === 'POST' && url.pathname === '/control/scenario') {
      const parsed = await input(256);
      const modes = [
        'accept',
        'throttle429',
        'throttle-code',
        'server500',
        'credential401',
        'recipient-rejected',
        'template-error',
        'no-id',
        'timeout',
        'close',
      ];
      if (!modes.includes(parsed.mode)) return reply(400, {});
      const count = parsed.count === undefined ? null : Number(parsed.count);
      state.scenario = {
        mode: parsed.mode,
        count: Number.isInteger(count) && count > 0 ? count : null,
      };
      return reply(200, state.scenario);
    }
    // Optional: set the callback secrets instead of reading the files/environment.
    if (request.method === 'POST' && url.pathname === '/control/callback-secrets') {
      const parsed = await input(512);
      state.appSecret = parsed.appSecret ? String(parsed.appSecret) : null;
      state.verifyToken = parsed.verifyToken ? String(parsed.verifyToken) : null;
      return reply(200, { ok: true });
    }
    // {statuses:[{id|"last", status:"sent|delivered|read|failed", errorCode?, timestamp?}],
    //  signature?: "valid|invalid|missing", phoneNumberId?, rawBody?}
    if (request.method === 'POST' && url.pathname === '/control/emit-callback') {
      return reply(200, await emit(await input()));
    }
    // {wrongToken?: true}
    if (request.method === 'POST' && url.pathname === '/control/emit-verify') {
      return reply(200, await emitVerify(await input(256)));
    }
    return reply(404, {});
  } catch {
    return reply(400, {});
  }
});

provider.listen(HTTPS_PORT, '0.0.0.0');
control.listen(CONTROL_PORT, '0.0.0.0');
