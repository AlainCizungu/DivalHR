// DEVX-001A: the `changed` profile only ever widens (node --test).
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { classify } from './classify-changed.mjs';

describe('classify-changed', () => {
  it('runs only the narrow checks for clearly scoped changes', () => {
    assert.deepEqual(classify(['docs/I18N.md']), {
      profile: 'changed',
      checks: ['docs'],
      specs: [],
      reasons: [],
    });
    assert.deepEqual(classify(['apps/core-api/src/main/java/com/divalhr/core/X.java']).checks, [
      'core',
    ]);
    const spec = classify(['apps/web/e2e/contracts.spec.ts']);
    assert.deepEqual(
      [spec.profile, spec.checks, spec.specs],
      ['changed', ['e2e', 'web'], ['e2e/contracts.spec.ts']],
    );
  });

  it('runs the whole optimized browser suite for app or shared e2e code', () => {
    assert.deepEqual(classify(['apps/web/src/features/home/HomePage.tsx']).specs, ['*']);
    assert.deepEqual(classify(['apps/web/e2e/support.ts', 'apps/web/e2e/mfa.spec.ts']).specs, [
      '*',
    ]);
    assert.deepEqual(classify(['packages/localization/locales/fr/common.json']).checks, [
      'e2e',
      'web',
    ]);
  });

  it('selects pr for contracts and for anything it does not recognise', () => {
    assert.equal(classify(['docs/API-SPEC.yaml']).profile, 'pr');
    assert.equal(classify(['apps/ai-service/src/divalhr_ai/asgi.py']).profile, 'pr');
    assert.equal(classify(['some/new/place.txt']).profile, 'pr');
    assert.equal(classify(['docs/I18N.md', 'unknown.cfg']).profile, 'pr');
  });

  it('selects full for operational, identity, build, workflow and dependency changes', () => {
    for (const path of [
      'ops/hr-dev/deploy.sh',
      'infrastructure/docker/keycloak/realm-divalhr-dev.json',
      'infrastructure/hr-dev/compose.yaml',
      '.github/workflows/e2e.yml',
      'scripts/dev/verify-on-host.sh',
      'apps/core-api/Dockerfile',
      'apps/keycloak-provisioning/src/main/java/X.java',
      'apps/web/e2e/hr-dev/oidc.spec.ts',
      'pnpm-lock.yaml',
      'apps/core-api/build.gradle.kts',
      'apps/web/package.json',
    ]) {
      assert.equal(classify([path]).profile, 'full', path);
      assert.equal(classify(['docs/I18N.md', path]).profile, 'full', `mixed with ${path}`);
    }
  });

  it('never narrows: full wins over pr, pr over changed', () => {
    assert.equal(classify(['docs/API-SPEC.yaml', 'ops/hr-dev/deploy.sh']).profile, 'full');
    assert.equal(classify(['apps/web/src/main.tsx', 'docs/API-SPEC.yaml']).profile, 'pr');
  });
});
