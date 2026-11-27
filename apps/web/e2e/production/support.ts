/** LAUNCH-002: origins of the production acceptance journeys, from the environment only. */
export const PUBLIC_URL = (process.env.PROD_PUBLIC_URL ?? '').replace(/\/$/, '');
export const APP_URL = (process.env.PROD_APP_URL ?? '').replace(/\/$/, '');
export const WWW_URL = (process.env.PROD_WWW_URL ?? '').replace(/\/$/, '');
export const REALM = 'divalhr-production';
export const SIGN_IN_PATH = `/identity/realms/${REALM}/protocol/openid-connect/auth`;

export function requireOrigins(): void {
  if (!PUBLIC_URL.startsWith('https://') || !APP_URL.startsWith('https://')) {
    throw new Error('PROD_PUBLIC_URL and PROD_APP_URL (https origins) are required');
  }
  if (/hr-dev|dival\.ai/i.test(`${PUBLIC_URL}${APP_URL}`)) {
    throw new Error('the production acceptance never runs against the test environment');
  }
}
