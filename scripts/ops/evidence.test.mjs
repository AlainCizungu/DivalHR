// DEVX-001B (Issue #75; A75-5, A75-6, A75B-1..A75B-6): risk classes, the rehearsal step matrix,
// checksummed read-only records and the hr-dev deployment decision (ops/hr-dev/evidence.py).
// Runs with `pnpm ops:test`; needs git and python3. Every case builds real commits and bundles.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const EVIDENCE = join(ROOT, 'ops/hr-dev/evidence.py');
const WORK = mkdtempSync(join(tmpdir(), 'divalhr-evidence-'));
after(() => rmSync(WORK, { recursive: true, force: true }));

const sh = (cmd, args, opts = {}) => {
  const r = spawnSync(cmd, args, { encoding: 'utf8', ...opts });
  return { code: r.status, out: (r.stdout ?? '') + (r.stderr ?? '') };
};
const py = (...args) => sh('python3', [EVIDENCE, ...args]);
let counter = 0;

/** A git repository with helpers to write files and commit. */
function repo() {
  const dir = join(WORK, `repo-${++counter}`);
  mkdirSync(dir);
  const git = (...args) => {
    const r = sh('git', ['-C', dir, ...args]);
    assert.equal(r.code, 0, r.out);
    return r.out.trim();
  };
  git('init', '-q', '-b', 'main');
  git('config', 'user.name', 'test');
  git('config', 'user.email', 'test@example.test');
  const write = (files) => {
    for (const [path, text] of Object.entries(files)) {
      mkdirSync(dirname(join(dir, path)), { recursive: true });
      writeFileSync(join(dir, path), text);
    }
  };
  const commit = (files, message = 'change') => {
    write(files);
    git('add', '-A');
    git('commit', '-q', '-m', message);
    return git('rev-parse', 'HEAD');
  };
  const bundle = (sha, file) => {
    git('update-ref', 'refs/test/b', sha);
    git('bundle', 'create', file, 'refs/test/b');
    git('update-ref', '-d', 'refs/test/b');
    return file;
  };
  return { dir, git, write, commit, bundle };
}

const classify = (paths) => {
  const r = repo();
  const base = r.commit({ 'README.md': 'x' }, 'base');
  const head = r.commit(Object.fromEntries(paths.map((p) => [p, `${p}\n`])));
  const out = py('classify', '--repo', r.dir, '--head', head, '--deployed', base).out;
  return /^class=(.+)$/mu.exec(out)?.[1];
};

describe('risk classifier (A75-5, A75B-1)', () => {
  it('build, deployment and verification paths are complete whatever else matches', () => {
    for (const path of [
      'apps/web/Dockerfile',
      'infrastructure/docker/keycloak/Dockerfile',
      'apps/core-api/src/main/resources/Dockerfile.extra',
      'infrastructure/docker/compose.yaml',
      'infrastructure/hr-dev/compose.yaml',
      '.github/workflows/e2e.yml',
      'ops/hr-dev/deploy.sh',
      'scripts/dev/verify-on-host.sh',
      'apps/web/package.json',
      'pnpm-lock.yaml',
      'apps/core-api/build.gradle.kts',
      'apps/keycloak-provisioning/build.gradle.kts',
      'apps/ai-service/pyproject.toml',
      'apps/ai-service/uv.lock',
      'apps/web/vite.config.ts',
      'apps/web/docker/nginx.conf.template',
      'apps/web/e2e/hr-dev/landing.spec.ts',
      'infrastructure/hr-dev/caddy/Caddyfile',
      'Makefile',
      '.env.example',
      'some/unknown/file.txt',
    ]) {
      assert.equal(classify([path]), 'complete', path);
    }
    assert.equal(classify(['apps/web/src/a.ts', 'ops/hr-dev/lib.sh']), 'complete');
  });

  it('enumerated Core and web identity and security paths never fall through to app', () => {
    for (const path of [
      'infrastructure/docker/keycloak/realm-divalhr-dev.json',
      'infrastructure/hr-dev/keycloak/realm-divalhr-test.json',
      'apps/keycloak-provisioning/src/main/java/X.java',
      'apps/core-api/src/main/java/com/divalhr/core/identity/api/X.java',
      'apps/core-api/src/main/java/com/divalhr/core/platform/security/SecurityConfig.java',
      'apps/core-api/src/main/java/com/divalhr/core/platform/tenancy/TenantContextResolver.java',
      'apps/core-api/src/main/java/com/divalhr/core/tenant/api/AuthenticatedCaller.java',
      'apps/core-api/src/main/resources/application.yaml',
      'apps/web/src/auth/oidc.ts',
      'apps/web/src/pwa/sw.ts',
    ]) {
      assert.equal(classify([path]), 'identity', path);
    }
  });

  it('classifies migrations, app code, documentation and their combinations', () => {
    assert.equal(
      classify(['apps/core-api/src/main/resources/db/migration/V99__x.sql']),
      'migration',
    );
    assert.equal(
      classify([
        'apps/web/src/features/a.tsx',
        'apps/core-api/src/main/java/com/divalhr/core/people/A.java',
      ]),
      'app',
    );
    assert.equal(classify(['docs/OPS-HR-DEV.md', 'apps/web/README.md']), 'docs-only');
    assert.equal(classify(['docs/API-SPEC.yaml']), 'app');
    assert.equal(classify(['docs/x.md', 'apps/web/src/a.ts']), 'app');
    assert.equal(
      classify([
        'apps/core-api/src/main/resources/db/migration/V99__x.sql',
        'apps/web/src/auth/a.ts',
      ]),
      'migration+identity',
    );
  });

  it('uses both sides of renames and deletions, NUL-delimited, with no shell evaluation', () => {
    const r = repo();
    const base = r.commit({
      'ops/hr-dev/x.sh': 'x\n',
      'apps/web/Dockerfile': 'FROM x\n',
      'docs/a.md': 'a',
    });
    r.git('mv', 'ops/hr-dev/x.sh', 'docs/x.md');
    const renamed = r.commit({}, 'rename out of ops');
    let out = py('classify', '--repo', r.dir, '--head', renamed, '--deployed', base).out;
    assert.match(out, /^class=complete$/mu, 'a rename out of ops/ is still complete');
    r.git('rm', '-q', 'apps/web/Dockerfile');
    const deleted = r.commit({}, 'delete a Dockerfile');
    out = py('classify', '--repo', r.dir, '--head', deleted, '--deployed', renamed).out;
    assert.match(out, /^class=complete$/mu, 'deleting a Dockerfile is complete');
    const odd = r.commit({ 'docs/with space $(touch pwned) ;`id`.md': 'x' }, 'odd name');
    out = py('classify', '--repo', r.dir, '--head', odd, '--deployed', deleted).out;
    assert.match(out, /^class=docs-only$/mu, out);
    const newline = r.commit({ 'docs/two\nlines.md': 'x' }, 'newline in a name');
    out = py('classify', '--repo', r.dir, '--head', newline, '--deployed', odd).out;
    assert.match(out, /^class=complete$/mu, 'a name with a line break is ambiguous');
    assert.equal(sh('ls', [join(r.dir, 'pwned')]).code === 0, false, 'no shell evaluation');
  });

  it('unknown, empty, unreadable or ambiguous input is complete', () => {
    const r = repo();
    const a = r.commit({ 'apps/web/src/a.ts': '1' });
    const b = r.commit({ 'apps/web/src/a.ts': '2' });
    const cls = (...args) =>
      /^class=(.+)$/mu.exec(py('classify', '--repo', r.dir, ...args).out)?.[1];
    assert.equal(cls('--head', b, '--deployed', a), 'app');
    assert.equal(cls('--head', b), 'complete', 'no deployed release (first deployment)');
    assert.equal(cls('--head', b, '--deployed', b), 'complete', 'empty diff');
    assert.equal(cls('--head', a, '--deployed', b), 'complete', 'deployed release not an ancestor');
    assert.equal(cls('--head', b, '--deployed', 'f'.repeat(40)), 'complete', 'unknown commit');
    assert.equal(cls('--head', b, '--deployed', 'main'), 'complete', 'not a full SHA');
    assert.equal(cls('--head', 'main', '--deployed', a), 'complete', 'candidate not a full SHA');
    assert.equal(cls('--head', b, '--deployed', a, '--base', ''), 'complete', 'empty base');
    assert.equal(cls('--head', b, '--deployed', a, '--base', a), 'app', 'base can only widen');
  });
});

describe('rehearsal step matrix (A75B-2)', () => {
  const steps = (cls) => py('steps', '--class', cls).out.trim().split('\n').filter(Boolean);
  const base = ['deploy-first', 'browser-suite', 'realm-verify-final'];

  it('requires realm-verify-final for every deployable class and nothing for docs-only', () => {
    assert.deepEqual(steps('docs-only'), []);
    assert.deepEqual(steps('app'), base);
    assert.deepEqual(steps('migration'), [
      ...base,
      'backup',
      'restore-drill',
      'rollback-migration',
    ]);
    assert.deepEqual(steps('identity'), [...base, 'rollback-identity-change']);
    assert.deepEqual(steps('migration+identity'), [
      ...base,
      'backup',
      'restore-drill',
      'rollback-identity-change',
      'rollback-migration',
    ]);
    assert.equal(py('steps', '--class', 'everything').code, 2);
  });

  it('complete is exactly the steps rehearse.sh runs (no drift)', () => {
    const rehearse = readFileSync(join(ROOT, 'ops/hr-dev/rehearse.sh'), 'utf8');
    const names = [...rehearse.matchAll(/^\s*(?:if )?step ([a-z0-9-]+) /gmu)].map((m) => m[1]);
    assert.deepEqual([...new Set(names)].sort(), steps('complete').sort());
    assert.match(rehearse, /evidence\.py" steps --class "\$CLASS"/u);
    assert.match(rehearse, /SKIP \$name \(class \$CLASS\)/u);
    assert.doesNotMatch(
      rehearse,
      /DIVALHR_REHEARSAL_CLASS|\$\{?REHEARSAL_CLASS/u,
      'no override (A75B-5)',
    );
  });
});

// --- records and the deployment decision ---------------------------------------------------------

const ALL = [
  'first-deploy-units-failure',
  'deploy-first',
  'browser-suite',
  'realm-verify-final',
  'backup',
  'host-lock-concurrency',
  'watchdog-failure-injection',
  'restore-drill',
  'upgrade-units-failure',
  'rollback-identity-change',
  'rollback-migration',
];
const APP_STEPS = ['deploy-first', 'browser-suite', 'realm-verify-final'];

/** A main line with a deployed release, a PR head and its no-fast-forward merge (same tree). */
function scenario(prFiles = { 'apps/web/src/a.ts': 'pr\n' }) {
  const r = repo();
  const deployed = r.commit({ 'apps/web/src/a.ts': 'v1\n', 'docs/a.md': 'a' }, 'deployed');
  r.git('checkout', '-q', '-b', 'pr');
  const head = r.commit(prFiles, 'pr');
  r.git('checkout', '-q', 'main');
  r.git('merge', '-q', '--no-ff', '-m', 'merge', 'pr');
  const merge = r.git('rev-parse', 'HEAD');
  return { r, deployed, head, merge };
}

let runClock = 0;
/** A finished run directory, recorded with evidence.py record. */
function makeRun(s, runs, opts = {}) {
  const {
    commit = s.head,
    profile = 'full',
    stage = profile === 'pr' ? 'pr' : 'all',
    exit = 0,
    steps = ALL,
    skipped = [],
    failed = [],
    stages = ['core-check', 'e2e', 'hrdev-rehearsal'],
    cls = 'complete',
  } = opts;
  runClock += 1;
  const id = `202610${String(10 + runClock).padStart(2, '0')}T120000Z-${commit.slice(0, 12)}`;
  const d = join(runs, id);
  mkdirSync(join(d, 'logs'), { recursive: true });
  s.r.bundle(commit, join(d, 'verify.bundle'));
  writeFileSync(join(d, 'stage'), `${stage}\n`);
  writeFileSync(join(d, 'profile'), `${profile}\n`);
  writeFileSync(
    join(d, 'logs', `${stage}.summary`),
    [
      'INFO java_major=21',
      ...stages.map((x) => `PASS ${x}`),
      'TESTS core-api total=901 failures=0 errors=0 skipped=0',
    ].join('\n') + '\n',
  );
  writeFileSync(
    join(d, 'logs', 'rehearsal.summary'),
    [
      `CLASS ${cls}`,
      ...steps
        .filter((x) => !skipped.includes(x))
        .map((x) => (failed.includes(x) ? `FAIL ${x}` : `PASS ${x}`)),
      ...skipped.map((x) => `SKIP ${x} (class ${cls})`),
      'PASS current release is the deployed SHA',
    ].join('\n') + '\n',
  );
  const rec = py('record', '--run-dir', d, '--exit', String(exit));
  assert.equal(rec.code, 0, rec.out);
  return { id, dir: d };
}

function decideFor(s, runs, { release = s.merge, deployed = s.deployed } = {}) {
  const bundle = s.r.bundle(release, join(WORK, `release-${++counter}.bundle`));
  return py(
    'decide',
    '--release',
    release,
    '--release-bundle',
    bundle,
    '--runs',
    runs,
    '--deployed',
    deployed,
  );
}

const runsDir = () => {
  const d = join(WORK, `runs-${++counter}`);
  mkdirSync(d);
  return d;
};

describe('evidence records (A75B-3, A75B-4)', () => {
  it('writes a read-only record and checksum once, for passing and failing runs', () => {
    const s = scenario();
    const runs = runsDir();
    const ok = makeRun(s, runs);
    const failed = makeRun(s, runs, { exit: 1 });
    for (const run of [ok, failed]) {
      for (const f of ['evidence.json', 'evidence.sha256']) {
        assert.equal(sh('stat', ['-c', '%a', join(run.dir, f)]).out.trim(), '444', f);
      }
    }
    const rec = JSON.parse(readFileSync(join(ok.dir, 'evidence.json'), 'utf8'));
    assert.equal(rec.profile, 'full', 'legacy stage all is recorded as profile full');
    assert.equal(rec.stage, 'all');
    assert.equal(rec.commit, s.head);
    assert.equal(rec.tree, s.r.git('rev-parse', `${s.head}^{tree}`));
    assert.equal(rec.rehearsalSteps['rollback-migration'], 'PASS');
    assert.equal(rec.rehearsalSteps['current'], undefined, 'only step names are recorded');
    assert.deepEqual(rec.coreTests, { total: 901, failures: 0, errors: 0, skipped: 0 });
    assert.equal(JSON.parse(readFileSync(join(failed.dir, 'evidence.json'), 'utf8')).exit, 1);
    assert.notEqual(py('record', '--run-dir', ok.dir, '--exit', '0').code, 0, 'never rewritten');
    const legacy = makeRun(s, runs, { profile: 'all' });
    assert.equal(
      JSON.parse(readFileSync(join(legacy.dir, 'evidence.json'), 'utf8')).profile,
      'full',
    );
  });
});

describe('deployment decision (A75-6, A75B-2..A75B-6)', () => {
  it('accepts the PR head evidence for a merge commit with the same tree', () => {
    const s = scenario();
    const runs = runsDir();
    const run = makeRun(s, runs);
    const r = decideFor(s, runs);
    assert.equal(r.code, 0, r.out);
    assert.match(r.out, new RegExp(`^ACCEPT ${run.id} `, 'mu'));
    assert.match(r.out, /release class app/u);
  });

  it('selects the newest qualifying record', () => {
    const s = scenario();
    const runs = runsDir();
    makeRun(s, runs);
    const newer = makeRun(s, runs);
    assert.match(decideFor(s, runs).out, new RegExp(`^ACCEPT ${newer.id} `, 'mu'));
  });

  it('accepts a pr record whose recorded steps cover the recomputed class', () => {
    const s = scenario();
    const runs = runsDir();
    makeRun(s, runs, { profile: 'pr', cls: 'app', steps: APP_STEPS });
    assert.equal(decideFor(s, runs).code, 0);
  });

  const rejects = (s, runs, pattern, opts) => {
    const r = decideFor(s, runs, opts);
    assert.equal(r.code, 1, r.out);
    assert.match(r.out, pattern);
    return r.out;
  };

  it('refuses a different tree', () => {
    const s = scenario();
    const runs = runsDir();
    makeRun(s, runs);
    const later = s.r.commit({ 'apps/web/src/b.ts': 'x' });
    rejects(s, runs, /the verified tree is not the release tree/u, { release: later });
  });

  it('refuses edited, writable, unchecksummed, linked or misplaced records', () => {
    const cases = {
      edited: (d) => {
        chmodSync(join(d, 'evidence.json'), 0o644);
        writeFileSync(
          join(d, 'evidence.json'),
          readFileSync(join(d, 'evidence.json'), 'utf8').replace('"exit": 0', '"exit": 0 '),
        );
        chmodSync(join(d, 'evidence.json'), 0o444);
        return /does not match its checksum/u;
      },
      writable: (d) => {
        chmodSync(join(d, 'evidence.json'), 0o644);
        return /mode 644/u;
      },
      unchecksummed: (d) => {
        rmSync(join(d, 'evidence.sha256'), { force: true });
        return /evidence\.sha256 is missing/u;
      },
      linked: (d) => {
        const copy = join(WORK, `copy-${++counter}.json`);
        writeFileSync(copy, readFileSync(join(d, 'evidence.json')));
        rmSync(join(d, 'evidence.json'), { force: true });
        symlinkSync(copy, join(d, 'evidence.json'));
        return /not a regular file/u;
      },
      bundleReplaced: (d, s) => {
        rmSync(join(d, 'verify.bundle'));
        s.r.bundle(s.deployed, join(d, 'verify.bundle'));
        return /verified bundle does not match the record/u;
      },
      bundleMissing: (d) => {
        rmSync(join(d, 'verify.bundle'));
        return /verified bundle is missing/u;
      },
    };
    for (const [name, damage] of Object.entries(cases)) {
      const s = scenario();
      const runs = runsDir();
      const run = makeRun(s, runs);
      const pattern = damage(run.dir, s);
      rejects(s, runs, pattern);
      assert.ok(name);
    }
  });

  it('refuses a record moved to another run directory', () => {
    const s = scenario();
    const runs = runsDir();
    const run = makeRun(s, runs);
    const other = join(runs, `20261099T000000Z-${s.head.slice(0, 12)}`);
    sh('mv', [run.dir, other]);
    rejects(s, runs, /run ID is not its directory/u);
  });

  it('refuses failed runs, non-qualifying profiles and diagnostic overrides', () => {
    for (const [opts, pattern] of [
      [{ exit: 1 }, /the run failed \(exit 1\)/u],
      [{ profile: 'changed', stage: 'changed' }, /profile changed never qualifies/u],
      [{ profile: 'hrdev', stage: 'hrdev' }, /profile hrdev never qualifies/u],
      [{ profile: 'stack', stage: 'stack' }, /profile stack never qualifies/u],
      [{ profile: 'core', stage: 'core' }, /profile core never qualifies/u],
      [{ stages: ['core-check', 'hrdev-rehearsal'] }, /stage e2e did not pass/u],
    ]) {
      const s = scenario();
      const runs = runsDir();
      makeRun(s, runs, opts);
      rejects(s, runs, pattern);
    }
  });

  it('requires every step of the recomputed class, never trusting the recorded class', () => {
    const migration = { 'apps/core-api/src/main/resources/db/migration/V99__x.sql': 'select 1;\n' };
    let s = scenario(migration);
    let runs = runsDir();
    makeRun(s, runs, {
      profile: 'pr',
      cls: 'complete', // claims more than it ran
      steps: ALL,
      skipped: ['backup', 'restore-drill', 'rollback-migration'],
    });
    rejects(s, runs, /required rehearsal step backup is SKIP \(release class migration\)/u);

    s = scenario(migration);
    runs = runsDir();
    makeRun(s, runs, {
      profile: 'pr',
      cls: 'migration',
      steps: ALL,
      failed: ['rollback-migration'],
    });
    rejects(s, runs, /rollback-migration is FAIL/u);

    s = scenario({
      'apps/web/src/auth/a.ts': 'x',
      'apps/core-api/src/main/resources/db/migration/V9__x.sql': 'y',
    });
    runs = runsDir();
    makeRun(s, runs, {
      profile: 'pr',
      cls: 'migration',
      steps: [...APP_STEPS, 'backup', 'restore-drill', 'rollback-migration'],
    });
    rejects(s, runs, /rollback-identity-change is missing \(release class migration\+identity\)/u);
  });

  it('falls back by class: pr for a safely classified release, full otherwise (A75B-6)', () => {
    let s = scenario();
    let out = rejects(s, runsDir(), /verify the release itself: aws-verify\.sh [0-9a-f]{40} pr$/mu);
    assert.match(out, /release class app/u);

    s = scenario({ 'ops/hr-dev/x.sh': 'x' });
    rejects(s, runsDir(), /aws-verify\.sh [0-9a-f]{40} full$/mu);

    s = scenario();
    let runs = runsDir();
    makeRun(s, runs, { profile: 'pr', cls: 'app', steps: APP_STEPS });
    out = rejects(s, runs, /aws-verify\.sh [0-9a-f]{40} full$/mu, { deployed: '' });
    assert.match(out, /first deployment/u);

    s = scenario({ 'docs/b.md': 'b' });
    runs = runsDir();
    makeRun(s, runs, { profile: 'pr', cls: 'docs-only', steps: [], stages: ['format-check'] });
    out = rejects(s, runs, /aws-verify\.sh [0-9a-f]{40} full$/mu);
    assert.match(out, /requires app rehearsal steps/u, 'no docs-only bypass');
  });

  it('accepts only a full SHA whose bundle names exactly that release', () => {
    const s = scenario();
    const runs = runsDir();
    makeRun(s, runs);
    const bundle = s.r.bundle(s.merge, join(WORK, `release-${++counter}.bundle`));
    for (const release of [s.merge.slice(0, 12), 'main', 'pr', '76']) {
      const r = py('decide', '--release', release, '--release-bundle', bundle, '--runs', runs);
      assert.equal(r.code, 1, release);
      assert.match(r.out, /^REJECT the release must be a full 40-character SHA$/mu);
    }
    const wrong = py('decide', '--release', s.head, '--release-bundle', bundle, '--runs', runs);
    assert.match(wrong.out, /single head is not the release SHA/u);
  });

  it('lists runs without a record and never accepts them', () => {
    const s = scenario();
    const runs = runsDir();
    mkdirSync(join(runs, `20261001T000000Z-${s.head.slice(0, 12)}`));
    const out = rejects(s, runs, /no evidence record/u);
    assert.doesNotMatch(out, /^ACCEPT/mu);
  });
});

describe('wiring (A75B-3, A75B-4, A75B-5)', () => {
  const read = (p) => readFileSync(join(ROOT, p), 'utf8');
  it('records the requested profile apart from the stage and writes the record before exit', () => {
    const verify = read('ops/aws/aws-verify.sh');
    assert.match(verify, /PROFILE="\$STAGE"\n {2}case "\$STAGE" in/u);
    assert.match(verify, /echo "\$PROFILE" > "\$D\/profile"/u);
    const record = verify.indexOf('evidence.py record --run-dir "$D" --exit');
    assert.ok(
      record > 0 && record < verify.indexOf('echo \\$rc > "$D/exit"'),
      'record before exit',
    );
  });
  it('the pr profile classifies from the diffs only; no class override exists anywhere', () => {
    const host = read('scripts/dev/verify-on-host.sh');
    assert.match(host, /evidence\.py" classify --repo "\$ROOT" --head "\$head"/u);
    assert.match(host, /pr\) stage_pr ;;/u);
    for (const file of [
      'scripts/dev/verify-on-host.sh',
      'ops/aws/aws-verify.sh',
      'ops/hr-dev/rehearse.sh',
      'ops/hr-dev/remote-deploy.sh',
      'ops/hr-dev/evidence.py',
    ]) {
      assert.doesNotMatch(read(file), /REHEARSAL_CLASS|os\.environ/u, file);
    }
  });
});
