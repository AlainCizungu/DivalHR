import assert from 'node:assert/strict';
import { test } from 'node:test';
import { evaluate, report } from './membership-preflight.mjs';

const A = '00000000-0000-4000-8000-00000000000a';
const B = '00000000-0000-4000-8000-00000000000b';
const id = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const SUBJECTS = ['kc-ok', 'kc-missing', 'kc-foreign', 'kc-role', 'kc-both', 'kc-gone', 'kc-off'];

const users = [
  { id: 'kc-ok', enabled: true, tenant: A, roles: ['tenant-admin', 'divalhr-privileged-mfa'] },
  { id: 'kc-missing', enabled: true, tenant: A, roles: ['employee'] },
  { id: 'kc-foreign', enabled: true, tenant: A, roles: ['tenant-admin'] },
  { id: 'kc-role', enabled: true, tenant: B, roles: ['employee'] },
  { id: 'kc-both', enabled: true, tenant: B, roles: ['employee', 'tenant-admin'] },
  { id: 'kc-notenant', enabled: true, tenant: null, roles: ['employee'] },
  { id: 'kc-off', enabled: false, tenant: A, roles: ['employee'] },
  { id: 'kc-platform', enabled: true, tenant: A, roles: ['platform-admin'] },
];
const memberships = [
  { id: id(1), tenant: A, subject: 'kc-ok', role: 'tenant-admin' },
  { id: id(2), tenant: B, subject: 'kc-foreign', role: 'tenant-admin' },
  { id: id(3), tenant: B, subject: 'kc-role', role: 'tenant-admin' },
  { id: id(4), tenant: B, subject: 'kc-both', role: 'employee' },
  { id: id(5), tenant: A, subject: 'kc-gone', role: 'employee' },
  { id: id(6), tenant: A, subject: 'kc-off', role: 'employee' },
];

test('every category is detected and only blocking ones fail the preflight', () => {
  const entries = evaluate(users, memberships);
  const categories = entries.map((e) => e.category).sort();
  assert.deepEqual(categories, [
    'DISABLED_WITH_MEMBERSHIP',
    'MISSING_MEMBERSHIP',
    'MISSING_TENANT_CLAIM',
    'MULTIPLE_TOKEN_ROLES',
    'OK',
    'OK',
    'OK',
    'ORPHAN_MEMBERSHIP',
    'ROLE_MISMATCH',
    'TENANT_MISMATCH',
  ]);
  const { lines, exitCode } = report(entries, 'divalhr-dev');
  assert.equal(exitCode, 1);
  assert.ok(lines.includes(`PREFLIGHT MISSING_MEMBERSHIP tenant=${A} count=1`));
  assert.ok(lines.includes(`PREFLIGHT TENANT_MISMATCH tenant=${A} count=1`));
  assert.ok(lines.includes(`PREFLIGHT ROLE_MISMATCH tenant=${B} count=1`));
  assert.ok(lines.includes(`MEMBERSHIP ORPHAN_MEMBERSHIP id=${id(5)}`));
  assert.ok(lines.includes(`MEMBERSHIP TENANT_MISMATCH id=${id(2)}`));
  assert.equal(lines.at(-1), 'RESULT FAIL blocking=3');
});

test('a consistent realm passes', () => {
  const { lines, exitCode } = report(
    evaluate(users.slice(0, 1), memberships.slice(0, 1)),
    'divalhr-dev',
  );
  assert.equal(exitCode, 0);
  assert.deepEqual(lines, [`PREFLIGHT OK tenant=${A} count=1`, 'RESULT PASS blocking=0']);
});

test('the output never contains a subject, username, address or credential', () => {
  const withPersonal = users.map((u) => ({
    ...u,
    username: `${u.id}-name`,
    email: `${u.id}@x.cd`,
  }));
  const { lines } = report(evaluate(withPersonal, memberships), 'divalhr-dev');
  const output = lines.join('\n');
  for (const subject of SUBJECTS) assert.ok(!output.includes(subject), subject);
  assert.ok(!output.includes('@'));
  assert.ok(!/password|token|secret/iu.test(output.replace(/\b[A-Z_]{4,}\b/gu, '')));
});

test('disabled users never block the rollout', () => {
  const { exitCode } = report(
    evaluate([{ id: 'x', enabled: false, tenant: A, roles: ['employee'] }], []),
    'r',
  );
  assert.equal(exitCode, 0);
});
