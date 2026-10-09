// DEVX-001A (R76-3): the five-times-higher per-subject request limits exist only in the
// development Compose stack. The test environment (hr-dev) and the Core application defaults keep
// the production values, and the limits browser tests exercise or that were not part of the
// approval stay untouched.  node --test scripts/dev/dev-rate-limits.test.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

const root = new URL('../../', import.meta.url);
const read = (path) => readFileSync(new URL(path, root), 'utf8');

/** The approved development overrides and the Core defaults they must not replace. */
const OVERRIDES = {
  DIVALHR_EMPLOYEE_IMPORT_REQUESTS_PER_MINUTE: { dev: 50, core: 10 },
  DIVALHR_EMPLOYEE_READ_REQUESTS_PER_MINUTE: { dev: 300, core: 60 },
  DIVALHR_EMPLOYMENT_CHANGE_REQUESTS_PER_MINUTE: { dev: 100, core: 20 },
  DIVALHR_CONTRACT_READ_REQUESTS_PER_MINUTE: { dev: 300, core: 60 },
  DIVALHR_CONTRACT_WRITE_REQUESTS_PER_MINUTE: { dev: 100, core: 20 },
  DIVALHR_CONTRACT_SELF_REQUESTS_PER_MINUTE: { dev: 150, core: 30 },
};

/** Limits that stay at their defaults everywhere (with the Core default). */
const UNCHANGED = {
  DIVALHR_ACCESS_REVIEW_REQUESTS_PER_MINUTE: 30,
  DIVALHR_DENIAL_AUDIT_PER_ACTOR_PER_MINUTE: 20,
  DIVALHR_DENIAL_AUDIT_PER_INSTANCE_PER_MINUTE: 600,
  DIVALHR_CONTRACT_EXPIRATION_REQUESTS_PER_MINUTE: 60,
  DIVALHR_EMPLOYEE_IMPORT_TENANT_UPLOADS: 30,
  DIVALHR_EMPLOYEE_IMPORT_TENANT_COMMITS: 60,
  DIVALHR_EMPLOYEE_SEARCH_TENANT_REQUESTS: 300,
  DIVALHR_EMPLOYMENT_CHANGE_TENANT_WRITES: 200,
  DIVALHR_CONTRACT_TENANT_WRITES: 200,
  DIVALHR_LEAVE_POLICY_READ_REQUESTS_PER_MINUTE: 60,
  DIVALHR_LEAVE_POLICY_WRITE_REQUESTS_PER_MINUTE: 20,
  DIVALHR_LEAVE_POLICY_TENANT_WRITES: 200,
};

/** `NAME: ${NAME:-value}` entries of a Compose file. */
function composeValues(text) {
  const values = {};
  for (const m of text.matchAll(/^\s+(DIVALHR_[A-Z_]+):\s*(.+)$/gmu)) values[m[1]] = m[2].trim();
  return values;
}

/** `${NAME:default}` placeholders of the Core application configuration. */
function coreDefaults(text) {
  const values = {};
  for (const m of text.matchAll(/\$\{(DIVALHR_[A-Z_]+):([^}]*)\}/gu)) values[m[1]] = m[2];
  return values;
}

const dev = composeValues(read('infrastructure/docker/compose.yaml'));
const hrDev = read('infrastructure/hr-dev/compose.yaml');
const core = coreDefaults(read('apps/core-api/src/main/resources/application.yaml'));

describe('development-only request limits (R76-3)', () => {
  it('the development Compose stack sets exactly the approved overrides', () => {
    for (const [name, { dev: value }] of Object.entries(OVERRIDES)) {
      assert.equal(dev[name], `\${${name}:-${value}}`, name);
    }
  });

  it('the test environment (hr-dev) overrides none of them', () => {
    for (const name of [...Object.keys(OVERRIDES), ...Object.keys(UNCHANGED)]) {
      assert.ok(!hrDev.includes(name), `${name} appears in infrastructure/hr-dev/compose.yaml`);
    }
  });

  it('the Core application defaults keep the production values', () => {
    for (const [name, { core: value }] of Object.entries(OVERRIDES)) {
      assert.equal(core[name], String(value), name);
    }
    for (const [name, value] of Object.entries(UNCHANGED)) {
      assert.equal(core[name], String(value), name);
    }
  });

  it('the development stack leaves the other limits at their defaults', () => {
    for (const name of Object.keys(UNCHANGED)) assert.equal(dev[name], undefined, name);
    const raised = Object.keys(dev).filter((name) => /REQUESTS|PER_MINUTE|TENANT_/u.test(name));
    assert.deepEqual(raised.sort(), Object.keys(OVERRIDES).sort());
  });
});
