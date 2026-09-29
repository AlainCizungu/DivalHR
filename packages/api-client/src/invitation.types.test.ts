import { describe, expectTypeOf, it } from 'vitest';
import type { CreateInvitation, InvitationReceipt, InvitationRole } from './index';

describe('invitation contract types', () => {
  it('assigns only tenant roles', () => {
    expectTypeOf<InvitationRole>().toEqualTypeOf<'tenant-admin' | 'employee'>();
    const ok: CreateInvitation = { email: 'a@example.test', role: 'employee', locale: 'fr' };
    const platformAdmin = 'platform-admin' as const;
    // @ts-expect-error platform-admin is never assignable through a tenant endpoint
    const platform: CreateInvitation['role'] = platformAdmin;
    const tenantId = '00000000-0000-4000-8000-00000000000a';
    // @ts-expect-error the tenant never comes from the caller
    const tenant: CreateInvitation = { ...ok, tenantId };
    void [ok, platform, tenant];
  });

  it('keeps the email address out of receipts', () => {
    expectTypeOf<InvitationReceipt>().not.toHaveProperty('email');
  });
});
