// SEC-001, SEC-002 and SEC-003: static tests of the access-log policy (Caddy of the test environment, nginx
// of the web image), mutation checks of that policy, and the wiring of the runtime canary test and
// of the hr-dev log check. Run: pnpm ops:test
import assert from 'node:assert/strict';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  REQUEST_HEADERS_DELETED,
  REQUEST_HEADERS_DIAGNOSTIC,
  caddyAccessLogSnippet,
  checkCaddy,
  checkNginx,
  contractRequestHeaders,
  unclassifiedRequestHeaders,
} from './access-log-policy.mjs';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const read = (path) => readFileSync(join(ROOT, path), 'utf8');
const CADDYFILE = read('infrastructure/hr-dev/caddy/Caddyfile');
const TEMPLATE = read('apps/web/docker/default.conf.template');

// SEC-003 (R174-1): the fields Caddy deletes on each side. Request and response are symmetric for
// the receipt metadata; the webhook signature is a request header only.
const SEC003_HEADERS = {
  'request>headers': [
    'X-Receipt-File-Name',
    'Content-Disposition',
    'Etag',
    'Digest',
    'Content-Digest',
    'Repr-Digest',
    'X-Hub-Signature-256',
  ],
  resp_headers: [
    'X-Receipt-File-Name',
    'Content-Disposition',
    'Etag',
    'Digest',
    'Content-Digest',
    'Repr-Digest',
  ],
};

const without = (text, pattern) => {
  const next = text
    .split('\n')
    .filter((line) => !pattern.test(line))
    .join('\n');
  assert.notEqual(next, text, `mutation ${pattern} changed nothing`);
  return next;
};
const replace = (text, from, to) => {
  assert.ok(text.includes(from), `mutation source missing: ${from}`);
  return text.replace(from, to);
};

test('the committed Caddyfile deletes the fields that carry OIDC data and drops queries', () => {
  assert.deepEqual(checkCaddy(CADDYFILE), []);
  const snippet = caddyAccessLogSnippet(CADDYFILE);
  for (const field of [
    'request>headers>Referer',
    'resp_headers>Location',
    'request>headers>Idempotency-Key',
    'request>headers>X-Receipt-File-Name',
    'resp_headers>Content-Disposition',
    'resp_headers>Etag',
    'request>headers>X-Hub-Signature-256',
  ]) {
    assert.match(snippet, new RegExp(`^\\s*${field} delete$`, 'm'), field);
  }
  // The three public sites log through the filter; the internal identity route and the operator
  // site have no access log at all.
  for (const site of ['{$HR_DEV_HOST} {', 'http://{$HR_DEV_HOST} {', 'http:// {']) {
    const start = CADDYFILE.indexOf(`\n${site}\n`);
    assert.ok(start >= 0, site);
    const block = CADDYFILE.slice(start, CADDYFILE.indexOf('\n}\n', start));
    assert.match(block, /^\s*import accesslog$/m, `${site} imports the filtered log`);
  }
  assert.equal((CADDYFILE.match(/^\s*import accesslog$/gm) ?? []).length, 3);
});

test('every removed or weakened Caddy redaction is a violation', () => {
  const mutations = {
    'Referer kept': without(CADDYFILE, /request>headers>Referer delete/),
    'Location kept': without(CADDYFILE, /resp_headers>Location delete/),
    'Authorization kept': without(CADDYFILE, /request>headers>Authorization delete/),
    'Cookie kept': without(CADDYFILE, /request>headers>Cookie delete/),
    'Set-Cookie kept': without(CADDYFILE, /resp_headers>Set-Cookie delete/),
    'Idempotency-Key kept': without(CADDYFILE, /request>headers>Idempotency-Key delete/),
    'Idempotency-Key rewritten instead of deleted': replace(
      CADDYFILE,
      'request>headers>Idempotency-Key delete',
      'request>headers>Idempotency-Key replace REDACTED',
    ),
    // SEC-003: receipt metadata and webhook signature, request and response side, independently.
    'receipt file name rewritten instead of deleted': replace(
      CADDYFILE,
      'request>headers>X-Receipt-File-Name delete',
      'request>headers>X-Receipt-File-Name replace REDACTED',
    ),
    'webhook signature rewritten instead of deleted': replace(
      CADDYFILE,
      'request>headers>X-Hub-Signature-256 delete',
      'request>headers>X-Hub-Signature-256 replace REDACTED',
    ),
    'query kept': without(CADDYFILE, /request>uri regexp/),
    'Location rewritten instead of deleted': replace(
      CADDYFILE,
      'resp_headers>Location delete',
      'resp_headers>Location regexp "\\?.*$" ""',
    ),
    'Referer rewritten instead of deleted': replace(
      CADDYFILE,
      'request>headers>Referer delete',
      'request>headers>Referer replace REDACTED',
    ),
    'query only partly removed': replace(CADDYFILE, 'regexp "\\?.*$" ""', 'regexp "code=[^&]*" ""'),
    'unfiltered log': replace(CADDYFILE, 'format filter {', 'format json {'),
  };
  // Every SEC-003 deletion, removed alone, is a violation (13 of them).
  for (const side of ['request>headers', 'resp_headers']) {
    for (const header of SEC003_HEADERS[side]) {
      const field = `${side}>${header}`;
      mutations[`${field} kept`] = without(CADDYFILE, new RegExp(`${field} delete`));
    }
  }
  for (const [name, text] of Object.entries(mutations)) {
    assert.notDeepEqual(checkCaddy(text), [], name);
  }
});

test('the web nginx template uses only the safe format, assigned explicitly', () => {
  assert.deepEqual(checkNginx(TEMPLATE), []);
  assert.match(TEMPLATE, /^\s*access_log \/dev\/stdout divalhr_safe;$/m);
  // The health check stays unlogged.
  assert.match(TEMPLATE, /location = \/healthz \{\s*access_log off;/);
});

test('every unsafe nginx logging change is a violation', () => {
  const format = '"$request_method $divalhr_path $server_protocol"';
  const mutations = {
    'request line': replace(TEMPLATE, format, '"$request"'),
    'raw URI': replace(TEMPLATE, '$divalhr_path $server_protocol', '$request_uri $server_protocol'),
    query: replace(
      TEMPLATE,
      '$divalhr_path $server_protocol',
      '$divalhr_path?$args $server_protocol',
    ),
    'query string': replace(
      TEMPLATE,
      '$divalhr_path $server_protocol',
      '$divalhr_path$is_args$query_string $server_protocol',
    ),
    referrer: replace(TEMPLATE, '$request_time', '$request_time "$http_referer"'),
    cookie: replace(TEMPLATE, '$request_time', '$request_time $http_cookie'),
    'one cookie': replace(TEMPLATE, '$request_time', '$request_time $cookie_KEYCLOAK_IDENTITY'),
    authorization: replace(TEMPLATE, '$request_time', '$request_time $http_authorization'),
    'idempotency key': replace(TEMPLATE, '$request_time', '$request_time $http_idempotency_key'),
    'redirect target': replace(TEMPLATE, '$request_time', '$request_time $sent_http_location'),
    'one argument': replace(TEMPLATE, '$request_time', '$request_time $arg_code'),
    // SEC-003
    'receipt file name': replace(
      TEMPLATE,
      '$request_time',
      '$request_time $http_x_receipt_file_name',
    ),
    'request content disposition': replace(
      TEMPLATE,
      '$request_time',
      '$request_time $http_content_disposition',
    ),
    'response content disposition': replace(
      TEMPLATE,
      '$request_time',
      '$request_time $sent_http_content_disposition',
    ),
    'response entity tag': replace(TEMPLATE, '$request_time', '$request_time $sent_http_etag'),
    'webhook signature': replace(
      TEMPLATE,
      '$request_time',
      '$request_time $http_x_hub_signature_256',
    ),
    'request entity tag': replace(TEMPLATE, '$request_time', '$request_time $http_etag'),
    'request digest': replace(TEMPLATE, '$request_time', '$request_time $http_digest'),
    'request content digest': replace(
      TEMPLATE,
      '$request_time',
      '$request_time $http_content_digest',
    ),
    'request repr digest': replace(TEMPLATE, '$request_time', '$request_time $http_repr_digest'),
    'response digest': replace(TEMPLATE, '$request_time', '$request_time $sent_http_digest'),
    'response content digest': replace(
      TEMPLATE,
      '$request_time',
      '$request_time $sent_http_content_digest',
    ),
    'response repr digest': replace(
      TEMPLATE,
      '$request_time',
      '$request_time $sent_http_repr_digest',
    ),
    'response receipt file name': replace(
      TEMPLATE,
      '$request_time',
      '$request_time $sent_http_x_receipt_file_name',
    ),
    'any upstream header': replace(
      TEMPLATE,
      '$request_time',
      '$request_time $upstream_http_content_disposition',
    ),
    'image default format': replace(
      TEMPLATE,
      'access_log /dev/stdout divalhr_safe;',
      'access_log /dev/stdout main;',
    ),
    'no explicit access_log': without(TEMPLATE, /access_log \/dev\/stdout divalhr_safe;/),
    'second access_log': replace(
      TEMPLATE,
      'access_log /dev/stdout divalhr_safe;',
      'access_log /dev/stdout divalhr_safe;\n    access_log /dev/stderr combined;',
    ),
    'path from the raw URI via map': replace(
      TEMPLATE,
      'log_format divalhr_safe',
      'map $request_uri $leak { default $request_uri; }\nlog_format divalhr_safe',
    ),
    'path not from $uri': replace(
      TEMPLATE,
      'set $divalhr_path $uri;',
      'set $divalhr_path $request_uri;',
    ),
  };
  for (const [name, text] of Object.entries(mutations)) {
    assert.notDeepEqual(checkNginx(text), [], name);
  }
});

test('the runtime canary test runs in CI with the pinned images, and the hr-dev log check is wired', () => {
  const workflow = read('.github/workflows/e2e.yml');
  assert.match(workflow, /bash scripts\/ops\/access-log-canary\.sh/);
  const canary = read('scripts/ops/access-log-canary.sh');
  const caddyImage = read('infrastructure/hr-dev/compose.yaml').match(/image: (caddy:\S+)/)[1];
  const nginxImage = read('apps/web/Dockerfile').match(/^FROM (nginxinc\/\S+)/m)[1];
  // The script reads both pins from these files, so they cannot drift apart.
  assert.match(canary, /infrastructure\/hr-dev\/compose\.yaml/);
  assert.match(canary, /apps\/web\/Dockerfile/);
  assert.match(caddyImage, /@sha256:[0-9a-f]{64}$/);
  assert.match(nginxImage, /@sha256:[0-9a-f]{64}$/);
  // Canaries are unique per run, and every required redaction has a mutation that must leak.
  assert.match(canary, /\/dev\/urandom/);
  for (const field of [
    'Referer',
    'Location',
    'Authorization',
    'Cookie',
    'Set-Cookie',
    'Idempotency-Key',
    'X-Receipt-File-Name',
    'Content-Disposition',
    'ETag',
    'X-Hub-Signature-256',
  ]) {
    assert.ok(canary.includes(field), `mutation for ${field}`);
  }
  // SEC-003 (R174-1): every Caddy deletion has its own mutation in the canary, and the success
  // label names SEC-003.
  for (const [side, headers] of Object.entries(SEC003_HEADERS)) {
    for (const header of headers) {
      assert.ok(
        canary.includes(`'${side}>${header} delete'`),
        `canary mutation for ${side}>${header}`,
      );
    }
  }
  assert.match(canary, /ALL PASS \(SEC-001, SEC-002 and SEC-003 access-log canaries/);
  const browser = read('ops/hr-dev/rehearsal-browser.sh');
  assert.match(browser, /log-hygiene\.sh/);
  const hygiene = read('ops/hr-dev/log-hygiene.sh');
  assert.match(hygiene, /secrets/);
  assert.doesNotMatch(hygiene, /grep[^\n]*-o\b/, 'never prints a matched value');
});

// --- log-hygiene.sh, with fake docker and id ------------------------------------------------------
function hygieneWorld(logs, suite) {
  const dir = mkdtempSync(join(tmpdir(), 'hygiene-'));
  const bin = join(dir, 'bin');
  const data = join(dir, 'data');
  for (const d of [
    bin,
    join(data, 'secrets/postgres'),
    join(data, 'secrets/ops/hr-dev-acceptance'),
    join(dir, 'logs'),
  ]) {
    mkdirSync(d, { recursive: true });
  }
  writeFileSync(join(data, 'secrets/postgres/POSTGRES_PASSWORD'), 'pgSecretValue0123456789abcdef');
  writeFileSync(
    join(data, 'secrets/ops/kc-admin.env'),
    "KC_ADMIN_USER='divalhr-operator'\nKC_ADMIN_PASSWORD='opSecretValue0123456789'\n",
  );
  writeFileSync(
    join(data, 'secrets/ops/hr-dev-acceptance/tenant-admin.env'),
    "# synthetic administrator\nHR_DEV_TENANT_ADMIN_USER='admin.synthetic'\nHR_DEV_TENANT_ADMIN_PASSWORD='adminSecret0123456'\nHR_DEV_TENANT_ADMIN_TOTP_KEY='JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP'\n",
  );
  writeFileSync(
    join(data, 'secrets/ops/hr-dev-acceptance/organization.txt'),
    '0f3c9a52-1111-4222-8333-944455556666\n',
  );
  for (const [name, text] of Object.entries(logs)) writeFileSync(join(dir, 'logs', name), text);
  writeFileSync(
    join(bin, 'docker'),
    `#!/bin/bash\ncase "$1" in\n  ps) ls "${join(dir, 'logs')}" ;;\n  logs) cat "${join(dir, 'logs')}/\${@: -1}" ;;\nesac\n`,
  );
  writeFileSync(join(bin, 'id'), '#!/bin/bash\necho 0\n');
  writeFileSync(join(bin, 'sudo'), '#!/bin/bash\nexit "${FAKE_SUITE:-0}"\n');
  chmodSync(join(bin, 'docker'), 0o755);
  chmodSync(join(bin, 'id'), 0o755);
  chmodSync(join(bin, 'sudo'), 0o755);
  if (suite !== undefined) {
    // rehearsal-browser.sh: the browser suite (fake sudo) and then the log check.
    return spawnSync('bash', [join(ROOT, 'ops/hr-dev/rehearsal-browser.sh'), '28443', '28180'], {
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        HR_DEV_DATA: data,
        HR_DEV_PROJECT: 'p',
        HR_DEV_ORIGIN: 'https://hr-dev.example.test',
        HR_DEV_HOST: 'hr-dev.example.test',
        REHEARSAL_USER: 'verifier',
        REHEARSAL_REPO: dir,
        FAKE_SUITE: String(suite),
      },
    });
  }
  return spawnSync(
    'bash',
    [join(ROOT, 'ops/hr-dev/log-hygiene.sh'), '--since', '2026-10-09T00:00:00Z', '--project', 'p'],
    {
      encoding: 'utf8',
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, HR_DEV_DATA: data },
    },
  );
}
const CLEAN =
  [
    '{"logger":"http.log.access.log0","request":{"method":"GET","uri":"/auth/callback"},"status":200}',
    '[2026-10-09T02:45:52+00:00] "GET /auth/callback HTTP/1.1" 200 696 0.001',
    'WARN [org.keycloak.events] type="LOGIN", realmName="divalhr-test", clientId="divalhr-web", redirect_uri="https://hr-dev.dival.ai/auth/callback", code_id="x"',
    'tenant 0f3c9a52-1111-4222-8333-944455556666 operator divalhr-operator; cookie KEYCLOAK_IDENTITY not found',
  ].join('\n') + '\n';

test('log-hygiene passes clean logs of every container and prints counts only', () => {
  const result = hygieneWorld({ 'p-caddy-1': CLEAN, 'p-web-1': CLEAN });
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(
    result.stdout,
    /p-caddy-1: 4 lines, secret values 0, token\/cookie\/code\/credential shapes 0/,
  );
  assert.match(result.stdout, /4 secret values looked for/);
  assert.match(result.stdout, /^PASS /m);
});

test('log-hygiene fails on a secret value or an authorization shape, without printing it', () => {
  const leaks = {
    'database password': 'connect with pgSecretValue0123456789abcdef',
    'operator password': 'login opSecretValue0123456789',
    'authenticator key': 'totp JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP',
    'callback code': '"GET /auth/callback?state=s123456&code=c0de0123456789 HTTP/1.1" 200',
    session_state: 'Location: https://x/auth/callback?session_state=abcdef0123',
    'bearer token': 'Authorization: Bearer abcdefghijklmnopqrstuvwxyz',
    jwt: 'token eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig',
    // Built at run time: a literal cookie assignment would itself trip the secret scanner.
    'session cookie': ['Cookie: KEYCLOAK_IDENTITY', 'cafe'.repeat(4)].join('='),
    'password assignment': 'form password=hunter22secret',
    // SEC-002: Caddy's JSON access log records request headers as arrays of values.
    'idempotency key header (JSON)': '{"headers":{"Idempotency-Key":["web-key-0123456789abcdef"]}}',
    'idempotency key header (raw)': 'Idempotency-Key: web-key-0123456789abcdef',
  };
  for (const [name, line] of Object.entries(leaks)) {
    const result = hygieneWorld({ 'p-caddy-1': CLEAN, 'p-web-1': `${CLEAN}${line}\n` });
    assert.equal(result.status, 1, name);
    assert.match(result.stdout, /^FAIL /m, name);
    for (const word of line.split(/[\s=&?]+/).filter((w) => w.length >= 10)) {
      assert.ok(
        !result.stdout.includes(word) && !result.stderr.includes(word),
        `${name}: printed a value`,
      );
    }
  }
});

test('the rehearsal browser step fails when either the suite or the log check fails', () => {
  const clean = { 'p-caddy-1': CLEAN };
  const leaking = {
    'p-caddy-1': `${CLEAN}"GET /auth/callback?code=c0de0123456789 HTTP/1.1" 200\n`,
  };
  assert.equal(hygieneWorld(clean, 0).status, 0, 'suite and log check pass');
  assert.notEqual(hygieneWorld(clean, 1).status, 0, 'a failing suite fails the step');
  const leaked = hygieneWorld(leaking, 0);
  assert.notEqual(leaked.status, 0, 'a log leak fails the step');
  assert.match(leaked.stdout, /^FAIL secrets or authorization data/m);
});

// --- SEC-003: receipt metadata in access logs --------------------------------------------------
const API_SPEC = read('docs/API-SPEC.yaml');

test('SEC-003: every request header of the API contract is classified, so none is logged unreviewed', () => {
  // A new `in: header` parameter fails here until it is deleted in Caddy or reviewed as diagnostic.
  assert.deepEqual(unclassifiedRequestHeaders(API_SPEC), []);
  const declared = contractRequestHeaders(API_SPEC);
  assert.ok(declared.includes('X-Receipt-File-Name'), 'the receipt header is in the contract');
  // Every header classified as deleted is deleted in the committed Caddyfile.
  const snippet = caddyAccessLogSnippet(CADDYFILE);
  for (const name of REQUEST_HEADERS_DELETED) {
    assert.match(snippet, new RegExp(`^\\s*request>headers>${name} delete$`, 'm'), name);
  }
  // R174-1: exactly these three are deleted, and only these two are diagnostic.
  assert.deepEqual([...REQUEST_HEADERS_DELETED].sort(), [
    'Idempotency-Key',
    'X-Hub-Signature-256',
    'X-Receipt-File-Name',
  ]);
  assert.deepEqual([...REQUEST_HEADERS_DIAGNOSTIC].sort(), [
    'X-Correlation-Id',
    'X-Expected-Version',
  ]);
  // The detector itself works: a contract with an unknown header is reported.
  const withNew = `${API_SPEC}\n        in: header\n        name: X-Unreviewed-Value\n`;
  assert.deepEqual(unclassifiedRequestHeaders(withNew), ['X-Unreviewed-Value']);
});

test('SEC-003: ordinary diagnostic fields stay in the Caddy log, which is never disabled or discarded', () => {
  const snippet = caddyAccessLogSnippet(CADDYFILE);
  assert.match(snippet, /^\s*output stdout$/m, 'the access log still goes to stdout');
  assert.match(snippet, /format filter \{/);
  for (const kept of ['X-Correlation-Id', 'X-Expected-Version', 'Content-Type', 'User-Agent']) {
    assert.doesNotMatch(snippet, new RegExp(`${kept}\\s+(delete|replace)`, 'i'), `${kept} stays`);
  }
  assert.doesNotMatch(CADDYFILE, /^\s*log\s*\{?\s*(output\s+discard|level\s+OFF)/im);
  assert.equal((CADDYFILE.match(/^\s*import accesslog$/gm) ?? []).length, 3);
});

test('SEC-003: the Core API has no request or response logging layer that could record headers or bodies', () => {
  const main = 'apps/core-api/src/main';
  const yaml = ['application.yaml', 'application-local.yaml', 'application-test.yaml']
    .map((name) => {
      try {
        return read(`${main}/resources/${name}`);
      } catch {
        return '';
      }
    })
    .join('\n');
  assert.doesNotMatch(yaml, /accesslog/i, 'no Tomcat access log valve');
  assert.doesNotMatch(
    yaml,
    /(org\.springframework\.web|org\.apache\.coyote|org\.apache\.tomcat)[^\n]*:\s*(DEBUG|TRACE)/i,
    'no web-layer debug logging (it prints headers and parameters)',
  );
  assert.doesNotMatch(yaml, /log-request-details|logbook/i);
  const sources = spawnSync(
    'grep',
    [
      '-rlE',
      'CommonsRequestLoggingFilter|AbstractRequestLoggingFilter|logbook|HttpTraceRepository|includeHeaders|includePayload',
      join(ROOT, main, 'java'),
    ],
    { encoding: 'utf8' },
  );
  assert.equal(sources.stdout.trim(), '', 'no request-logging filter in the Core API sources');
  // No operations log line names a receipt value.
  const logged = spawnSync(
    'grep',
    [
      '-rnEi',
      'addKeyValue\\("(fileName|file_name|receiptName|sha256|digest|contentSha|bytes)"',
      join(ROOT, main, 'java/com/divalhr/core/operations'),
    ],
    { encoding: 'utf8' },
  );
  assert.equal(logged.stdout.trim(), '', 'no receipt value is added to a structured log line');
});

test('SEC-003: log-hygiene fails on a receipt file name header, without printing it', () => {
  const header = '{"headers":{"X-Receipt-File-Name":["sentinelreceipt0123456789.png"]}}';
  const result = hygieneWorld({ 'p-caddy-1': CLEAN, 'p-web-1': `${CLEAN}${header}\n` });
  assert.equal(result.status, 1);
  assert.match(result.stdout, /^FAIL /m);
  assert.ok(!result.stdout.includes('sentinelreceipt0123456789'), 'printed a value');
  const raw = hygieneWorld({ 'p-caddy-1': `${CLEAN}X-Receipt-File-Name: receipt-x.png\n` });
  assert.equal(raw.status, 1);
  // R174-1: the webhook signature header is credential material.
  const signature =
    '{"headers":{"X-Hub-Signature-256":["sha256=sentinelsignature0123456789abcdef"]}}';
  const signed = hygieneWorld({ 'p-caddy-1': CLEAN, 'p-web-1': `${CLEAN}${signature}\n` });
  assert.equal(signed.status, 1);
  assert.match(signed.stdout, /^FAIL /m);
  assert.ok(!signed.stdout.includes('sentinelsignature'), 'printed a value');
  const rawSigned = hygieneWorld({
    'p-caddy-1': `${CLEAN}X-Hub-Signature-256: sha256=sentinelsignature0123456789abcdef\n`,
  });
  assert.equal(rawSigned.status, 1);
});
