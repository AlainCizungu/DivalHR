// Unit tests of the read-only realm verification (MVP-011 A4) against the committed development
// realm, served through a fake admin API. Run: node --test infrastructure/docker/keycloak/
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { collect, evaluate, MARKER_ROLE, RULES } from './verify-realm.mjs';

const REALM = JSON.parse(
  readFileSync(new URL('./realm-divalhr-dev.json', import.meta.url), 'utf8'),
);

/** The admin API's flattened executions of a flow, from the export's nested representation. */
function flatten(realm, alias, level = 0) {
  const flow = realm.authenticationFlows.find((f) => f.alias === alias);
  return [...flow.authenticationExecutions]
    .sort((a, b) => a.priority - b.priority)
    .flatMap((e, index) => {
      const row = {
        level,
        index,
        requirement: e.requirement,
        authenticationFlow: e.authenticatorFlow === true,
        ...(e.authenticatorFlow
          ? { displayName: e.flowAlias }
          : { providerId: e.authenticator, displayName: e.authenticator }),
        ...(e.authenticatorConfig ? { authenticationConfig: e.authenticatorConfig } : {}),
      };
      return e.authenticatorFlow ? [row, ...flatten(realm, e.flowAlias, level + 1)] : [row];
    });
}

function fakeAdminApi(realm) {
  return async (path) => {
    const [resource, query = ''] = path.split('?');
    const params = new URLSearchParams(query);
    const parts = resource.split('/').map(decodeURIComponent);
    if (resource === '') return realm;
    if (parts[1] === 'authentication' && parts[2] === 'flows') return flatten(realm, parts[3]);
    if (parts[1] === 'authentication' && parts[2] === 'config') {
      return realm.authenticatorConfig.find((c) => c.alias === parts[3]);
    }
    if (parts[1] === 'authentication' && parts[2] === 'required-actions') {
      return realm.requiredActions.find((a) => a.alias === parts[3]);
    }
    if (parts[1] === 'clients') {
      return realm.clients.filter((c) => c.clientId === params.get('clientId'));
    }
    if (parts[1] === 'roles' && parts[3] === 'composites') {
      const role = realm.roles.realm.find((r) => r.name === parts[2]);
      return (role?.composites?.realm ?? []).map((name) => ({ name }));
    }
    if (parts[1] === 'roles' && parts[3] === 'users') {
      return realm.users.filter((u) => (u.realmRoles ?? []).includes(parts[2]));
    }
    if (parts[1] === 'localization') return realm.localizationTexts?.[parts[2]] ?? {};
    throw new Error(`unexpected path ${path}`);
  };
}

async function results(mutate = () => {}) {
  const realm = structuredClone(REALM);
  mutate(realm);
  return Object.fromEntries(
    evaluate(await collect(fakeAdminApi(realm))).map(({ rule, pass }) => [rule, pass]),
  );
}

const client = (realm) => realm.clients.find((c) => c.clientId === 'divalhr-web');
const config = (realm, alias) => realm.authenticatorConfig.find((c) => c.alias === alias).config;

test('the committed development realm passes every rule', async () => {
  const all = await results();
  assert.deepEqual(
    Object.keys(all),
    RULES.map(([rule]) => rule),
  );
  assert.deepEqual(
    Object.entries(all).filter(([, pass]) => !pass),
    [],
  );
});

test('each misconfiguration fails its rule', async () => {
  const cases = [
    ['browser-flow-bound', (r) => (r.browserFlow = 'browser')],
    [
      'browser-flow-structure',
      (r) => {
        const flow = r.authenticationFlows.find((f) => f.alias === 'divalhr browser level 2 otp');
        flow.authenticationExecutions[1].requirement = 'DISABLED';
      },
    ],
    ['loa-levels', (r) => (config(r, 'divalhr-loa-2')['loa-condition-level'] = '1')],
    ['loa-levels', (r) => (config(r, 'divalhr-loa-1')['loa-max-age'] = '0')],
    ['level2-only-for-marker-role', (r) => (config(r, 'divalhr-privileged-role').negate = 'true')],
    [
      'unenrolled-privileged-denied',
      (r) => (config(r, 'divalhr-not-enrolled').check_result = 'executed'),
    ],
    ['acr-map-and-client-minimum', (r) => delete client(r).attributes['minimum.acr.value']],
    [
      'acr-map-and-client-minimum',
      (r) => (client(r).attributes['acr.loa.map'] = '{"urn:divalhr:loa:mfa":1}'),
    ],
    ['otp-policy', (r) => (r.otpPolicyCodeReusable = true)],
    ['otp-policy', (r) => (r.otpPolicyLookAheadWindow = 3)],
    [
      'no-self-enrollment',
      (r) => (r.requiredActions.find((a) => a.alias === 'CONFIGURE_TOTP').defaultAction = true),
    ],
    [
      'marker-role-composition',
      (r) => {
        const employee = r.roles.realm.find((role) => role.name === 'employee');
        employee.composite = true;
        employee.composites = { realm: [MARKER_ROLE] };
      },
    ],
    ['marker-role-composition', (r) => r.users[0].realmRoles.push(MARKER_ROLE)],
    [
      'web-client-pkce-no-password-or-device-grant',
      (r) => (client(r).directAccessGrantsEnabled = true),
    ],
    ['brute-force-protection', (r) => (r.permanentLockout = true)],
    ['locales-and-messages', (r) => delete r.localizationTexts.fr.divalhrMfaEnrollmentRequired],
    ['login-and-admin-events', (r) => (r.adminEventsDetailsEnabled = true)],
  ];
  for (const [rule, mutate] of cases) {
    const all = await results(mutate);
    assert.equal(all[rule], false, rule);
  }
});

test('results carry rule names and outcomes only', async () => {
  const realm = structuredClone(REALM);
  const output = JSON.stringify(evaluate(await collect(fakeAdminApi(realm))));
  for (const secret of ['dev-only-', 'totp', 'urn:divalhr', 'HmacSHA1']) {
    assert.equal(output.includes(secret), false, secret);
  }
});
