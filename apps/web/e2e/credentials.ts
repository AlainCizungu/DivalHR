import { createHmac } from 'node:crypto';

// DEVELOPMENT-ONLY seed users and fixture tenants (infrastructure/docker/keycloak/README.md).
// The fixture organizations exist only because the stack runs with DIVALHR_ENVIRONMENT=development.
export const TENANT_A = '00000000-0000-4000-8000-00000000000a';
export const USERS = {
  adminA: ['dev-admin-a', 'dev-only-Admin-A-2026'],
  adminB: ['dev-admin-b', 'dev-only-Admin-B-2026'],
  employeeA: ['dev-employee-a', 'dev-only-Employee-A-2026'],
  platformAdmin: ['dev-platform-admin', 'dev-only-Platform-2026'],
} as const;
/**
 * DEVELOPMENT-ONLY published TOTP seeds of the privileged seed users (MVP-011, realm import).
 * Codes are computed in memory and typed through the DOM, never through a logged step argument.
 */
export const TOTP_SEEDS: Readonly<Partial<Record<string, string>>> = {
  'dev-admin-a': 'dev-only-totp-admin-a-2026',
  'dev-admin-b': 'dev-only-totp-admin-b-2026',
  'dev-platform-admin': 'dev-only-totp-platform-2026',
};

/** RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30 s) over the raw secret, as Keycloak configures it. */
export function totp(secret: string, counter: number): string {
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(counter));
  const hash = createHmac('sha1', Buffer.from(secret, 'utf8')).update(message).digest();
  const offset = (hash[hash.length - 1] ?? 0) & 0x0f;
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}
