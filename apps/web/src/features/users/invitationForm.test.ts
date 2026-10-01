import type { Invitation } from '@divalhr/api-client';
import { describe, expect, it } from 'vitest';
import {
  compareNewestFirst,
  deliveryKey,
  INVITATION_ROLES,
  instantKey,
  invitationErrorsFromProblem,
  mergeInvitations,
  rowAfterResend,
  rowFromReceipt,
  toInvitationPayload,
  validateInvitation,
} from './invitationForm';

const row = (overrides: Partial<Invitation> = {}): Invitation => ({
  id: '11111111-1111-4111-8111-111111111111',
  email: 'amani@example.cd',
  role: 'employee',
  locale: 'fr',
  status: 'PENDING',
  deliveryState: 'QUEUED',
  expiresAt: '2026-10-07T10:00:00Z',
  createdAt: '2026-09-30T10:00:00Z',
  acceptedAt: null,
  revokedAt: null,
  resendsRemaining: 3,
  origin: 'TENANT_ADMIN',
  ...overrides,
});

describe('invitation form', () => {
  it('offers only tenant roles, never platform-admin', () => {
    expect(INVITATION_ROLES).toEqual(['employee', 'tenant-admin']);
    expect(INVITATION_ROLES).not.toContain('platform-admin');
  });

  it('validates the email address and role', () => {
    expect(validateInvitation({ email: ' ', role: '', locale: 'fr' })).toEqual({
      email: 'REQUIRED',
      role: 'REQUIRED',
    });
    expect(validateInvitation({ email: 'amani', role: 'employee', locale: 'fr' })).toEqual({
      email: 'FORMAT',
    });
    expect(
      validateInvitation({ email: `${'a'.repeat(250)}@b.cd`, role: 'employee', locale: 'fr' }),
    ).toEqual({ email: 'LENGTH' });
    expect(
      validateInvitation({ email: 'elodie@société.cd', role: 'tenant-admin', locale: 'en' }),
    ).toEqual({});
  });

  it('trims the email in the payload and sends no tenant', () => {
    expect(
      toInvitationPayload({ email: '  Amani@Example.CD ', role: 'employee', locale: 'fr' }),
    ).toStrictEqual({ email: 'Amani@Example.CD', role: 'employee', locale: 'fr' });
  });

  it('maps conflicts and validation problems to fields', () => {
    expect(invitationErrorsFromProblem({ code: 'INVITATION_ALREADY_PENDING' })).toEqual({
      email: 'ALREADY_PENDING',
    });
    expect(invitationErrorsFromProblem({ code: 'INVITATION_RECIPIENT_ALREADY_MEMBER' })).toEqual({
      email: 'ALREADY_MEMBER',
    });
    expect(
      invitationErrorsFromProblem({
        code: 'VALIDATION_FAILED',
        params: {
          fields: [
            { field: 'role', constraint: 'FORMAT' },
            { field: 'tenantId', constraint: 'UNKNOWN_PROPERTY' },
          ],
        },
      }),
    ).toEqual({ role: 'FORMAT' });
    expect(invitationErrorsFromProblem({ code: 'INVITATION_RATE_LIMITED' })).toBeNull();
  });
});

describe('invitation list order', () => {
  it('keeps microsecond precision with variable fraction digits', () => {
    expect(instantKey('2026-09-30T10:00:00Z')).toBe('2026-09-30T10:00:00.000000000');
    expect(instantKey('2026-09-30T10:00:00.1Z')).toBe('2026-09-30T10:00:00.100000000');
    expect(instantKey('2026-09-30T10:00:00.000123Z')).toBe('2026-09-30T10:00:00.000123000');
    expect(instantKey('2026-09-30T11:00:00+01:00')).toBe('2026-09-30T10:00:00.000000000');
  });

  it('orders newest first by createdAt then id, both descending, one row per id', () => {
    const a = row({
      id: 'aaaaaaaa-0000-4000-8000-000000000000',
      createdAt: '2026-09-30T10:00:00.000100Z',
    });
    const b = row({
      id: 'bbbbbbbb-0000-4000-8000-000000000000',
      createdAt: '2026-09-30T10:00:00.0001Z',
    });
    const c = row({
      id: 'cccccccc-0000-4000-8000-000000000000',
      createdAt: '2026-09-30T10:00:00.00015Z',
    });
    const d = row({
      id: 'dddddddd-0000-4000-8000-000000000000',
      createdAt: '2026-09-29T10:00:00Z',
    });
    expect([d, a, c, b].sort(compareNewestFirst).map((r) => r.id[0])).toEqual(['c', 'b', 'a', 'd']);
    const merged = mergeInvitations([a, d], [row({ ...a, status: 'REVOKED' }), c]);
    expect(merged.map((r) => r.id[0])).toEqual(['c', 'a', 'd']);
    expect(merged[1]?.status).toBe('REVOKED');
  });
});

describe('delivery wording', () => {
  it('says pending for QUEUED, sent only for SENT and warns for FAILED', () => {
    expect(deliveryKey(row({ deliveryState: 'QUEUED' }))).toBe('users.delivery.QUEUED');
    expect(deliveryKey(row({ deliveryState: 'SENT' }))).toBe('users.delivery.SENT');
    expect(deliveryKey(row({ deliveryState: 'FAILED' }))).toBe('users.delivery.FAILED');
    expect(deliveryKey(row({ deliveryState: 'FAILED', resendsRemaining: 0 }))).toBe(
      'users.delivery.FAILED_NO_RESEND',
    );
  });

  it('shows no delivery state once the invitation is no longer pending', () => {
    for (const status of ['ACCEPTED', 'EXPIRED', 'REVOKED'] as const) {
      expect(deliveryKey(row({ status, deliveryState: 'SENT' }))).toBeNull();
    }
  });

  it('builds rows from receipts without trusting the receipt for the email', () => {
    const receipt = {
      id: row().id,
      role: 'tenant-admin' as const,
      locale: 'en' as const,
      status: 'PENDING' as const,
      deliveryState: 'QUEUED' as const,
      expiresAt: '2026-10-07T10:00:00Z',
      createdAt: '2026-09-30T10:00:00Z',
    };
    const created = rowFromReceipt(receipt, 'amani@example.cd');
    expect(created).toMatchObject({ email: 'amani@example.cd', resendsRemaining: 3 });
    const resent = rowAfterResend(row({ deliveryState: 'FAILED', resendsRemaining: 1 }), {
      ...receipt,
      role: 'employee',
      expiresAt: '2026-10-08T10:00:00Z',
    });
    expect(resent).toMatchObject({
      deliveryState: 'QUEUED',
      expiresAt: '2026-10-08T10:00:00Z',
      resendsRemaining: 0,
      email: 'amani@example.cd',
    });
  });
});
