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

// --- R66-1: data-volume preparation never formats the wrong disk -----------------------------
const FIXTURES = join(ROOT, 'scripts/ops/fixtures');
function blockWorld(disks, extra = {}) {
  const dir = tmp();
  const bin = join(dir, 'bin');
  spawnSync('mkdir', ['-p', bin, join(dir, 'data')]);
  for (const t of [
    'id',
    'lsblk',
    'findmnt',
    'blkid',
    'wipefs',
    'swapon',
    'mkfs.ext4',
    'mount',
    'mountpoint',
    'systemctl',
  ]) {
    spawnSync('ln', ['-s', join(FIXTURES, 'fake-block-tools.py'), join(bin, t)]);
  }
  writeFileSync(join(dir, 'world.json'), JSON.stringify({ disks, ...extra }));
  writeFileSync(join(dir, 'fstab'), '');
  writeFileSync(join(dir, 'lock'), '');
  writeFileSync(join(dir, 'log'), '');
  const prepare = (...args) =>
    run('bash', [join(OPS, 'prepare-data-volume.sh'), ...args], {
      env: {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        FAKE_WORLD: join(dir, 'world.json'),
        FAKE_LOG: join(dir, 'log'),
        HR_DEV_DATA: join(dir, 'data'),
        HR_DEV_FSTAB: join(dir, 'fstab'),
        HR_DEV_SYSFS: join(dir, 'sys'),
        DIVALHR_HOST_LOCK: join(dir, 'lock'),
      },
    });
  const changes = () => readFileSync(join(dir, 'log'), 'utf8');
  return { dir, prepare, changes };
}
const EBS = 'Amazon Elastic Block Store';
const root = {
  type: 'disk',
  model: EBS,
  serial: 'vol0aaaaaaaaaaaaaaaa',
  children: [{ name: 'nvme0n1p1', mount: '/' }],
};
const blank = {
  type: 'disk',
  model: EBS,
  serial: 'vol0bbbbbbbbbbbbbbbb',
  children: [],
  mount: '',
  probe: '',
};
const VOL = 'vol-0bbbbbbbbbbbbbbbb';

test(
  'R66-1: the root disk and partitioned disks are refused, even when named with --device',
  { skip: !hasFlock },
  () => {
    const { prepare, changes } = blockWorld({ nvme0n1: root, nvme1n1: blank });
    const r1 = prepare(
      '--volume-id',
      'vol-0aaaaaaaaaaaaaaaa',
      '--device',
      '/dev/nvme0n1',
      '--format',
    );
    assert.notEqual(r1.status, 0);
    assert.match(r1.stderr, /partitions|backs a mounted/);
    const r2 = prepare('--volume-id', VOL, '--device', '/dev/nvme0n1', '--format');
    assert.notEqual(r2.status, 0, 'a device that is not the given volume is refused');
    const r3 = prepare('--volume-id', VOL, '--device', '/dev/nvme0n1p1', '--format');
    assert.notEqual(r3.status, 0, 'a partition is refused');
    assert.equal(changes(), '', 'nothing was formatted or mounted');
  },
);

test('R66-1: a partitioned data disk without mounts is refused', { skip: !hasFlock }, () => {
  const partitioned = {
    ...blank,
    children: [{ name: 'nvme1n1p1', mount: '' }],
    probe: 'PTTYPE=gpt\n',
  };
  const { prepare, changes } = blockWorld({ nvme0n1: root, nvme1n1: partitioned });
  const r = prepare('--volume-id', VOL, '--device', '/dev/nvme1n1', '--format');
  assert.notEqual(r.status, 0);
  assert.equal(changes(), '');
});

test('R66-1: any existing signature or partition table is refused', { skip: !hasFlock }, () => {
  for (const disk of [
    { ...blank, probe: 'TYPE=xfs\n' },
    { ...blank, probe: 'PTTYPE=dos\n' },
    { ...blank, probe: 'TYPE=ext4\nLABEL=other\n' },
    { ...blank, probe: '', wipefs: ['0x438,,,LVM2_member'] },
  ]) {
    const { prepare, changes } = blockWorld({ nvme0n1: root, nvme1n1: disk });
    const r = prepare('--volume-id', VOL, '--format');
    assert.notEqual(r.status, 0, JSON.stringify(disk));
    assert.match(r.stderr, /signature|refusing/);
    assert.equal(changes(), '');
  }
});

test('R66-1: a disk mounted elsewhere or backing swap is refused', { skip: !hasFlock }, () => {
  const mounted = blockWorld({ nvme0n1: root, nvme1n1: { ...blank, mount: '/mnt/other' } });
  assert.notEqual(mounted.prepare('--volume-id', VOL, '--format').status, 0);
  assert.equal(mounted.changes(), '');
  const swap = blockWorld({ nvme0n1: root, nvme1n1: blank }, { swap: ['/dev/nvme1n1'] });
  assert.notEqual(swap.prepare('--volume-id', VOL, '--format').status, 0);
  assert.equal(swap.changes(), '');
});

test(
  'R66-1: a blank data disk is formatted only with --format; our own filesystem is reused',
  { skip: !hasFlock },
  () => {
    const fresh = blockWorld({ nvme0n1: root, nvme1n1: blank });
    const without = fresh.prepare('--volume-id', VOL);
    assert.notEqual(without.status, 0);
    assert.match(without.stderr, /--format/);
    assert.equal(fresh.changes(), '');
    const formatted = fresh.prepare('--volume-id', VOL, '--format');
    assert.equal(formatted.status, 0, formatted.stderr);
    assert.match(fresh.changes(), /^mkfs \/dev\/nvme1n1$/m);
    assert.match(
      readFileSync(join(fresh.dir, 'fstab'), 'utf8'),
      /^UUID=\S+ \S+ ext4 defaults,nofail/m,
    );

    const ours = blockWorld({
      nvme0n1: root,
      nvme1n1: { ...blank, probe: 'TYPE=ext4\nLABEL=divalhr-test\n', uuid: 'u-1' },
    });
    const reused = ours.prepare('--volume-id', VOL, '--format');
    assert.equal(reused.status, 0, reused.stderr);
    assert.doesNotMatch(
      ours.changes(),
      /mkfs/,
      'an existing divalhr-test filesystem is never reformatted',
    );
  },
);

// --- R66-4: timer installation is all or nothing ----------------------------------------------
function unitWorld(failEnable) {
  const dir = tmp();
  const bin = join(dir, 'bin');
  spawnSync('mkdir', ['-p', bin, join(dir, 'units')]);
  spawnSync('ln', ['-s', join(FIXTURES, 'fake-block-tools.py'), join(bin, 'id')]);
  writeFileSync(
    join(bin, 'systemctl'),
    `#!/usr/bin/env bash\necho "$*" >> "${join(dir, 'calls')}"\ncase "$1" in\n  enable) ${failEnable ? 'exit 1' : 'exit 0'} ;;\n  is-active) echo active ;;\n  is-enabled) exit 1 ;;\nesac\nexit 0\n`,
  );
  chmodSync(join(bin, 'systemctl'), 0o755);
  writeFileSync(join(dir, 'world.json'), '{"disks": {}}');
  writeFileSync(join(dir, 'lock'), '');
  // A previous, different version of one unit is already installed.
  writeFileSync(join(dir, 'units', 'divalhr-hrdev-backup.timer'), 'previous version\n');
  const install = () =>
    run('bash', [join(OPS, 'install-units.sh')], {
      env: {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        FAKE_WORLD: join(dir, 'world.json'),
        FAKE_LOG: join(dir, 'log'),
        HR_DEV_UNIT_DIR: join(dir, 'units'),
        HR_DEV_SYSTEMCTL: join(bin, 'systemctl'),
      },
    });
  return { dir, install };
}

test('R66-4: install-units installs and enables both timers', () => {
  const { dir, install } = unitWorld(false);
  const r = install();
  assert.equal(r.status, 0, r.stderr + r.stdout);
  assert.deepEqual(readdirSync(join(dir, 'units')).sort(), [
    'divalhr-hrdev-backup.service',
    'divalhr-hrdev-backup.timer',
    'divalhr-hrdev-watchdog.service',
    'divalhr-hrdev-watchdog.timer',
  ]);
  assert.match(
    readFileSync(join(dir, 'calls'), 'utf8'),
    /^enable --now divalhr-hrdev-watchdog\.timer divalhr-hrdev-backup\.timer$/m,
  );
});

test('R66-4: a failed enable restores the previous unit files and leaves the timers disabled', () => {
  const { dir, install } = unitWorld(true);
  const r = install();
  assert.notEqual(r.status, 0);
  assert.deepEqual(readdirSync(join(dir, 'units')), ['divalhr-hrdev-backup.timer']);
  assert.equal(
    readFileSync(join(dir, 'units', 'divalhr-hrdev-backup.timer'), 'utf8'),
    'previous version\n',
  );
  assert.match(
    readFileSync(join(dir, 'calls'), 'utf8'),
    /^disable --now divalhr-hrdev-watchdog\.timer$/m,
  );
});

test('R66-4: the release becomes current only after its timers are installed', () => {
  const deploy = source('remote-deploy.sh');
  const chain = deploy.slice(deploy.indexOf('hr_log "starting $SHA"'));
  assert.ok(chain.indexOf('&& install_units; then') < chain.indexOf('hr_set_current "$SHA"'));
  assert.doesNotMatch(deploy, /install-units\.sh" \|\| hr_die/);
});

// --- R66-2: rollback always restores both databases ------------------------------------------
test('R66-2: every rollback restores both pre-deploy dumps before starting the previous images', () => {
  const rollback = source('rollback.sh');
  assert.doesNotMatch(rollback, /no-migration/);
  const restore = rollback.indexOf('hr_restore_backup "$FROM" "$backup"');
  const start = rollback.indexOf('hr_compose "$TO" up');
  assert.ok(restore > 0 && restore < start, 'restore happens before the target starts');
  assert.match(rollback, /\[ -n "\$backup" \] \|\| hr_die/);
  assert.match(source('lib.sh'), /for pair in divalhr:divalhr_app keycloak:keycloak/);
});

// --- R66-3: ports 80/443 never land in a shared security group ------------------------------
function awsWorld(environment) {
  const dir = tmp();
  const bin = join(dir, 'bin');
  spawnSync('mkdir', ['-p', bin]);
  spawnSync('ln', ['-s', join(FIXTURES, 'fake-aws.sh'), join(bin, 'aws')]);
  spawnSync('ln', ['-s', join(FIXTURES, 'fake-aws.sh'), join(bin, 'dig')]);
  writeFileSync(join(dir, 'log'), '');
  const aws = (...args) =>
    run('bash', [join(ROOT, 'ops/aws/hr-dev-aws.sh'), ...args], {
      env: {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        FAKE_LOG: join(dir, 'log'),
        ...environment,
      },
    });
  const openWeb = () => aws('open-web', '--yes');
  return { aws, openWeb, calls: () => readFileSync(join(dir, 'log'), 'utf8') };
}

test('R66-3: open-web refuses a web group shared with another instance and changes nothing', () => {
  const { openWeb, calls } = awsWorld({ FAKE_WEB_SG: 'sg-0123456789abc0web', FAKE_SHARED: '1' });
  const r = openWeb();
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /other resources; nothing was changed/);
  assert.doesNotMatch(calls(), /authorize|modify|create-security-group/);
});

test('R66-3: open-web uses a dedicated group and never edits the existing one', () => {
  const { openWeb, calls } = awsWorld({});
  const r = openWeb();
  assert.equal(r.status, 0, r.stderr + r.stdout);
  const log = calls();
  assert.match(log, /^create-security-group$/m);
  assert.match(log, /authorize .*--group-id sg-0123456789abc0web .*FromPort=80/);
  assert.match(log, /authorize .*--group-id sg-0123456789abc0web .*FromPort=443/);
  assert.doesNotMatch(log, /authorize .*sg-0123456789abc0ssh/);
  assert.match(log, /modify .*--groups sg-0123456789abc0ssh sg-0123456789abc0web/);
});

// --- Issue #68: deployment tooling corrections --------------------------------------------------
const DLM_ALLOWED = /^[0-9A-Za-z _-]{1,500}$/;

test('#68: the DLM policy is created with a description AWS accepts', () => {
  const { aws, calls } = awsWorld({});
  const r = aws('snapshots', '--yes');
  assert.equal(r.status, 0, r.stderr + r.stdout);
  assert.match(r.stdout, /created policy policy-/);
  const description = /create-lifecycle-policy description=\[([^\]]*)\]/.exec(calls())?.[1];
  assert.ok(description, 'create-lifecycle-policy was called with a description');
  assert.match(description, DLM_ALLOWED);
  // The fake applies AWS's rule: the description used before #68 is rejected.
  const old = run(
    join(FIXTURES, 'fake-aws.sh'),
    [
      'dlm',
      'create-lifecycle-policy',
      '--description',
      'DivalHR hr-dev data volume, daily, keep 7',
    ],
    { env: { ...process.env, FAKE_LOG: join(tmp(), 'log') } },
  );
  assert.notEqual(old.status, 0);
  assert.match(old.stderr, /InvalidRequestException/);
});

test('#68: an existing DLM policy is kept, and the policy schedule names use the same character set', () => {
  const { aws, calls } = awsWorld({ FAKE_DLM_POLICIES: 'policy-0123456789abcdef0' });
  const r = aws('snapshots', '--yes');
  assert.equal(r.status, 0, r.stderr + r.stdout);
  assert.match(r.stdout, /already exists/);
  assert.doesNotMatch(calls(), /create-lifecycle-policy/);
  const policy = JSON.parse(read('ops/hr-dev/aws/dlm-policy.json'));
  for (const schedule of policy.Schedules) assert.match(schedule.Name, DLM_ALLOWED);
});

const deployLib = (script, environment = {}) =>
  run('bash', ['-c', `set -u; . "$LIB"; ${script}`], {
    env: { ...process.env, LIB: join(OPS, 'deploy-lib.sh'), ...environment },
  });
// A fake instance: state/ is root 0700, so only the sudo -n read returns the release.
const FAKE_INSTANCE = `
rsh() {
  case "$1" in
    "sudo -n cat /srv/divalhr-test/state/current-release 2>/dev/null") printf '%s\\n' "$RUNNING" ;;
    "sudo -n /srv/divalhr-test/current/ops/hr-dev/evidence-host.sh")
      printf '== checks\\n%s\\n' "$EVIDENCE"; [ "$EVIDENCE" = "ALL HOST CHECKS PASSED" ] ;;
    *) return 1 ;;
  esac
}`;

test('#68: a successful deployment is reported as deployed, reading the release with sudo -n', () => {
  const out = tmp();
  const ok = deployLib(`${FAKE_INSTANCE}\nhr_deploy_finish 0 "$SHA" "$OUT"`, {
    SHA,
    OUT: out,
    RUNNING: SHA,
    EVIDENCE: 'ALL HOST CHECKS PASSED',
  });
  assert.equal(ok.status, 0, ok.stderr + ok.stdout);
  assert.match(ok.stdout, new RegExp(`DEPLOYED ${SHA} to https://hr-dev\\.dival\\.ai`));
  assert.doesNotMatch(ok.stdout, /DEPLOYMENT FAILED/);
  assert.match(readFileSync(join(out, 'evidence-host.txt'), 'utf8'), /ALL HOST CHECKS PASSED/);

  const cases = [
    {
      rc: '1',
      running: SHA,
      evidence: 'ALL HOST CHECKS PASSED',
      expect: /DEPLOYMENT FAILED \(exit 1\)/,
    },
    {
      rc: '0',
      running: 'b'.repeat(40),
      evidence: 'ALL HOST CHECKS PASSED',
      expect: /DEPLOYMENT FAILED \(exit 0\); running release is b{40}/,
    },
    {
      rc: '0',
      running: '',
      evidence: 'ALL HOST CHECKS PASSED',
      expect: /DEPLOYMENT FAILED \(exit 0\); running release is none/,
    },
    {
      rc: '0',
      running: SHA,
      evidence: 'FAIL port 5432',
      expect: /DEPLOYED[\s\S]*WARNING: host evidence reported FAIL lines/,
    },
  ];
  for (const c of cases) {
    const r = deployLib(`${FAKE_INSTANCE}\nhr_deploy_finish "$RC" "$SHA" "$OUT"`, {
      SHA,
      OUT: tmp(),
      RC: c.rc,
      RUNNING: c.running,
      EVIDENCE: c.evidence,
    });
    assert.equal(r.status, 1, JSON.stringify(c));
    assert.match(r.stdout, c.expect);
  }
  // deploy.sh itself never reads the root-only state directory without sudo.
  const deploy = source('deploy.sh');
  assert.match(deploy, /deploy-lib\.sh/);
  assert.match(deploy, /hr_deploy_finish "\$RC" "\$SHA" "\$OUT"/);
  assert.doesNotMatch(deploy, /rsh '(cat|head|tail) \/srv\/divalhr-test\/state\//);
});

test('#68: the log follower prints every line exactly once, blank lines included', () => {
  // Three polls of a log that grows with the blank lines Docker's build output contains.
  const polls = [
    '#1 [internal] load build definition\n#1 DONE 0.0s\n\n#2 [web] build',
    '#1 [internal] load build definition\n#1 DONE 0.0s\n\n#2 [web] build\n#2 DONE 1.0s\n\n\n#3 exporting',
    '#1 [internal] load build definition\n#1 DONE 0.0s\n\n#2 [web] build\n#2 DONE 1.0s\n\n\n#3 exporting\n\nstarting\n12:13:56Z deployed',
  ];
  const r = deployLib(
    `SEEN=0
     for LINES in "$P1" "$P2" "$P3" "$P3"; do
       N=$(hr_log_line_count "$LINES")
       if [ "$N" -gt "$SEEN" ]; then hr_log_new_lines "$LINES" "$SEEN"; SEEN=$N; fi
     done`,
    { P1: polls[0], P2: polls[1], P3: polls[2] },
  );
  assert.equal(r.status, 0, r.stderr);
  const expected =
    polls[2]
      .split('\n')
      .map((line) => `  ${line}`)
      .join('\n') + '\n';
  assert.equal(r.stdout, expected);
  const counts = deployLib('hr_log_line_count ""; hr_log_line_count "a"; hr_log_line_count "$X"', {
    X: 'a\n\nb',
  });
  assert.equal(counts.stdout, '0\n1\n3\n');
});

test('#68: the follower reads only complete lines of the remote log, then the exit code', () => {
  const home = tmp();
  const dir = join(home, 'divalhr-deploy', 'RUN1');
  spawnSync('mkdir', ['-p', dir]);
  const poll = () => {
    const command = deployLib('hr_deploy_log_command RUN1').stdout;
    return run('bash', ['-c', command], { env: { ...process.env, HOME: home } }).stdout;
  };
  assert.equal(poll(), '@@EXIT \n');
  writeFileSync(join(dir, 'deploy.log'), 'one\n\nthree\npart');
  assert.equal(poll(), 'one\n\nthree\n@@EXIT \n');
  writeFileSync(join(dir, 'deploy.log'), 'one\n\nthree\npartial line\n');
  writeFileSync(join(dir, 'exit'), '0\n');
  assert.equal(poll(), 'one\n\nthree\npartial line\n@@EXIT 0\n');
});
