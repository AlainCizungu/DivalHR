import type {
  CreateInvitation,
  Invitation,
  InvitationReceipt,
  InvitationRole,
  InvitationStatus,
} from '@divalhr/api-client';
import type { SupportedLocale } from '@divalhr/localization';

export const INVITATION_FIELDS = ['email', 'role', 'locale'] as const;
export type InvitationField = (typeof INVITATION_FIELDS)[number];

/** Server constraint names (Problem params) plus the two conflict codes mapped onto the email. */
export type InvitationConstraint =
  'REQUIRED' | 'LENGTH' | 'FORMAT' | 'ALREADY_PENDING' | 'ALREADY_MEMBER';
export type InvitationErrors = Partial<Record<InvitationField, InvitationConstraint>>;

/** Only tenant roles; platform-admin is never offered (and the API rejects it). */
export const INVITATION_ROLES: readonly InvitationRole[] = ['employee', 'tenant-admin'];

export const STATUS_FILTERS = ['ALL', 'PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED'] as const;
export type StatusFilter = (typeof STATUS_FILTERS)[number];

export interface InvitationValues {
  email: string;
  role: InvitationRole | '';
  locale: SupportedLocale;
}

export const EMAIL_MAX_LENGTH = 254;
// A usability check only: the Core API normalizes and validates the address authoritatively.
const EMAIL_SHAPE = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/u;

export function validateInvitation(values: InvitationValues): InvitationErrors {
  const errors: InvitationErrors = {};
  const email = values.email.trim();
  if (email === '') errors.email = 'REQUIRED';
  else if (email.length > EMAIL_MAX_LENGTH) errors.email = 'LENGTH';
  else if (!EMAIL_SHAPE.test(email)) errors.email = 'FORMAT';
  if (values.role === '') errors.role = 'REQUIRED';
  return errors;
}

export function toInvitationPayload(values: InvitationValues): CreateInvitation {
  return { email: values.email.trim(), role: values.role as InvitationRole, locale: values.locale };
}

interface ProblemLike {
  code?: string;
  params?: Record<string, unknown>;
}

/** Maps a server Problem to field errors; null when it is not field-specific. */
export function invitationErrorsFromProblem(problem: ProblemLike): InvitationErrors | null {
  if (problem.code === 'INVITATION_ALREADY_PENDING') return { email: 'ALREADY_PENDING' };
  if (problem.code === 'INVITATION_RECIPIENT_ALREADY_MEMBER') return { email: 'ALREADY_MEMBER' };
  if (problem.code !== 'VALIDATION_FAILED') return null;
  const entries = Array.isArray(problem.params?.fields) ? problem.params.fields : [];
  const errors: InvitationErrors = {};
  for (const entry of entries as { field?: string; constraint?: string }[]) {
    const field = entry.field as InvitationField;
    const constraint = entry.constraint as InvitationConstraint;
    if ((INVITATION_FIELDS as readonly string[]).includes(field)) {
      errors[field] = ['REQUIRED', 'LENGTH', 'FORMAT'].includes(constraint) ? constraint : 'FORMAT';
    }
  }
  return Object.keys(errors).length > 0 ? errors : null;
}

const UTC_INSTANT = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/u;

/**
 * A fixed-width sort key for a UTC instant, keeping the server's sub-millisecond precision (the
 * API orders by microseconds and serializes instants with a variable number of fraction digits).
 */
export function instantKey(iso: string): string {
  const match = UTC_INSTANT.exec(iso);
  if (!match?.[1]) return new Date(iso).toISOString().replace('Z', '000000');
  return `${match[1]}.${(match[2] ?? '').padEnd(9, '0')}`;
}

/** The list order of GET /invitations: newest first, createdAt then id, both descending. */
export function compareNewestFirst(a: Invitation, b: Invitation): number {
  const at = instantKey(a.createdAt);
  const bt = instantKey(b.createdAt);
  if (at !== bt) return at < bt ? 1 : -1;
  if (a.id !== b.id) return a.id < b.id ? 1 : -1;
  return 0;
}

/** Merges rows newest first with one row per id (the incoming one wins). */
export function mergeInvitations(
  current: readonly Invitation[],
  incoming: readonly Invitation[],
): Invitation[] {
  const byId = new Map<string, Invitation>();
  for (const row of current) byId.set(row.id, row);
  for (const row of incoming) byId.set(row.id, row);
  return [...byId.values()].sort(compareNewestFirst);
}

/**
 * A created invitation as a list row. The receipt deliberately omits the email address (so the
 * idempotency record holds no personal data); the administrator typed it, so the page restores it
 * locally without asking the server again.
 */
export function rowFromReceipt(receipt: InvitationReceipt, email: string): Invitation {
  return {
    id: receipt.id,
    email,
    role: receipt.role,
    locale: receipt.locale,
    status: receipt.status,
    deliveryState: receipt.deliveryState,
    expiresAt: receipt.expiresAt,
    createdAt: receipt.createdAt,
    acceptedAt: null,
    revokedAt: null,
    resendsRemaining: 3,
  };
}

/** A reissued invitation: new link, expiry and delivery state; one fewer resend. */
export function rowAfterResend(row: Invitation, receipt: InvitationReceipt): Invitation {
  return {
    ...row,
    status: receipt.status,
    deliveryState: receipt.deliveryState,
    expiresAt: receipt.expiresAt,
    resendsRemaining: Math.max(0, row.resendsRemaining - 1),
  };
}

/** Whether a row belongs in the list shown for the filter. */
export function matchesFilter(status: InvitationStatus, filter: StatusFilter): boolean {
  return filter === 'ALL' || filter === status;
}

/**
 * The delivery message key for a pending invitation. QUEUED is "created, delivery pending" and
 * only SENT says "sent"; FAILED is an actionable warning (resend, or revoke and re-invite once no
 * resend remains). Delivery is not shown for accepted, expired or revoked invitations.
 */
export function deliveryKey(row: Invitation): string | null {
  if (row.status !== 'PENDING') return null;
  if (row.deliveryState === 'FAILED') {
    return row.resendsRemaining > 0 ? 'users.delivery.FAILED' : 'users.delivery.FAILED_NO_RESEND';
  }
  return `users.delivery.${row.deliveryState}`;
}
