#!/usr/bin/env node
// MVP-011 (A4): read-only verification of a DivalHR realm's privileged-MFA configuration.
//
// It reads the realm through the Keycloak admin API (GET only) and prints one line per rule:
// "PASS <rule>" or "FAIL <rule>". It never prints configuration values, credentials, TOTP secrets,
// tokens, subjects or personal data. Exit code: 0 all rules pass, 1 a rule fails, 2 the realm
// could not be read.
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
export async function collect(get) {
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
  return {
    realm,
    executions,
    configs,
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
  [
    'login-and-admin-events',
    (s) =>
      s.realm.eventsEnabled === true &&
      s.realm.adminEventsEnabled === true &&
      s.realm.adminEventsDetailsEnabled === false,
  ],
];

/** Evaluates every rule; a rule that throws on unexpected data fails. */
export function evaluate(snapshot) {
  return RULES.map(([rule, check]) => {
    let pass;
    try {
      pass = check(snapshot) === true;
    } catch {
      pass = false;
    }
    return { rule, pass };
  });
}

function httpTransport(env) {
  const base = `${env.KEYCLOAK_URL ?? 'http://localhost:8180'}/admin/realms/${env.KEYCLOAK_REALM ?? 'divalhr-dev'}`;
  let token = env.KEYCLOAK_ADMIN_TOKEN;
  return async (path) => {
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
    const response = await fetch(`${base}${path}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw new Error('read');
    return response.json();
  };
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
  return async (path) => {
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
    const [resource = '', query] = path.slice(1).split('?');
    // kcadm.sh sends the path as given, so it stays percent-encoded (flow aliases contain spaces).
    const target = path === '' ? `realms/${realm}` : resource;
    const params = query ? query.split('&').flatMap((pair) => ['-q', pair]) : [];
    return JSON.parse(exec(['get', target, '-r', realm, ...params]));
  };
}

async function main() {
  const env = process.env;
  const root = fileURLToPath(new URL('../../../', import.meta.url));
  const get =
    env.KEYCLOAK_VERIFY_TRANSPORT === 'compose' ? composeTransport(env, root) : httpTransport(env);
  let snapshot;
  try {
    snapshot = await collect(get);
  } catch {
    // No detail: an error could echo a URL, a token or a server message.
    console.log('FAIL read-realm-configuration');
    process.exit(2);
  }
  const results = evaluate(snapshot);
  for (const { rule, pass } of results) console.log(`${pass ? 'PASS' : 'FAIL'} ${rule}`);
  process.exit(results.every((r) => r.pass) ? 0 : 1);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main();
}
