#!/usr/bin/env node
// MVP-012A (Issue #37, architect decision A3, A7 and M9): read-only preflight before the membership
// gate is enabled in a shared environment. It compares the identity provider's tenant users with
// the Core's tenant memberships and reports, per tenant, how many users would keep or lose access.
//
// Privacy (A3, guardrail): it prints counts, tenant IDs and membership IDs only. Never addresses,
// usernames, names or identity-provider subjects. Keycloak users that need remediation are found by
// the realm administrator in Keycloak itself, from the printed tenant and category.
// Credentials and tokens are read from the environment only: never from arguments or files, never
// written, logged or included in a remediation hint.
//
// Exit code: 0 no blocking discrepancy, 1 blocking discrepancies, 2 the data could not be read.
//
// Keycloak (HTTP admin API, GET only):
//   KEYCLOAK_URL          default http://localhost:8180
//   KEYCLOAK_REALM        default divalhr-dev
//   KEYCLOAK_ADMIN_TOKEN  a short-lived token of an administrator who may view users, or
//   KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD  a local master-realm administrator (password
//                         grant; development Compose stack only)
// Core database (read-only; SELECT on identity.tenant_membership):
//   PREFLIGHT_DB_TRANSPORT=compose  the development Compose stack (psql inside the container)
//   PREFLIGHT_DB_TRANSPORT=psql     psql with the standard PG* environment variables (PGHOST,
//                                   PGDATABASE, PGUSER, PGPASSWORD or a .pgpass the operator owns)

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const TENANT_ROLES = ['tenant-admin', 'employee'];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

/** Categories whose enabled users would lose access when the gate is enabled. */
export const BLOCKING = new Set(['MISSING_MEMBERSHIP', 'TENANT_MISMATCH', 'ROLE_MISMATCH']);
/** Categories reported with membership IDs (never subjects). */
const MEMBERSHIP_SIDE = new Set([
  'TENANT_MISMATCH',
  'ROLE_MISMATCH',
  'ORPHAN_MEMBERSHIP',
  'MEMBERSHIP_WITHOUT_TOKEN_ROLE',
  'DISABLED_WITH_MEMBERSHIP',
]);

/**
 * Classifies every identity. `users`: [{ id, enabled, tenant, roles }] (effective realm roles);
 * `memberships`: [{ id, tenant, subject, role }]. Returns entries { category, tenant, membership }
 * that carry no subject.
 */
export function evaluate(users, memberships) {
  const bySubject = new Map(memberships.map((m) => [m.subject, m]));
  const seen = new Set();
  const entries = [];
  for (const user of users) {
    const tenantRoles = TENANT_ROLES.filter((role) => user.roles.includes(role));
    const membership = bySubject.get(user.id);
    if (membership) seen.add(user.id);
    const claimed = typeof user.tenant === 'string' && UUID.test(user.tenant) ? user.tenant : null;
    if (tenantRoles.length === 0) {
      if (membership) {
        entries.push({
          category: 'MEMBERSHIP_WITHOUT_TOKEN_ROLE',
          tenant: membership.tenant,
          membership: membership.id,
        });
      }
      continue;
    }
    const tenant = claimed ?? 'none';
    let category;
    if (!claimed) category = 'MISSING_TENANT_CLAIM';
    else if (!membership) category = 'MISSING_MEMBERSHIP';
    else if (membership.tenant !== claimed) category = 'TENANT_MISMATCH';
    else if (!tenantRoles.includes(membership.role)) category = 'ROLE_MISMATCH';
    else category = 'OK';
    if (!user.enabled && category !== 'OK') {
      category = membership ? 'DISABLED_WITH_MEMBERSHIP' : 'DISABLED_WITHOUT_MEMBERSHIP';
    }
    entries.push({ category, tenant, membership: membership?.id ?? null });
    if (category === 'OK' && !user.enabled) {
      entries.push({ category: 'DISABLED_WITH_MEMBERSHIP', tenant, membership: membership.id });
    }
    if (tenantRoles.length > 1) {
      entries.push({ category: 'MULTIPLE_TOKEN_ROLES', tenant, membership: null });
    }
  }
  for (const membership of memberships) {
    if (!seen.has(membership.subject)) {
      entries.push({
        category: 'ORPHAN_MEMBERSHIP',
        tenant: membership.tenant,
        membership: membership.id,
      });
    }
  }
  return entries;
}

/** Report lines (no subject, address or username ever) and the exit code. */
export function report(entries, realm) {
  const counts = new Map();
  for (const { category, tenant } of entries) {
    const key = `${category} tenant=${tenant}`;
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const lines = [...counts.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, count]) => `PREFLIGHT ${key} count=${count}`);
  for (const { category, membership } of entries
    .filter((e) => MEMBERSHIP_SIDE.has(e.category) && e.membership)
    .sort((a, b) => `${a.category}${a.membership}`.localeCompare(`${b.category}${b.membership}`))) {
    lines.push(`MEMBERSHIP ${category} id=${membership}`);
  }
  const missingTenants = [
    ...new Set(entries.filter((e) => e.category === 'MISSING_MEMBERSHIP').map((e) => e.tenant)),
  ].sort();
  for (const tenant of missingTenants) {
    lines.push(
      `REMEDIATE MISSING_MEMBERSHIP tenant=${tenant}: in Keycloak (realm ${realm}), list users ` +
        `with attribute tenant_id=${tenant} and a tenant role; give each a membership through ` +
        'a supported path (MVP-014 bootstrap for a first administrator, MVP-010 invitation ' +
        'otherwise) or remove the role.',
    );
  }
  const blocking = entries.filter((e) => BLOCKING.has(e.category)).length;
  lines.push(`RESULT ${blocking === 0 ? 'PASS' : 'FAIL'} blocking=${blocking}`);
  return { lines, exitCode: blocking === 0 ? 0 : 1 };
}

async function keycloakUsers(env) {
  const base = env.KEYCLOAK_URL ?? 'http://localhost:8180';
  const realm = env.KEYCLOAK_REALM ?? 'divalhr-dev';
  let token = env.KEYCLOAK_ADMIN_TOKEN;
  if (!token) {
    const response = await fetch(`${base}/realms/master/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'password',
        client_id: 'admin-cli',
        username: env.KEYCLOAK_ADMIN_USER ?? '',
        password: env.KEYCLOAK_ADMIN_PASSWORD ?? '',
      }),
    });
    if (!response.ok) throw new Error('token');
    token = (await response.json()).access_token;
  }
  const get = async (path) => {
    const response = await fetch(`${base}/admin/realms/${realm}${path}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw new Error('read');
    return response.json();
  };
  const users = [];
  for (let first = 0; ; first += 100) {
    const page = await get(`/users?first=${first}&max=100&briefRepresentation=false`);
    for (const user of page) {
      if (user.serviceAccountClientId || String(user.username).startsWith('service-account-')) {
        continue;
      }
      const roles = (
        await get(`/users/${encodeURIComponent(user.id)}/role-mappings/realm/composite`)
      ).map((role) => role.name);
      users.push({
        id: user.id,
        enabled: user.enabled !== false,
        tenant: user.attributes?.tenant_id?.[0] ?? null,
        roles,
      });
    }
    if (page.length < 100) break;
  }
  return { users, realm };
}

const MEMBERSHIP_QUERY =
  "SELECT coalesce(json_agg(json_build_object('id', id, 'tenant', tenant_id, 'subject', subject, " +
  "'role', role)), '[]') FROM identity.tenant_membership";

function memberships(env, root) {
  const output =
    env.PREFLIGHT_DB_TRANSPORT === 'psql'
      ? execFileSync('psql', ['-X', '-q', '-tA', '-c', MEMBERSHIP_QUERY], {
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'ignore'],
        })
      : execFileSync(
          'docker',
          [
            'compose',
            '-f',
            'infrastructure/docker/compose.yaml',
            '--env-file',
            '.env.example',
            'exec',
            '-T',
            'postgres',
            'psql',
            '-X',
            '-q',
            '-U',
            'postgres',
            '-d',
            'divalhr',
            '-tAc',
            MEMBERSHIP_QUERY,
          ],
          { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
        );
  return JSON.parse(output.trim());
}

async function main() {
  const env = process.env;
  const root = fileURLToPath(new URL('../../', import.meta.url));
  let data;
  try {
    const { users, realm } = await keycloakUsers(env);
    data = { users, realm, memberships: memberships(env, root) };
  } catch {
    // No detail: an error could echo a URL, a token, a server message or a row.
    console.log('RESULT ERROR could-not-read');
    process.exit(2);
  }
  const { lines, exitCode } = report(evaluate(data.users, data.memberships), data.realm);
  for (const line of lines) console.log(line);
  process.exit(exitCode);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main();
}
