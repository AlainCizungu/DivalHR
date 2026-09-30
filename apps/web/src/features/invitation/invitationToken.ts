/** The invitation token format: 32 random bytes, base64url without padding (43 characters). */
const TOKEN_SHAPE = /^[A-Za-z0-9_-]{43}$/u;

/** Reads the invitation token from the URL fragment (#token=...); null unless well formed. */
export function readInvitationToken(location: Location): string | null {
  if (location.hash === '') return null;
  const token = new URLSearchParams(location.hash.slice(1)).get('token');
  return token !== null && TOKEN_SHAPE.test(token) ? token : null;
}

/**
 * Removes the fragment from the address bar and the current history entry, so the secret is not
 * left in history, bookmarks or screenshots of the address bar. The fragment never reaches a
 * server; the page keeps the token in memory only.
 */
export function stripFragment(location: Location, history: History): void {
  if (location.hash === '') return;
  history.replaceState(history.state, '', `${location.pathname}${location.search}`);
}
