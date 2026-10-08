// DEVX-001A: tests of the browser-suite tooling (SSO state, hygiene check, suite runner). Plain
// Node test runner, no browser and no stack:  node --test e2e/tooling.test.ts
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { deflateRawSync } from 'node:zlib';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { TOTP_SEEDS, totp } from './credentials.ts';
import globalSetup from './global-setup.ts';
import { check, scanText, seedCodes, textsOf } from './hygiene.ts';
import {
  createSsoDir,
  readState,
  removeSsoDir,
  sanitizeState,
  type SavedState,
  type SsoCookie,
  SSO_DIR_ENV,
  ssoDir,
  validateState,
  writeState,
} from './sso-state.ts';

const HOST = 'localhost';
const WEB = fileURLToPath(new URL('..', import.meta.url));
// Built at run time so the repository holds no token-shaped literal (secret scanning).
const b64url = (value: string) => Buffer.from(value).toString('base64url');
const SAMPLE_JWT = [
  b64url('{"alg":"HS256"}'),
  b64url('{"sub":"test-subject"}'),
  b64url('signature'),
].join('.');

function cookie(name: string, value = 'v', domain = HOST): SsoCookie {
  return {
    name,
    value,
    domain,
    path: '/realms/divalhr-dev/',
    expires: -1,
    httpOnly: true,
    secure: false,
    sameSite: 'Lax',
  };
}

/** A one-entry deflated ZIP, the shape of the archive Playwright embeds in its HTML report. */
function zipOf(name: string, text: string): Buffer {
  const data = deflateRawSync(Buffer.from(text));
  const fileName = Buffer.from(name);
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(8, 8);
  local.writeUInt32LE(data.length, 18);
  local.writeUInt32LE(text.length, 22);
  local.writeUInt16LE(fileName.length, 26);
  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE(8, 10);
  central.writeUInt32LE(data.length, 20);
  central.writeUInt32LE(text.length, 24);
  central.writeUInt16LE(fileName.length, 28);
  const directoryAt = local.length + fileName.length + data.length;
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(1, 8);
  end.writeUInt16LE(1, 10);
  end.writeUInt32LE(central.length + fileName.length, 12);
  end.writeUInt32LE(directoryAt, 16);
  return Buffer.concat([local, fileName, data, central, fileName, end]);
}

function restoreSsoEnv(previous: string | undefined): void {
  if (previous === undefined) Reflect.deleteProperty(process.env, SSO_DIR_ENV);
  else process.env[SSO_DIR_ENV] = previous;
}

const identityState = (): SavedState => ({
  cookies: [
    cookie('KEYCLOAK_IDENTITY', SAMPLE_JWT),
    cookie('KEYCLOAK_SESSION', 'realm/user/session'),
  ],
  origins: [],
});

void describe('SSO state (A75-3)', () => {
  void it('is created outside the repository with mode 0700 and removed completely', () => {
    const dir = createSsoDir();
    try {
      assert.equal(statSync(dir).mode & 0o777, 0o700);
      assert.ok(!dir.startsWith(fileURLToPath(new URL('../../../', import.meta.url))));
      writeState(dir, 'dev-admin-a', identityState(), HOST);
      assert.equal(statSync(join(dir, 'dev-admin-a.json')).mode & 0o777, 0o600);
    } finally {
      removeSsoDir(dir);
    }
    assert.equal(existsSync(dir), false);
  });

  void it('keeps only the identity host session cookies and never browser storage', () => {
    const saved = sanitizeState(
      {
        cookies: [
          ...identityState().cookies,
          cookie('AUTH_SESSION_ID', 'abc'),
          cookie('KEYCLOAK_IDENTITY', SAMPLE_JWT, 'example.test'),
          cookie('divalhr_pref', 'fr', 'app.localhost'),
        ],
        origins: [
          { origin: 'http://localhost:5173', localStorage: [{ name: 'lang', value: 'fr' }] },
        ],
      },
      HOST,
    );
    assert.deepEqual(saved.cookies.map((c) => c.name).sort(), [
      'KEYCLOAK_IDENTITY',
      'KEYCLOAK_SESSION',
    ]);
    assert.deepEqual(saved.origins, []);
  });

  void it('refuses storage, other hosts, unexpected cookies, stray tokens and a missing session', () => {
    const refuse = (state: SavedState, pattern: RegExp) => {
      assert.throws(() => {
        validateState(state, HOST);
      }, pattern);
    };
    refuse({ ...identityState(), origins: [{ origin: 'http://localhost:5173' }] }, /storage/u);
    refuse(
      {
        cookies: [...identityState().cookies, cookie('KEYCLOAK_SESSION', 'x', 'evil.test')],
        origins: [],
      },
      /another host/u,
    );
    refuse(
      { cookies: [...identityState().cookies, cookie('access_token', 'x')], origins: [] },
      /unexpected/u,
    );
    refuse(
      {
        cookies: [cookie('KEYCLOAK_IDENTITY', SAMPLE_JWT), cookie('KEYCLOAK_SESSION', SAMPLE_JWT)],
        origins: [],
      },
      /token-shaped/u,
    );
    refuse({ cookies: [cookie('KEYCLOAK_SESSION', 'x')], origins: [] }, /identity cookie/u);
  });

  void it('writes once, reads back only from a 0600 file, and refuses a weak directory', () => {
    const dir = createSsoDir();
    const previous = process.env[SSO_DIR_ENV];
    try {
      writeState(dir, 'dev-admin-b', identityState(), HOST);
      assert.throws(() => {
        writeState(dir, 'dev-admin-b', identityState(), HOST);
      });
      assert.equal(readState(dir, 'dev-admin-b', HOST)?.length, 2);
      assert.equal(readState(dir, 'dev-platform-admin', HOST), undefined);
      chmodSync(join(dir, 'dev-admin-b.json'), 0o644);
      assert.throws(() => readState(dir, 'dev-admin-b', HOST), /0600/u);
      process.env[SSO_DIR_ENV] = dir;
      assert.equal(ssoDir(), dir);
      chmodSync(dir, 0o755);
      assert.throws(() => ssoDir(), /0700/u);
      process.env[SSO_DIR_ENV] = 'relative/dir';
      assert.throws(() => ssoDir(), /absolute/u);
      process.env[SSO_DIR_ENV] = WEB;
      assert.throws(() => ssoDir(), /outside the repository/u);
    } finally {
      restoreSsoEnv(previous);
      removeSsoDir(dir);
    }
  });

  void it('global setup creates a private directory and its teardown removes it', () => {
    const previous = process.env[SSO_DIR_ENV];
    Reflect.deleteProperty(process.env, SSO_DIR_ENV);
    try {
      const teardown = globalSetup();
      const dir = process.env[SSO_DIR_ENV];
      assert.ok(dir && existsSync(dir));
      teardown();
      assert.equal(existsSync(dir), false);
    } finally {
      restoreSsoEnv(previous);
    }
  });
});

void describe('hygiene check (A75-3)', () => {
  const now = Math.floor(Date.now() / 1000);
  const codes = seedCodes(now - 60, now);
  const seed = TOTP_SEEDS['dev-admin-a'] ?? '';
  const code = totp(seed, Math.floor(now / 30));
  const report = join('x', 'playwright-report', 'index.html');
  const composeLog = join('x', 'logs', 'compose.log');

  void it('finds tokens, session cookies, passwords, seeds and TOTP codes of the run', () => {
    const kinds = (file: string, text: string) => scanText(file, text, codes).map((f) => f.kind);
    assert.deepEqual(kinds(report, `bearer ${SAMPLE_JWT}`), ['token-shaped value']);
    assert.deepEqual(kinds(report, 'Cookie: KEYCLOAK_IDENTITY=abc'), ['session cookie value']);
    assert.deepEqual(kinds(composeLog, 'password dev-only-Admin-A-2026'), ['seed password']);
    assert.deepEqual(kinds(composeLog, `seed ${seed}`), ['TOTP seed']);
    assert.deepEqual(kinds(report, `typed ${code} into #otp`), ['TOTP code']);
  });

  void it('decodes the archive embedded in an HTML report and scans its entries', () => {
    const embedded = (text: string) =>
      `<script>window.playwrightReportBase64 = "data:application/zip;base64,${zipOf('report.json', text).toString('base64')}";</script>`;
    const parts = textsOf(report, embedded('{"title":"Fill \\"dev-only-Admin-A-2026\\""}'));
    assert.equal(parts.length, 2);
    assert.ok(!parts[0]?.text.includes(zipOf('report.json', 'x').toString('base64').slice(0, 8)));
    assert.match(parts[0]?.text ?? '', /base64,"/u);
    const findings = parts.flatMap((part) => scanText(part.file, part.text, codes));
    assert.deepEqual(findings, [{ file: `${report}#report.json`, kind: 'seed password' }]);
    assert.deepEqual(
      textsOf(report, embedded('{"title":"Click locator(\'#kc-login\')"}')).flatMap((part) =>
        scanText(part.file, part.text, codes),
      ),
      [],
    );
  });

  void it('ignores cookie names without values, codes outside Playwright output and digits inside words', () => {
    assert.deepEqual(scanText(composeLog, 'KEYCLOAK_IDENTITY cookie not found', codes), []);
    assert.deepEqual(scanText(composeLog, `pid ${code} started`, codes), []);
    assert.deepEqual(scanText(report, `hash a${code}b`, codes), []);
  });

  void it('reports a generated file above the size limit instead of skipping it (R76-2)', () => {
    const root = mkdtempSync(join(tmpdir(), 'divalhr-hygiene-'));
    const report = join(root, 'playwright-report');
    mkdirSync(report);
    writeFileSync(join(report, 'index.html'), 'x'.repeat(2048));
    writeFileSync(join(report, 'small.txt'), 'clean');
    try {
      assert.deepEqual(check([report], now - 5, undefined, 1024), [
        { file: join(report, 'index.html'), kind: 'oversized generated file was not scanned' },
      ]);
      assert.deepEqual(check([report], now - 5, undefined, 4096), []);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });

  void it('fails while the SSO directory exists and passes on clean output', () => {
    const root = mkdtempSync(join(tmpdir(), 'divalhr-hygiene-'));
    const results = join(root, 'test-results');
    mkdirSync(results);
    writeFileSync(join(results, 'run-suite.log'), '  ✓  1 [features] › a test (2.1s)\n');
    const left = mkdtempSync(join(tmpdir(), 'divalhr-e2e-sso-'));
    try {
      assert.deepEqual(
        check([results], now - 5, left).map((f) => f.kind),
        ['SSO state directory left behind'],
      );
      rmSync(left, { recursive: true });
      assert.deepEqual(check([results], now - 5, left), []);
    } finally {
      rmSync(root, { recursive: true, force: true });
      rmSync(left, { recursive: true, force: true });
    }
  });
});

void describe('suite runner (A75-3, A75-4)', () => {
  // A fake `playwright` records each invocation and the SSO directory it was given, leaves a
  // file in that directory (like auth-setup), then exits with the status requested per project.
  function runSuite(featuresExit: number, identityExit: number) {
    const work = mkdtempSync(join(tmpdir(), 'divalhr-suite-test-'));
    const log = join(work, 'calls.log');
    const fake = join(work, 'fake-playwright.sh');
    writeFileSync(
      fake,
      `#!/usr/bin/env bash
echo "$* sso=$DIVALHR_E2E_SSO_DIR" >> "${log}"
touch "$DIVALHR_E2E_SSO_DIR/dev-admin-a.json"
case "$*" in
  *--project=identity*) echo "  ✓  1 [identity] › a (1s)"; echo "  3 passed (5s)"; exit ${identityExit} ;;
  *) echo "  ✘  2 [features] › b (1s)"; echo "  ✓  3 [features] › b (retry #1) (1s)"
     echo "  1 flaky"; echo "  2 skipped"; echo "  70 passed (2.0m)"; exit ${featuresExit} ;;
esac
`,
      { mode: 0o755 },
    );
    const results = join(work, 'browser.results');
    const result = spawnSync(
      'bash',
      [join(WEB, 'e2e', 'run-suite.sh'), '--workers', '2', '--results', results],
      {
        cwd: WEB,
        encoding: 'utf8',
        env: { ...process.env, E2E_PLAYWRIGHT: fake, E2E_SKIP_STACK_CHECK: '1', E2E_OUT: work },
      },
    );
    const calls = readFileSync(log, 'utf8').trim().split('\n');
    const wroteOutput = existsSync(join(work, 'test-results', 'run-suite.log'));
    const totals = existsSync(results) ? readFileSync(results, 'utf8').trim() : '';
    rmSync(work, { recursive: true, force: true });
    const dir = /sso=(\S+)/u.exec(calls[0] ?? '')?.[1] ?? '';
    return { status: result.status, calls, dir, out: result.stdout, wroteOutput, totals };
  }

  void it('runs features then identity, removes the SSO state and passes when both pass', () => {
    const run = runSuite(0, 0);
    assert.equal(run.status, 0, run.out);
    assert.equal(run.calls.length, 2);
    assert.match(run.calls[0] ?? '', /--project=auth-setup --project=features --workers=2/u);
    assert.match(run.calls[1] ?? '', /--project=identity --workers=1/u);
    assert.ok(run.dir.length > 0);
    assert.equal(existsSync(run.dir), false);
    assert.match(run.out, /PASS hygiene/u);
    assert.ok(run.wroteOutput);
    // DEVX-001B (R79-2): totals summed over both invocations, retries counted.
    assert.equal(
      run.totals,
      'BROWSER passed=73 skipped=2 failed=0 flaky=1 notrun=0 retries=1 hygiene=PASS',
    );
  });

  void it('still runs identity after a features failure, fails, and removes the SSO state', () => {
    const run = runSuite(1, 0);
    assert.notEqual(run.status, 0);
    assert.equal(run.calls.length, 2);
    assert.equal(existsSync(run.dir), false);
    assert.match(run.out, /features exit=1 identity exit=0/u);
  });

  void it('fails when only identity fails', () => {
    const run = runSuite(0, 1);
    assert.notEqual(run.status, 0);
    assert.match(run.out, /features exit=0 identity exit=1/u);
    assert.equal(existsSync(run.dir), false);
  });
});

void describe('canonical entry points (R76-1)', () => {
  void it('run the suite runner, and only e2e:raw runs plain Playwright', () => {
    const { scripts } = JSON.parse(readFileSync(join(WEB, 'package.json'), 'utf8')) as {
      scripts: Record<string, string>;
    };
    for (const name of ['e2e', 'e2e:suite', 'e2e:serial']) {
      assert.match(scripts[name] ?? '', /^bash e2e\/run-suite\.sh\b/u, name);
    }
    const raw = Object.entries(scripts).filter(([, command]) => /playwright\s+test/u.test(command));
    assert.deepEqual(
      raw.map(([name]) => name),
      ['e2e:raw'],
    );
  });

  void it('make e2e and make e2e-host use the same suite runner', () => {
    const makefile = readFileSync(join(WEB, '..', '..', 'Makefile'), 'utf8');
    const recipe = (target: string) =>
      new RegExp(`^${target}:[^\\n]*\\n((?:\\t[^\\n]*\\n?)*)`, 'mu').exec(makefile)?.[1] ?? '';
    assert.match(makefile, /^e2e: (?:[^\n#]* )?e2e-host\b/mu);
    assert.match(recipe('e2e-host'), /\$\(PNPM\) run e2e\b/u);
    assert.doesNotMatch(makefile, /playwright\s+test/u);
  });
});
