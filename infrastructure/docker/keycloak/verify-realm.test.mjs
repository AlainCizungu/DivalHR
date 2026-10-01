// Unit tests of the read-only realm verification (MVP-011 A4, Issue #31 A3) against the committed
// development realm, served through a fake admin API. Run: node --test infrastructure/docker/keycloak/
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { collect, evaluate, MARKER_ROLE, modeOf, RULES } from './verify-realm.mjs';

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

function userByName(realm, name) {
  return realm.users.find((u) => u.username === name);
}

/** Effective realm roles: direct, from groups, and composites, recursively. */
function effectiveRealmRoles(realm, user) {
  const seen = new Set();
  const visit = (name) => {
    if (seen.has(name)) return;
    seen.add(name);
    const role = realm.roles.realm.find((r) => r.name === name);
    (role?.composites?.realm ?? []).forEach(visit);
  };
  (user.realmRoles ?? []).forEach(visit);
  for (const path of user.groups ?? []) {
    const group = realm.groups.find((g) => g.path === path);
    (group?.realmRoles ?? []).forEach(visit);
  }
  return [...seen];
}

/** Effective roles of one client: direct, from groups, and from realm-role composites. */
function effectiveClientRoles(realm, user, clientId) {
  const roles = new Set(user.clientRoles?.[clientId] ?? []);
  for (const path of user.groups ?? []) {
    const group = realm.groups.find((g) => g.path === path);
    (group?.clientRoles?.[clientId] ?? []).forEach((r) => roles.add(r));
  }
  for (const name of effectiveRealmRoles(realm, user)) {
    const role = realm.roles.realm.find((r) => r.name === name);
    (role?.composites?.client?.[clientId] ?? []).forEach((r) => roles.add(r));
  }
  return [...roles];
}

function fakeAdminApi(realm, extensions = ['divalhr-provisioning']) {
  // Keycloak creates realm-management itself; the export omits it.
  const clients = () =>
    [...realm.clients, { clientId: 'realm-management' }].map((c) => ({ ...c, id: c.clientId }));
  const get = async (path) => {
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
    if (parts[1] === 'clients' && parts.length === 2) {
      return params.has('clientId')
        ? clients().filter((c) => c.clientId === params.get('clientId'))
        : clients();
    }
    if (parts[1] === 'clients' && parts[3] === 'service-account-user') {
      const user = realm.users.find((u) => u.serviceAccountClientId === parts[2]);
      return { ...user, id: user.username };
    }
    if (parts[1] === 'clients' && parts[3] === 'authz') {
      return (
        realm.clients.find((c) => c.clientId === parts[2])?.authorizationSettings?.policies ?? []
      );
    }
    if (parts[1] === 'users' && parts[3] === 'role-mappings' && parts[4] === 'realm') {
      return effectiveRealmRoles(realm, userByName(realm, parts[2])).map((name) => ({ name }));
    }
    if (parts[1] === 'users' && parts[3] === 'role-mappings' && parts[4] === 'clients') {
      return effectiveClientRoles(realm, userByName(realm, parts[2]), parts[5]).map((name) => ({
        name,
      }));
    }
    if (parts[1] === 'users' && parts[3] === 'groups') {
      return (userByName(realm, parts[2]).groups ?? []).map((path) => ({ path }));
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
  const getRoot = async (path) => {
    if (path !== '/serverinfo') throw new Error(`unexpected root path ${path}`);
    const providers = Object.fromEntries(extensions.map((id) => [id, {}]));
    return { providers: { 'realm-restapi-extension': { providers } } };
  };
  return { get, getRoot };
}

async function statuses(mutate = () => {}, mode = 'final', extensions) {
  const realm = structuredClone(REALM);
  mutate(realm);
  const api = fakeAdminApi(realm, extensions);
  return Object.fromEntries(
    evaluate(await collect(api.get, api.getRoot), mode).map(({ rule, status }) => [rule, status]),
  );
}

async function results(mutate = () => {}, extensions) {
  const all = await statuses(mutate, 'final', extensions);
  return Object.fromEntries(Object.entries(all).map(([rule, status]) => [rule, status === 'PASS']));
}

const serviceAccount = (realm) => userByName(realm, 'service-account-divalhr-core-provisioner');
const grantRealmManagement = (realm) => {
  serviceAccount(realm).clientRoles['realm-management'] = ['query-users'];
};

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

test('each provisioning misconfiguration fails its rule (Issue #31)', async () => {
  assert.equal((await results(() => {}, []))['provisioning-extension-deployed'], false);
  const cases = [
    [
      'provisioning-capability',
      (r) => {
        serviceAccount(r).clientRoles = {};
      },
    ],
    [
      'provisioning-capability',
      (r) => {
        r.clients.find((c) => c.clientId === 'divalhr-web').protocolMappers = [
          {
            name: 'aud',
            protocol: 'openid-connect',
            protocolMapper: 'oidc-audience-mapper',
            config: { 'included.custom.audience': 'divalhr-provisioning' },
          },
        ];
      },
    ],
    [
      'provisioning-capability',
      (r) => {
        r.clients.find((c) => c.clientId === 'divalhr-provisioning').standardFlowEnabled = true;
      },
    ],
    ['provisioner-has-no-broad-admin-rights', grantRealmManagement],
    [
      'provisioner-has-no-broad-admin-rights',
      (r) => {
        // Transitive: a group whose realm role is a composite of an admin role.
        r.roles.realm.push({
          name: 'innocent-helper',
          composite: true,
          composites: { client: { 'realm-management': ['manage-users'] } },
        });
        r.groups.push({ name: 'innocent', path: '/innocent', realmRoles: ['innocent-helper'] });
        serviceAccount(r).groups = ['/innocent'];
      },
    ],
    [
      'provisioner-has-no-broad-admin-rights',
      (r) => {
        serviceAccount(r).realmRoles = ['platform-admin'];
      },
    ],
    [
      'provisioner-has-no-broad-admin-rights',
      (r) => {
        // A fine-grained admin permission (v2) that names the provisioner's service account.
        r.adminPermissionsEnabled = true;
        r.clients.push({
          clientId: 'admin-permissions',
          authorizationSettings: {
            policies: [
              {
                name: 'p',
                type: 'user',
                config: { users: '["service-account-divalhr-core-provisioner"]' },
              },
            ],
          },
        });
      },
    ],
  ];
  for (const [rule, mutate] of cases) {
    const all = await results(mutate);
    assert.equal(all[rule], false, rule);
  }
});

test('pre-cutover mode reports only the broad-rights rule as pending, never as passing (A3)', async () => {
  const final = await statuses(grantRealmManagement, 'final');
  assert.equal(final['provisioner-has-no-broad-admin-rights'], 'FAIL');
  const pre = await statuses(grantRealmManagement, 'pre-cutover');
  assert.equal(pre['provisioner-has-no-broad-admin-rights'], 'PENDING');
  assert.deepEqual(
    Object.entries(pre).filter(([, status]) => status !== 'PASS'),
    [['provisioner-has-no-broad-admin-rights', 'PENDING']],
  );
  // Every other failure stays a failure in pre-cutover mode.
  const missing = await statuses(grantRealmManagement, 'pre-cutover', []);
  assert.equal(missing['provisioning-extension-deployed'], 'FAIL');
  // Once the rights are gone, both modes pass.
  assert.equal(
    (await statuses(() => {}, 'pre-cutover'))['provisioner-has-no-broad-admin-rights'],
    'PASS',
  );
  assert.throws(() => modeOf(['--mode=relaxed'], {}));
  assert.equal(modeOf([], {}), 'final');
  assert.equal(modeOf([], { KEYCLOAK_VERIFY_MODE: 'pre-cutover' }), 'pre-cutover');
});

test('results carry rule names and outcomes only', async () => {
  const realm = structuredClone(REALM);
  const api = fakeAdminApi(realm);
  const output = JSON.stringify(evaluate(await collect(api.get, api.getRoot)));
  for (const secret of ['dev-only-', 'totp', 'urn:divalhr', 'HmacSHA1', 'service-account']) {
    assert.equal(output.includes(secret), false, secret);
  }
});
