#!/usr/bin/env node
// Read-only verification of a DivalHR realm: privileged MFA (MVP-011, A4) and narrow provisioning
// (Issue #31, A3).
//
// It reads the realm through the Keycloak admin API (GET only) and prints one line per rule:
// "PASS <rule>", "FAIL <rule>" or, in pre-cutover mode only, "PENDING <rule>". It never prints
// configuration values, credentials, TOTP secrets, tokens, subjects or personal data. Exit code:
// 0 all rules pass (or are pending in pre-cutover mode), 1 a rule fails, 2 the realm could not be
// read.
//
// Modes (KEYCLOAK_VERIFY_MODE or --mode=<mode>; A3):
//   final        default, and the only mode acceptable as release or operational sign-off evidence:
//                every rule must pass, including that the provisioner holds no broad admin right.
//   pre-cutover  rollout steps A and B only (extension and capability deployed, the Core not yet
//                cut over or the broad rights not yet removed): the broad-rights rule is reported
//                PENDING instead of FAIL. Never release evidence; a deployment is incomplete until
//                final mode passes.
//
// Transports (KEYCLOAK_VERIFY_TRANSPORT):
//   http     KEYCLOAK_URL plus KEYCLOAK_ADMIN_TOKEN (a short-lived token of an administrator who
//            may view the realm), or KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD for a local
//            master-realm administrator (admin-cli password grant).
//   compose  the development Compose stack: runs kcadm.sh inside the keycloak container, over the
//            container's own loopback (works on Docker Desktop for macOS too).
// KEYCLOAK_REALM defaults to divalhr-dev.

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const PWD_ACR = 'urn:divalhr:loa:pwd';
export const MFA_ACR = 'urn:divalhr:loa:mfa';
export const MARKER_ROLE = 'divalhr-privileged-mfa';
export const WEB_CLIENT = 'divalhr-web';
export const PROVISIONER_CLIENT = 'divalhr-core-provisioner';
export const CAPABILITY_CLIENT = 'divalhr-provisioning';
export const CAPABILITY_ROLE = 'provision-invitations';
export const EXTENSION_ID = 'divalhr-provisioning';
/** Rules that pre-cutover mode reports as PENDING when they fail (A3). */
export const CUTOVER_RULES = new Set(['provisioner-has-no-broad-admin-rights']);
export const FLOWS = {
  top: 'divalhr browser',
  forms: 'divalhr browser forms',
  level1: 'divalhr browser level 1 password',
  level2: 'divalhr browser level 2 otp',
  enrolled: 'divalhr browser level 2 enrolled',
  notEnrolled: 'divalhr browser level 2 not enrolled',
};

/** Expected flattened executions of the bound browser flow: [level, requirement, what]. */
const EXPECTED_FLOW = [
  [0, 'ALTERNATIVE', 'auth-cookie'],
  [0, 'ALTERNATIVE', `flow:${FLOWS.forms}`],
  [1, 'CONDITIONAL', `flow:${FLOWS.level1}`],
  [2, 'REQUIRED', 'conditional-level-of-authentication'],
  [2, 'REQUIRED', 'auth-username-password-form'],
  [1, 'CONDITIONAL', `flow:${FLOWS.level2}`],
  [2, 'REQUIRED', 'conditional-level-of-authentication'],
  [2, 'REQUIRED', 'conditional-user-role'],
  [2, 'CONDITIONAL', `flow:${FLOWS.enrolled}`],
  [3, 'REQUIRED', 'conditional-user-configured'],
  [3, 'REQUIRED', 'auth-otp-form'],
  [2, 'CONDITIONAL', `flow:${FLOWS.notEnrolled}`],
  [3, 'REQUIRED', 'conditional-sub-flow-executed'],
  [3, 'REQUIRED', 'deny-access-authenticator'],
];

/**
 * Reads everything the rules need. `get(path)` returns parsed JSON for an admin-API path relative
 * to the realm ('' is the realm itself).
 */
export async function collect(get, getRoot) {
  const realm = await get('');
  const executions = await get(`/authentication/flows/${encodeURIComponent(FLOWS.top)}/executions`);
  const configs = {};
  for (const execution of executions) {
    if (execution.authenticationConfig) {
      configs[execution.authenticationConfig] = await get(
        `/authentication/config/${execution.authenticationConfig}`,
      );
    }
  }
  const clients = await get(`/clients?clientId=${WEB_CLIENT}`);
  const composites = {};
  for (const role of ['platform-admin', 'tenant-admin', 'employee', MARKER_ROLE]) {
    composites[role] = (await get(`/roles/${role}/composites/realm`)).map((r) => r.name);
  }
  const markerHolders = await get(`/roles/${MARKER_ROLE}/users?first=0&max=1`);
  const totpAction = await get('/authentication/required-actions/CONFIGURE_TOTP');
  const messages = {};
  for (const locale of ['fr', 'en']) {
    messages[locale] = await get(`/localization/${locale}`);
  }
  // Issue #31: the provisioner, its effective rights and the extension.
  const allClients = await get('/clients?first=0&max=1000');
  const provisioner = allClients.find((c) => c.clientId === PROVISIONER_CLIENT) ?? null;
  const capabilityClient = allClients.find((c) => c.clientId === CAPABILITY_CLIENT) ?? null;
  const realmManagement = allClients.find((c) => c.clientId === 'realm-management') ?? null;
  let serviceAccount = null;
  let effectiveRealmRoles = [];
  let effectiveAdminRoles = [];
  let effectiveCapability = [];
  let serviceAccountGroups = [];
  if (provisioner) {
    serviceAccount = await get(`/clients/${provisioner.id}/service-account-user`);
    effectiveRealmRoles = (
      await get(`/users/${serviceAccount.id}/role-mappings/realm/composite`)
    ).map((r) => r.name);
    if (realmManagement) {
      effectiveAdminRoles = (
        await get(
          `/users/${serviceAccount.id}/role-mappings/clients/${realmManagement.id}/composite`,
        )
      ).map((r) => r.name);
    }
    if (capabilityClient) {
      effectiveCapability = (
        await get(
          `/users/${serviceAccount.id}/role-mappings/clients/${capabilityClient.id}/composite`,
        )
      ).map((r) => r.name);
    }
    serviceAccountGroups = await get(`/users/${serviceAccount.id}/groups`);
  }
  // Fine-grained admin permissions: v2 (admin-permissions client) and v1 (realm-management).
  const policies = [];
  for (const client of allClients) {
    const v2 = client.clientId === 'admin-permissions' && realm.adminPermissionsEnabled === true;
    const v1 = client.clientId === 'realm-management' && client.authorizationServicesEnabled;
    if (v2 || v1) {
      policies.push(
        ...(await get(`/clients/${client.id}/authz/resource-server/policy?first=0&max=1000`)),
      );
    }
  }
  const serverInfo = await getRoot('/serverinfo');
  const restExtensions = Object.keys(
    serverInfo?.providers?.['realm-restapi-extension']?.providers ?? {},
  );
  return {
    realm,
    executions,
    configs,
    provisioning: {
      allClients,
      provisioner,
      capabilityClient,
      serviceAccount,
      effectiveRealmRoles,
      realmManagementFound: realmManagement !== null,
      effectiveAdminRoles,
      effectiveCapability,
      serviceAccountGroups,
      policies,
      restExtensions,
    },
    web: clients[0] ?? {},
    composites,
    markerHolders,
    totpAction,
    messages,
  };
}

function configOf(snapshot, providerId, level) {
  const execution = snapshot.executions.find(
    (e) => e.providerId === providerId && (level === undefined || e.level === level),
  );
  return (execution && snapshot.configs[execution.authenticationConfig]?.config) ?? {};
}

function loaConfigs(snapshot) {
  return snapshot.executions
    .filter((e) => e.providerId === 'conditional-level-of-authentication')
    .map((e) => snapshot.configs[e.authenticationConfig]?.config ?? {});
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** The rules, in the order printed. Each returns true when the realm satisfies it. */
export const RULES = [
  ['browser-flow-bound', (s) => s.realm.browserFlow === FLOWS.top],
  [
    'browser-flow-structure',
    (s) =>
      JSON.stringify(
        s.executions.map((e) => [
          e.level,
          e.requirement,
          e.authenticationFlow ? `flow:${e.displayName}` : e.providerId,
        ]),
      ) === JSON.stringify(EXPECTED_FLOW),
  ],
  [
    'loa-levels',
    (s) => {
      const levels = loaConfigs(s);
      return (
        levels.length === 2 &&
        levels[0]['loa-condition-level'] === '1' &&
        levels[1]['loa-condition-level'] === '2' &&
        levels.every((c) => {
          const age = Number(c['loa-max-age']);
          return Number.isInteger(age) && age > 0 && age <= 36000;
        })
      );
    },
  ],
  [
    'level2-only-for-marker-role',
    (s) => {
      const c = configOf(s, 'conditional-user-role');
      return c.condUserRole === MARKER_ROLE && c.negate !== 'true';
    },
  ],
  [
    'unenrolled-privileged-denied',
    (s) => {
      const check = configOf(s, 'conditional-sub-flow-executed');
      const deny = configOf(s, 'deny-access-authenticator');
      return (
        check.flow_to_check === FLOWS.enrolled &&
        check.check_result === 'not-executed' &&
        deny.denyErrorMessage === 'divalhrMfaEnrollmentRequired'
      );
    },
  ],
  [
    'acr-map-and-client-minimum',
    (s) => {
      const a = s.web.attributes ?? {};
      const map = parseJson(a['acr.loa.map'] ?? '');
      return (
        map !== null &&
        Object.keys(map).length === 2 &&
        map[PWD_ACR] === 1 &&
        map[MFA_ACR] === 2 &&
        a['default.acr.values'] === MFA_ACR &&
        a['minimum.acr.value'] === MFA_ACR
      );
    },
  ],
  [
    'otp-policy',
    (s) =>
      s.realm.otpPolicyType === 'totp' &&
      s.realm.otpPolicyAlgorithm === 'HmacSHA1' &&
      s.realm.otpPolicyDigits === 6 &&
      s.realm.otpPolicyPeriod === 30 &&
      s.realm.otpPolicyLookAheadWindow <= 1 &&
      s.realm.otpPolicyCodeReusable === false,
  ],
  [
    'no-self-enrollment',
    (s) => s.totpAction.enabled === true && s.totpAction.defaultAction === false,
  ],
  [
    'marker-role-composition',
    (s) =>
      s.composites['platform-admin'].includes(MARKER_ROLE) &&
      s.composites['tenant-admin'].includes(MARKER_ROLE) &&
      !s.composites.employee.includes(MARKER_ROLE) &&
      s.composites[MARKER_ROLE].length === 0 &&
      s.markerHolders.length === 0,
  ],
  [
    'web-client-pkce-no-password-or-device-grant',
    (s) => {
      const a = s.web.attributes ?? {};
      return (
        s.web.publicClient === true &&
        s.web.standardFlowEnabled === true &&
        s.web.implicitFlowEnabled === false &&
        s.web.directAccessGrantsEnabled === false &&
        a['pkce.code.challenge.method'] === 'S256' &&
        a['oauth2.device.authorization.grant.enabled'] !== 'true'
      );
    },
  ],
  [
    'brute-force-protection',
    (s) =>
      s.realm.bruteForceProtected === true &&
      s.realm.permanentLockout === false &&
      s.realm.failureFactor === 5 &&
      s.realm.waitIncrementSeconds === 60 &&
      s.realm.maxFailureWaitSeconds === 900,
  ],
  [
    'locales-and-messages',
    (s) =>
      s.realm.internationalizationEnabled === true &&
      ['fr', 'en'].every(
        (locale) =>
          (s.realm.supportedLocales ?? []).includes(locale) &&
          typeof s.messages[locale]?.divalhrMfaEnrollmentRequired === 'string' &&
          s.messages[locale].divalhrMfaEnrollmentRequired.trim() !== '',
      ),
  ],
  ['provisioning-extension-deployed', (s) => s.provisioning.restExtensions.includes(EXTENSION_ID)],
  [
    'provisioning-capability',
    (s) => {
      const p = s.provisioning;
      if (!p.provisioner || !p.capabilityClient || !p.serviceAccount) return false;
      const emitsAudience = (client) =>
        (client.protocolMappers ?? []).some(
          (m) =>
            m.protocolMapper === 'oidc-audience-mapper' &&
            (m.config?.['included.custom.audience'] === CAPABILITY_CLIENT ||
              m.config?.['included.client.audience'] === CAPABILITY_CLIENT),
        );
      const audienceClients = p.allClients.filter(emitsAudience).map((c) => c.clientId);
      const cap = p.capabilityClient;
      return (
        p.effectiveCapability.includes(CAPABILITY_ROLE) &&
        audienceClients.length === 1 &&
        audienceClients[0] === PROVISIONER_CLIENT &&
        p.provisioner.enabled === true &&
        p.provisioner.publicClient === false &&
        p.provisioner.serviceAccountsEnabled === true &&
        p.provisioner.standardFlowEnabled === false &&
        p.provisioner.directAccessGrantsEnabled === false &&
        cap.standardFlowEnabled === false &&
        cap.directAccessGrantsEnabled === false &&
        cap.serviceAccountsEnabled === false &&
        cap.implicitFlowEnabled !== true
      );
    },
  ],
  [
    'provisioner-has-no-broad-admin-rights',
    (s) => {
      const p = s.provisioning;
      // Fail closed: without realm-management the effective admin roles cannot be checked.
      if (!p.serviceAccount || !p.provisioner || !p.realmManagementFound) return false;
      const realmName = s.realm.realm;
      const defaults = new Set([
        `default-roles-${realmName}`,
        'offline_access',
        'uma_authorization',
      ]);
      const namesProvisioner = (policy) => {
        const text = JSON.stringify(policy.config ?? {});
        return (
          text.includes(p.serviceAccount.id) ||
          text.includes(p.serviceAccount.username) ||
          text.includes(p.provisioner.id) ||
          text.includes(PROVISIONER_CLIENT)
        );
      };
      return (
        p.effectiveAdminRoles.length === 0 &&
        p.effectiveRealmRoles.every((role) => defaults.has(role)) &&
        p.serviceAccountGroups.length === 0 &&
        !p.policies.some(namesProvisioner)
      );
    },
  ],
  [
    'login-and-admin-events',
    (s) =>
      s.realm.eventsEnabled === true &&
      s.realm.adminEventsEnabled === true &&
      s.realm.adminEventsDetailsEnabled === false,
  ],
];

/**
 * Evaluates every rule; a rule that throws on unexpected data fails. In pre-cutover mode, the
 * cut-over rules that fail are PENDING (never PASS).
 */
export function evaluate(snapshot, mode = 'final') {
  return RULES.map(([rule, check]) => {
    let pass;
    try {
      pass = check(snapshot) === true;
    } catch {
      pass = false;
    }
    const status = pass
      ? 'PASS'
      : mode === 'pre-cutover' && CUTOVER_RULES.has(rule)
        ? 'PENDING'
        : 'FAIL';
    return { rule, pass, status };
  });
}

/** The mode from --mode=<mode> or KEYCLOAK_VERIFY_MODE; final by default. Unknown modes fail. */
export function modeOf(argv, env) {
  const flag = argv.find((a) => a.startsWith('--mode='));
  const mode = flag ? flag.slice('--mode='.length) : (env.KEYCLOAK_VERIFY_MODE ?? 'final');
  if (mode !== 'final' && mode !== 'pre-cutover') {
    throw new Error('mode');
  }
  return mode;
}

function httpTransport(env) {
  const admin = `${env.KEYCLOAK_URL ?? 'http://localhost:8180'}/admin`;
  const base = `${admin}/realms/${env.KEYCLOAK_REALM ?? 'divalhr-dev'}`;
  let token = env.KEYCLOAK_ADMIN_TOKEN;
  const read = async (url) => {
    if (!token) {
      const response = await fetch(
        `${env.KEYCLOAK_URL ?? 'http://localhost:8180'}/realms/master/protocol/openid-connect/token`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({
            grant_type: 'password',
            client_id: 'admin-cli',
            username: env.KEYCLOAK_ADMIN_USER ?? '',
            password: env.KEYCLOAK_ADMIN_PASSWORD ?? '',
          }),
        },
      );
      if (!response.ok) throw new Error('token');
      token = (await response.json()).access_token;
    }
    const response = await fetch(url, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw new Error('read');
    return response.json();
  };
  return { get: (path) => read(`${base}${path}`), getRoot: (path) => read(`${admin}${path}`) };
}

function composeTransport(env, root) {
  const realm = env.KEYCLOAK_REALM ?? 'divalhr-dev';
  const config = '/tmp/divalhr-verify-realm.kcadm';
  const exec = (args) =>
    execFileSync(
      'docker',
      [
        'compose',
        '-f',
        'infrastructure/docker/compose.yaml',
        '--env-file',
        '.env.example',
        'exec',
        '-T',
        'keycloak',
        '/opt/keycloak/bin/kcadm.sh',
        ...args,
        '--config',
        config,
      ],
      { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
    );
  let signedIn = false;
  const signIn = () => {
    if (!signedIn) {
      exec([
        'config',
        'credentials',
        '--server',
        'http://localhost:8080',
        '--realm',
        'master',
        '--user',
        env.KEYCLOAK_ADMIN_USER ?? '',
        '--password',
        env.KEYCLOAK_ADMIN_PASSWORD ?? '',
      ]);
      signedIn = true;
    }
  };
  const get = async (path) => {
    signIn();
    const [resource = '', query] = path.slice(1).split('?');
    // kcadm.sh sends the path as given, so it stays percent-encoded (flow aliases contain spaces).
    const target = path === '' ? `realms/${realm}` : resource;
    const params = query ? query.split('&').flatMap((pair) => ['-q', pair]) : [];
    return JSON.parse(exec(['get', target, '-r', realm, ...params]));
  };
  const getRoot = async (path) => {
    signIn();
    return JSON.parse(exec(['get', path.slice(1)]));
  };
  return { get, getRoot };
}

async function main() {
  const env = process.env;
  let mode;
  try {
    mode = modeOf(process.argv.slice(2), env);
  } catch {
    console.log('FAIL unknown-mode');
    process.exit(2);
  }
  console.log(
    mode === 'final'
      ? 'MODE final'
      : 'MODE pre-cutover (rollout steps A-B only; NOT release or sign-off evidence)',
  );
  const root = fileURLToPath(new URL('../../../', import.meta.url));
  const { get, getRoot } =
    env.KEYCLOAK_VERIFY_TRANSPORT === 'compose' ? composeTransport(env, root) : httpTransport(env);
  let snapshot;
  try {
    snapshot = await collect(get, getRoot);
  } catch {
    // No detail: an error could echo a URL, a token or a server message.
    console.log('FAIL read-realm-configuration');
    process.exit(2);
  }
  const results = evaluate(snapshot, mode);
  for (const { rule, status } of results) console.log(`${status} ${rule}`);
  const pending = results.some((r) => r.status === 'PENDING');
  if (pending)
    console.log('INCOMPLETE broad admin rights still present: run final mode after cut-over');
  process.exit(results.every((r) => r.status !== 'FAIL') ? 0 : 1);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main();
}
