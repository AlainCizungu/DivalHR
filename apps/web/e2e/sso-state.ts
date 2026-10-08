import {
  chmodSync,
  existsSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * DEVX-001A (A75-3): the temporary Keycloak SSO state shared by the `features` browser tests.
 *
 * It is a session credential. It lives only in a per-run directory created with mkdtemp outside
 * the repository and every report or artifact path (directory 0700, files 0600), is validated
 * before it is written (only the identity provider's session cookies, no application origin, no
 * browser storage, nothing token-shaped outside the identity cookie) and is removed by the global
 * teardown and by the shell trap of the suite runner. It is never printed, uploaded or copied.
 * Application tokens are never stored: every feature test still signs in through Authorization
 * Code + PKCE, and the app keeps its tokens in memory.
 */

/** Environment variable carrying the run's state directory to the Playwright workers. */
export const SSO_DIR_ENV = 'DIVALHR_E2E_SSO_DIR';

/** Keycloak's SSO session cookies: the only names allowed in a saved state. */
export const SSO_COOKIE_NAMES: readonly string[] = [
  'KEYCLOAK_IDENTITY',
  'KEYCLOAK_IDENTITY_LEGACY',
  'KEYCLOAK_SESSION',
  'KEYCLOAK_SESSION_LEGACY',
];

/** The cookie that is itself a signed JWT (the only token-shaped value allowed). */
const IDENTITY_COOKIES = new Set(['KEYCLOAK_IDENTITY', 'KEYCLOAK_IDENTITY_LEGACY']);

const REPO_ROOT = fileURLToPath(new URL('../../../', import.meta.url));
const JWT = /eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\./u;

export interface SsoCookie {
  name: string;
  value: string;
  domain: string;
  path: string;
  expires: number;
  httpOnly: boolean;
  secure: boolean;
  sameSite: 'Strict' | 'Lax' | 'None';
}

export interface SavedState {
  cookies: SsoCookie[];
  origins: unknown[];
}

function insideRepository(path: string): boolean {
  const rel = relative(resolve(REPO_ROOT), resolve(path));
  return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel));
}

/** Creates the run's private state directory (outside the repository, mode 0700). */
export function createSsoDir(base: string = tmpdir()): string {
  const dir = mkdtempSync(join(base, 'divalhr-e2e-sso-'));
  chmodSync(dir, 0o700);
  if (insideRepository(dir)) {
    rmSync(dir, { recursive: true, force: true });
    throw new Error('the SSO state directory must be outside the repository');
  }
  return dir;
}

/** The run's state directory, checked: absolute, outside the repository, owner-only. */
export function ssoDir(): string {
  const dir = process.env[SSO_DIR_ENV];
  if (!dir || !isAbsolute(dir)) throw new Error(`${SSO_DIR_ENV} is not set to an absolute path`);
  if (insideRepository(dir))
    throw new Error('the SSO state directory must be outside the repository');
  const mode = statSync(dir).mode & 0o777;
  if (mode !== 0o700)
    throw new Error(`the SSO state directory must be mode 0700 (is ${mode.toString(8)})`);
  return dir;
}

/** Removes a state directory and everything in it; absent is fine. */
export function removeSsoDir(dir: string | undefined): void {
  if (dir && isAbsolute(dir) && !insideRepository(dir))
    rmSync(dir, { recursive: true, force: true });
}

function fileFor(dir: string, username: string): string {
  if (!/^[a-z0-9-]+$/u.test(username)) throw new Error('unexpected user name');
  return join(dir, `${username}.json`);
}

/**
 * Keeps only the identity provider's SSO cookies and refuses anything else: another host, any
 * browser storage, an unexpected cookie, or a token-shaped value outside the identity cookie.
 */
export function sanitizeState(state: SavedState, identityHost: string): SavedState {
  // Browser storage of any origin (the app's or the identity provider's) is never kept.
  const cookies = state.cookies.filter(
    (cookie) =>
      cookie.domain.replace(/^\./u, '') === identityHost && SSO_COOKIE_NAMES.includes(cookie.name),
  );
  validateState({ cookies, origins: [] }, identityHost);
  return { cookies, origins: [] };
}

/** Throws unless the state holds only allowed SSO cookies of the identity host. */
export function validateState(state: SavedState, identityHost: string): void {
  if (!Array.isArray(state.cookies) || !Array.isArray(state.origins))
    throw new Error('malformed state');
  if (state.origins.length !== 0) throw new Error('browser storage must not be saved');
  if (!state.cookies.some((cookie) => IDENTITY_COOKIES.has(cookie.name))) {
    throw new Error('no SSO identity cookie: the sign-in did not establish a session');
  }
  for (const cookie of state.cookies) {
    if (cookie.domain.replace(/^\./u, '') !== identityHost)
      throw new Error('a cookie of another host');
    if (!SSO_COOKIE_NAMES.includes(cookie.name)) throw new Error('an unexpected cookie');
    if (!IDENTITY_COOKIES.has(cookie.name) && JWT.test(cookie.value)) {
      throw new Error('a token-shaped value outside the identity cookie');
    }
  }
}

/** Writes a role's validated state once (0600); an existing file is an error. */
export function writeState(
  dir: string,
  username: string,
  state: SavedState,
  identityHost: string,
): void {
  validateState(state, identityHost);
  writeFileSync(fileFor(dir, username), JSON.stringify(state), { mode: 0o600, flag: 'wx' });
}

/** A role's SSO cookies, or undefined when the run saved none for that user. */
export function readState(
  dir: string,
  username: string,
  identityHost: string,
): SsoCookie[] | undefined {
  const file = fileFor(dir, username);
  if (!existsSync(file)) return undefined;
  if ((statSync(file).mode & 0o777) !== 0o600)
    throw new Error('the SSO state file must be mode 0600');
  const state = JSON.parse(readFileSync(file, 'utf8')) as SavedState;
  validateState(state, identityHost);
  return state.cookies;
}
