// DEVX-005 (Issue #214): the Keycloak image consumes a provisioning JAR that was built beforehand,
// with its SHA-256 recorded, and resolves nothing during the image build.
//   node --test scripts/ops/keycloak-image.test.mjs
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

const root = new URL('../../', import.meta.url);
const read = (path) => readFileSync(new URL(path, root), 'utf8');
const dockerfile = read('infrastructure/docker/keycloak/Dockerfile');
const script = read('ops/build/keycloak-provisioning-jar.sh');
const pinned = (text) => text.match(/eclipse-temurin:[0-9._]+-jdk@sha256:[0-9a-f]{64}/u)?.[0];

test('the Dockerfile copies the verified JAR and never runs Gradle or resolves a dependency', () => {
  assert.doesNotMatch(
    dockerfile,
    /gradle/iu.test(dockerfile.replace(/^#.*$/gmu, '')) ? /^/ : /(?!)/u,
  );
  const code = dockerfile.replace(/^#.*$/gmu, '');
  assert.doesNotMatch(code, /gradle|mvn|maven|curl|wget|apt-get|dnf|microdnf/iu);
  assert.match(code, /COPY infrastructure\/docker\/keycloak\/build\/divalhr-provisioning\.jar /u);
  assert.match(code, /sha256sum --check --strict/u);
  assert.match(
    code,
    /COPY --from=verify \/verified\/divalhr-provisioning\.jar \/opt\/keycloak\/providers\//u,
  );
  assert.match(code, /ARG PROVISIONING_JAR_SHA256/u);
  assert.match(code, /PROVISIONING_JAR_SHA256 is required/u, 'an empty digest fails the build');
});

test('the build context holds only the recorded JAR', () => {
  const ignore = read('infrastructure/docker/keycloak/Dockerfile.dockerignore')
    .split('\n')
    .filter((line) => line && !line.startsWith('#'));
  assert.deepEqual(ignore, ['*', '!infrastructure/docker/keycloak/build/divalhr-provisioning.jar']);
});

test('the build script and the verify stage use the same pinned Temurin image', () => {
  assert.ok(pinned(dockerfile), 'the Dockerfile pins Temurin by digest');
  assert.equal(pinned(script), pinned(dockerfile));
});

test('Compose passes the digest to the image build', () => {
  for (const file of ['infrastructure/docker/compose.yaml', 'infrastructure/hr-dev/compose.yaml']) {
    assert.match(
      read(file),
      /PROVISIONING_JAR_SHA256: \$\{KEYCLOAK_PROVISIONING_JAR_SHA256:-\}/u,
      file,
    );
  }
});

test('every wrapper that builds the image builds and verifies the JAR first', () => {
  const deploy = read('ops/hr-dev/remote-deploy.sh');
  assert.ok(
    deploy.indexOf('keycloak-provisioning-jar.sh') < deploy.indexOf('hr_compose "$SHA" build'),
    'deployment: JAR before images',
  );
  assert.match(deploy, /\^\[0-9a-f\]\{64\}\$/u);
  const host = read('scripts/dev/verify-on-host.sh');
  assert.ok(
    host.indexOf('keycloak-provisioning-jar.sh') < host.indexOf('run compose-build'),
    'host verification: JAR before images',
  );
  const e2e = read('.github/workflows/e2e.yml');
  assert.ok(
    e2e.indexOf('Build and verify the Keycloak provisioning JAR') <
      e2e.indexOf('uses: docker/build-push-action'),
    'container validation: JAR before the build',
  );
  assert.match(e2e, /PROVISIONING_JAR_SHA256=\$\{\{ steps\.jar\.outputs\.sha256 \}\}/u);
  assert.ok(
    e2e.lastIndexOf('Build and verify the Keycloak provisioning JAR') <
      e2e.indexOf('Start stack (development-only configuration)'),
    'smoke: JAR before the stack starts',
  );
});

test('plugin and dependency versions stay pinned and the JAR is reproducible', () => {
  const gradle = read('apps/keycloak-provisioning/build.gradle.kts');
  assert.doesNotMatch(gradle, /["':]\+["']|latest\.|\[[0-9.]+,/u, 'no dynamic versions');
  assert.match(gradle, /isPreserveFileTimestamps = false/u);
  assert.match(gradle, /isReproducibleFileOrder = true/u);
});

test('the generated JAR and its record are not tracked', () => {
  const result = spawnSync(
    'git',
    ['check-ignore', 'infrastructure/docker/keycloak/build/divalhr-provisioning.jar'],
    {
      cwd: root.pathname,
      encoding: 'utf8',
    },
  );
  assert.equal(result.status, 0, 'build/ is ignored');
});

test('--verify accepts the recorded JAR and refuses a changed one', () => {
  // The script locates the repository from its own path: run a copy inside a scratch tree.
  const dir = mkdtempSync(join(tmpdir(), 'kcjar-'));
  mkdirSync(join(dir, 'ops/build'), { recursive: true });
  mkdirSync(join(dir, 'infrastructure/docker/keycloak/build'), { recursive: true });
  writeFileSync(join(dir, 'ops/build/keycloak-provisioning-jar.sh'), script, { mode: 0o755 });
  const jar = join(dir, 'infrastructure/docker/keycloak/build/divalhr-provisioning.jar');
  writeFileSync(jar, 'extension bytes');
  const sha = createHash('sha256').update('extension bytes').digest('hex');
  writeFileSync(`${jar}.sha256`, `${sha}  divalhr-provisioning.jar\n`);
  const verify = () =>
    spawnSync('bash', [join(dir, 'ops/build/keycloak-provisioning-jar.sh'), '--verify'], {
      encoding: 'utf8',
    });
  const ok = verify();
  assert.equal(ok.status, 0, ok.stderr);
  assert.equal(ok.stdout.trim(), `KEYCLOAK_PROVISIONING_JAR_SHA256=${sha}`);
  writeFileSync(jar, 'tampered bytes');
  const bad = verify();
  assert.equal(bad.status, 1);
  assert.match(bad.stderr, /does not match its recorded SHA-256/u);
});

test('the scripts that are executed directly are tracked as executable', () => {
  // A checkout (CI, a release unpacked on hr-dev) runs these without `bash`; mode 100644 fails with
  // "Permission denied" (found in CI on the first DEVX-005 run).
  const modes = spawnSync(
    'git',
    ['ls-files', '-s', 'ops/build/keycloak-provisioning-jar.sh', 'ops/hr-dev/secret-needles.sh'],
    { cwd: root.pathname, encoding: 'utf8' },
  )
    .stdout.trim()
    .split('\n')
    .map((line) => line.split(' ')[0]);
  assert.deepEqual(modes, ['100755', '100755']);
});
