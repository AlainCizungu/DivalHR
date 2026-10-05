// OPS-001 (Issue #65): invariants and behaviour of the test-environment operations.
// Runs with `pnpm ops:test` (Node's test runner); needs bash, python3 and flock (Linux CI and the
// AWS instance; the lock and watchdog tests are skipped where flock is missing).
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  writeFileSync,
  existsSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

const ROOT = new URL('../../', import.meta.url).pathname;
// These tests exercise the lock themselves; inside an aws-verify run the caller already holds the
// real host lock and exports DIVALHR_HOST_LOCK_HELD=1, which must not leak into them.
delete process.env.DIVALHR_HOST_LOCK_HELD;
const OPS = join(ROOT, 'ops/hr-dev');
const read = (path) => readFileSync(join(ROOT, path), 'utf8');
const run = (command, args, options = {}) =>
  spawnSync(command, args, { encoding: 'utf8', timeout: 60_000, ...options });
const hasFlock = run('bash', ['-c', 'command -v flock']).status === 0;
const tmp = () => mkdtempSync(join(tmpdir(), 'hrdev-'));

// --- realm --------------------------------------------------------------------------------------
const realm = JSON.parse(read('infrastructure/hr-dev/keycloak/realm-divalhr-test.json'));

test('test realm: TLS required, labelled, synthetic, no users or credentials but the service account', () => {
  assert.equal(realm.realm, 'divalhr-test');
  assert.equal(realm.sslRequired, 'all');
  assert.match(realm.displayName, /TEST ENVIRONMENT/);
  const users = realm.users ?? [];
  assert.deepEqual(
    users.map((user) => user.username),
    ['service-account-divalhr-core-provisioner'],
  );
  assert.ok(users.every((user) => !user.credentials || user.credentials.length === 0));
  assert.equal(realm.smtpServer.host, 'mailpit');
  assert.match(realm.smtpServer.from, /@hr-dev\.example\.test$/);
  assert.equal(realm.browserSecurityHeaders.strictTransportSecurity, 'max-age=86400');
  assert.equal(realm.browserSecurityHeaders.xRobotsTag, 'noindex, nofollow, noarchive');
});

test('test realm: web client redirects only to the configured origin; no development value', () => {
  const web = realm.clients.find((client) => client.clientId === 'divalhr-web');
  assert.deepEqual(web.redirectUris, ['${HR_DEV_ORIGIN}/auth/callback']);
  assert.deepEqual(web.webOrigins, ['${HR_DEV_ORIGIN}']);
  const text = JSON.stringify(realm);
  assert.doesNotMatch(text, /dev-only|localhost|divalhr-dev/);
  const provisioner = realm.clients.find(
    (client) => client.clientId === 'divalhr-core-provisioner',
  );
  assert.equal(provisioner.secret, '${DIVALHR_KEYCLOAK_PROVISIONER_SECRET}');
});

test('render-realm.py substitutes exactly the two placeholders and validates the result', () => {
  const dir = tmp();
  const secret = join(dir, 'secret');
  writeFileSync(secret, 'rendered-test-secret-value-0123456789');
  const out = join(dir, 'realm.json');
  const ok = run('python3', [
    join(OPS, 'render-realm.py'),
    join(ROOT, 'infrastructure/hr-dev/keycloak/realm-divalhr-test.json'),
    out,
    'https://hr-dev.dival.ai',
    secret,
  ]);
  assert.equal(ok.status, 0, ok.stderr);
  assert.doesNotMatch(ok.stdout + ok.stderr, /rendered-test-secret/);
  const rendered = readFileSync(out, 'utf8');
  assert.doesNotMatch(rendered, /\$\{HR_DEV_ORIGIN\}|\$\{DIVALHR_/);
  assert.match(rendered, /https:\/\/hr-dev\.dival\.ai\/auth\/callback/);
  assert.match(rendered, /rendered-test-secret-value/);
  for (const origin of ['http://hr-dev.dival.ai', 'https://hr-dev.dival.ai/']) {
    const bad = run('python3', [
      join(OPS, 'render-realm.py'),
      join(ROOT, 'infrastructure/hr-dev/keycloak/realm-divalhr-test.json'),
      join(dir, 'bad.json'),
      origin,
      secret,
    ]);
    assert.notEqual(bad.status, 0, `origin ${origin} must be refused`);
  }
  writeFileSync(secret, 'dev-only-secret');
  const dev = run('python3', [
    join(OPS, 'render-realm.py'),
    join(ROOT, 'infrastructure/hr-dev/keycloak/realm-divalhr-test.json'),
    join(dir, 'dev.json'),
    'https://hr-dev.dival.ai',
    secret,
  ]);
  assert.notEqual(dev.status, 0, 'a development secret must be refused');
});

// --- compose and Caddy --------------------------------------------------------------------------
const compose = read('infrastructure/hr-dev/compose.yaml');
const caddy = read('infrastructure/hr-dev/caddy/Caddyfile')
  .split('\n')
  .filter((line) => !line.trim().startsWith('#'))
  .join('\n');

test('compose: no named volumes, no env files, pinned third-party images, only Caddy publishes', () => {
  assert.doesNotMatch(compose, /^volumes:/m, 'no top-level named volumes (A65-1)');
  assert.doesNotMatch(compose, /env_file/);
  for (const [, image] of compose.matchAll(/^\s+image:\s*(\S+)/gm)) {
    if (image.startsWith('${HR_DEV_IMAGE_PREFIX')) continue;
    assert.match(image, /@sha256:[0-9a-f]{64}$/, `${image} must be pinned by digest`);
  }
  const ports = [...compose.matchAll(/^\s+ports:\n((?:\s+(?:- |#).+\n)+)/gm)];
  assert.equal(ports.length, 1, 'exactly one service publishes ports');
  const published = ports[0][1]
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.startsWith('- '));
  assert.deepEqual(published, [
    "- '${HR_DEV_HTTP_PUBLISH:-80}:80'",
    "- '${HR_DEV_HTTPS_PUBLISH:-443}:443'",
    "- '127.0.0.1:${HR_DEV_ADMIN_PORT:-18180}:18180'",
  ]);
  const caddyBlock = compose.slice(compose.indexOf('\n  caddy:'), compose.indexOf('\n  postgres:'));
  assert.match(caddyBlock, /ports:/);
  for (const line of compose.split('\n')) {
    assert.doesNotMatch(line, /PASSWORD:\s*['"]?[^$\s'"]/, `literal password: ${line}`);
  }
  assert.match(compose, /DIVALHR_ENVIRONMENT: test/);
  assert.match(compose, /KC_PROXY_TRUSTED_ADDRESSES:/);
  assert.match(compose, /KC_HOSTNAME_ADMIN: http:\/\/localhost:/);
  // The bootstrap username lives in keycloak.conf with its password and is removed with it:
  // as an environment variable it would stop Keycloak from starting once the password is gone.
  assert.doesNotMatch(compose, /KC_BOOTSTRAP_ADMIN/);
  assert.match(compose, /KC_CACHE: local/, 'single node, no cluster discovery');
  // Fixed addresses (Caddy) sit below the dynamic range of every network, so Docker never hands
  // them to another container first.
  const subnets = [...compose.matchAll(/subnet: \$\{HR_DEV_SUBNET_PREFIX:-10\.71\}\.(\d)\.0\/24/g)];
  const ranges = [
    ...compose.matchAll(/ip_range: \$\{HR_DEV_SUBNET_PREFIX:-10\.71\}\.(\d)\.128\/25/g),
  ];
  assert.equal(subnets.length, 6);
  assert.deepEqual(
    ranges.map((m) => m[1]),
    subnets.map((m) => m[1]),
  );
  for (const [, last] of compose.matchAll(
    /ipv4_address: \$\{HR_DEV_SUBNET_PREFIX:-10\.71\}\.\d\.(\d+)/g,
  )) {
    assert.ok(Number(last) < 128, `fixed address .${last} is outside the dynamic range`);
  }
});

test('Caddy: one-day HSTS without subdomains or preload, noindex everywhere, admin paths refused', () => {
  assert.match(caddy, /Strict-Transport-Security "max-age=86400"/);
  assert.doesNotMatch(caddy, /includeSubDomains|preload/);
  assert.match(caddy, /X-Robots-Tag "noindex, nofollow, noarchive"/);
  assert.match(caddy, /Disallow: \//);
  for (const path of [
    'identity/(admin',
    'realms/master',
    'health',
    'metrics',
    'account',
    'divalhr-provisioning',
    'actuator',
  ]) {
    assert.ok(caddy.includes(path), `blocked list mentions ${path}`);
  }
  assert.match(caddy, /request>headers>Authorization delete/);
  assert.match(caddy, /request>headers>Cookie delete/);
  assert.match(caddy, /request>uri regexp/);
});

// --- scripts --------------------------------------------------------------------------------------
const scripts = readdirSync(OPS).filter((name) => name.endsWith('.sh'));
const source = (name) => readFileSync(join(OPS, name), 'utf8');

test('every changing operation takes the host lock and requires root', () => {
  for (const name of [
    'remote-deploy.sh',
    'backup.sh',
    'rollback.sh',
    'restore-drill.sh',
    'init-secrets.sh',
    'keycloak-admin-setup.sh',
    'prepare-data-volume.sh',
    'rehearse.sh',
    'evidence-host.sh',
  ]) {
    assert.match(source(name), /divalhr_lock "/, `${name} takes the host lock`);
    assert.match(source(name), /hr_require_root/, `${name} requires root`);
  }
  assert.match(source('watchdog.sh'), /divalhr_lock_busy/);
  assert.match(read('scripts/dev/verify-on-host.sh'), /divalhr_lock "verify-on-host/);
  assert.match(read('ops/aws/aws-verify.sh'), /\/run\/lock\/divalhr-host\.lock/);
});

test('no global Docker prune, no volume removal, no secret echo in the operations', () => {
  for (const name of [...scripts, ...readdirSync(join(ROOT, 'ops/aws'))]) {
    const text =
      name.endsWith('.sh') && existsSync(join(OPS, name)) ? source(name) : read(`ops/aws/${name}`);
    assert.doesNotMatch(text, /system prune|volume prune|image prune -a|down -v\b|--volumes/, name);
    assert.doesNotMatch(text, /set -x/, name);
  }
  const python = readFileSync(join(OPS, 'keycloak_admin_setup.py'), 'utf8');
  for (const line of python.split('\n').filter((l) => l.includes('print('))) {
    assert.doesNotMatch(line, /password\b(?!s? )|token\)|\{token|\{password/, line);
  }
});

test('deploy provenance: main ancestry, green checks, verification evidence, full-SHA images', () => {
  const deploy = source('deploy.sh');
  assert.match(deploy, /merge-base --is-ancestor "\$SHA" origin\/main/);
  assert.match(deploy, /provenance\.py/);
  assert.match(deploy, /\$\{#SHA\}" = 40/);
  const remote = source('remote-deploy.sh');
  assert.match(remote, /PASS hrdev-rehearsal/);
  assert.match(remote, /check_bundle_head/);
  assert.match(compose, /\$\{HR_DEV_RELEASE:\?\}/);
  const required = read('ops/hr-dev/required-checks.txt');
  assert.match(required, /^hr-dev ops validation$/m);
});

// --- provenance.py ------------------------------------------------------------------------------
const SHA = 'a'.repeat(40);
const runCheck = (name, conclusion, extra = {}) => ({
  id: Math.floor(Math.random() * 1e9),
  name,
  head_sha: SHA,
  status: 'completed',
  conclusion,
  started_at: '2026-10-05T10:00:00Z',
  ...extra,
});
const provenance = (runs, total = runs.length, sha = SHA) =>
  run('python3', [join(OPS, 'provenance.py'), join(OPS, 'required-checks.txt'), sha], {
    input: JSON.stringify({ total_count: total, check_runs: runs }),
  });
const green = () => [
  runCheck('end-to-end smoke (login, health, fr/en, accessibility)', 'success'),
  runCheck(
    'container build validation (core-api, apps/core-api, apps/core-api/Dockerfile)',
    'success',
  ),
  runCheck(
    'container build validation (keycloak-hr-dev, ., infrastructure/docker/keycloak/Dockerfile)',
    'success',
  ),
  runCheck('hr-dev ops validation', 'success'),
  runCheck('secret scanning', 'success'),
  runCheck('dependency review', 'skipped'),
];

test('provenance: all green passes', () => {
  const result = provenance(green());
  assert.equal(result.status, 0, result.stdout);
  assert.match(result.stdout, /^PASS /m);
});

test('provenance: fails closed', () => {
  const cases = {
    'a failed optional check': [...green(), runCheck('web and packages', 'failure')],
    'a missing required check': green().filter((r) => r.name !== 'hr-dev ops validation'),
    'a running check': [...green(), runCheck('core-api', null, { status: 'in_progress' })],
    'a skipped required check': green().map((r) =>
      r.name === 'secret scanning' ? { ...r, conclusion: 'skipped' } : r,
    ),
    'another commit': green().map((r, i) => (i === 0 ? { ...r, head_sha: 'b'.repeat(40) } : r)),
    'no runs': [],
  };
  for (const [label, runs] of Object.entries(cases)) {
    const result = provenance(runs);
    assert.equal(result.status, 1, `${label}: ${result.stdout}`);
    assert.match(result.stdout, /^FAIL /m, label);
  }
  assert.equal(provenance(green(), 200).status, 1, 'truncated listing');
  assert.equal(provenance(green(), undefined, 'abc').status, 1, 'short SHA');
});

test('provenance: a successful re-run replaces an earlier failure of the same check', () => {
  const runs = green();
  runs.push(runCheck('hr-dev ops validation', 'failure', { started_at: '2026-10-05T09:00:00Z' }));
  assert.equal(provenance(runs).status, 0);
});

// --- host lock ----------------------------------------------------------------------------------
test('host lock: a second operation exits 75 before changing anything', { skip: !hasFlock }, () => {
  const dir = tmp();
  const lock = join(dir, 'divalhr-host.lock');
  writeFileSync(lock, '');
  const script = `
    . '${ROOT}ops/host/host-lock.sh'
    divalhr_lock "first holder" || exit 1
    ( unset DIVALHR_HOST_LOCK_HELD; . '${ROOT}ops/host/host-lock.sh'; divalhr_lock "second"; echo "rc=$?" )
    ( unset DIVALHR_HOST_LOCK_HELD; . '${ROOT}ops/host/host-lock.sh'; divalhr_lock_busy && echo busy )
  `;
  const result = run('bash', ['-c', script], {
    env: { ...process.env, DIVALHR_HOST_LOCK: lock, DIVALHR_HOST_LOCK_HELD: '' },
  });
  assert.match(result.stdout, /rc=75/);
  assert.match(result.stdout, /busy/);
  assert.match(result.stderr, /holds the host lock \(first holder by /);
  const free = run(
    'bash',
    ['-c', `. '${ROOT}ops/host/host-lock.sh'; divalhr_lock_busy || echo free`],
    {
      env: { ...process.env, DIVALHR_HOST_LOCK: lock },
    },
  );
  assert.match(free.stdout, /free/);
  const missing = run(
    'bash',
    ['-c', `. '${ROOT}ops/host/host-lock.sh'; divalhr_lock x; echo "rc=$?"`],
    {
      env: { ...process.env, DIVALHR_HOST_LOCK: join(dir, 'absent') },
    },
  );
  assert.match(missing.stdout, /rc=78/);
  const optional = run(
    'bash',
    ['-c', `. '${ROOT}ops/host/host-lock.sh'; divalhr_lock x --optional; echo "rc=$?"`],
    {
      env: { ...process.env, DIVALHR_HOST_LOCK: join(dir, 'absent') },
    },
  );
  assert.match(optional.stdout, /rc=0/);
});

// --- watchdog -----------------------------------------------------------------------------------
function watchdogFixture() {
  const dir = tmp();
  const fake = join(dir, 'docker');
  writeFileSync(
    fake,
    `#!/usr/bin/env bash
case "$1" in
  ps) cat "${dir}/containers" ;;
  inspect) cat "${dir}/state.\${@: -1}" ;;
  restart) echo "\${@: -1}" >> "${dir}/restarts" ;;
esac
`,
  );
  chmodSync(fake, 0o755);
  writeFileSync(join(dir, 'containers'), 'c1 core-api\nc2 keycloak\nc3 web\n');
  writeFileSync(join(dir, 'state.c1'), 'running unhealthy');
  writeFileSync(join(dir, 'state.c2'), 'restarting unhealthy');
  writeFileSync(join(dir, 'state.c3'), 'running healthy');
  writeFileSync(join(dir, 'lock'), '');
  const env = (now) => ({
    ...process.env,
    DOCKER: fake,
    HR_DEV_DATA: dir,
    WATCHDOG_STATE: join(dir, 'state'),
    WATCHDOG_NOW: String(now),
    DIVALHR_HOST_LOCK: join(dir, 'lock'),
    DIVALHR_HOST_LOCK_HELD: '',
  });
  const tick = (now) => run('bash', [join(OPS, 'watchdog.sh')], { env: env(now) });
  const restarts = () =>
    existsSync(join(dir, 'restarts'))
      ? readFileSync(join(dir, 'restarts'), 'utf8').trim().split('\n')
      : [];
  return { dir, tick, restarts };
}

test(
  'watchdog: restarts only after 3 consecutive unhealthy runs, never exited/restarting ones',
  { skip: !hasFlock },
  () => {
    const { tick, restarts } = watchdogFixture();
    let t = 1_000_000;
    assert.match(tick(t).stdout, /core-api unhealthy \(1\/3\)/);
    assert.match(tick((t += 60)).stdout, /\(2\/3\)/);
    assert.deepEqual(restarts(), []);
    assert.match(tick((t += 60)).stdout, /restarted core-api/);
    assert.deepEqual(
      restarts(),
      ['c1'],
      'only the running unhealthy container; never keycloak (restarting)',
    );
  },
);

test(
  'watchdog: a healthy observation resets the count; cooldown and hourly budget bound restarts',
  { skip: !hasFlock },
  () => {
    const { dir, tick, restarts } = watchdogFixture();
    let t = 2_000_000;
    tick(t);
    tick((t += 60));
    writeFileSync(join(dir, 'state.c1'), 'running healthy');
    tick((t += 60));
    writeFileSync(join(dir, 'state.c1'), 'running unhealthy');
    tick((t += 60));
    tick((t += 60));
    assert.deepEqual(restarts(), [], 'the count restarted after the healthy observation');
    tick((t += 60));
    assert.equal(restarts().length, 1);
    // Within the 10-minute cooldown: no restart even after three more unhealthy runs.
    for (let i = 0; i < 4; i++) tick((t += 60));
    assert.equal(restarts().length, 1);
    assert.match(tick((t += 60)).stdout, /cooldown/);
    // After the cooldown, restarts continue up to three per hour, then stop.
    for (let i = 0; i < 40; i++) tick((t += 60));
    assert.equal(restarts().length, 3);
    assert.match(tick((t += 60)).stdout, /budget exhausted.*operator attention required/);
  },
);

test(
  'watchdog: does nothing while another operation holds the host lock',
  { skip: !hasFlock },
  () => {
    const { dir, restarts } = watchdogFixture();
    const result = run(
      'bash',
      [
        '-c',
        `flock -n '${join(dir, 'lock')}' bash -c 'for i in 1 2 3 4; do "$0" ; done' '${join(OPS, 'watchdog.sh')}'`,
      ],
      {
        env: {
          ...process.env,
          DOCKER: join(dir, 'docker'),
          HR_DEV_DATA: dir,
          WATCHDOG_STATE: join(dir, 'state'),
          DIVALHR_HOST_LOCK: join(dir, 'lock'),
          DIVALHR_HOST_LOCK_HELD: '',
        },
      },
    );
    assert.equal(result.stdout.match(/skipped/g)?.length, 4);
    assert.deepEqual(restarts(), []);
    assert.equal(existsSync(join(dir, 'state')), false, 'no state written while the lock is busy');
  },
);

// --- migrations ---------------------------------------------------------------------------------
test('rollback knows every shipped Flyway version, SQL and Java migrations alike', () => {
  const parent = join(ROOT, '..');
  const name = ROOT.replace(/\/$/, '').split('/').pop();
  const result = run('bash', ['-c', `. '${OPS}/lib.sh'; hr_release_migrations '${name}'`], {
    env: { ...process.env, HR_DEV_RELEASES: parent },
  });
  const versions = result.stdout.trim().split('\n');
  const expected = [
    ...readdirSync(join(ROOT, 'apps/core-api/src/main/resources/db/migration')),
    ...readdirSync(join(ROOT, 'apps/core-api/src/main/java/db/migration')),
  ]
    .filter((file) => /^V[0-9_]+__/.test(file))
    .map((file) => file.replace(/^V([0-9_]+)__.*$/, '$1').replaceAll('_', '.'));
  assert.deepEqual([...versions].sort(), [...new Set(expected)].sort());
  assert.ok(versions.includes('14.1'), 'the Java migration V14_1 is included');
});
