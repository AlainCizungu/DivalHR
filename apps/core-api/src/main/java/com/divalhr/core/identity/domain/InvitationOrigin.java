package com.divalhr.core.identity.domain;

/**
 * Who created an invitation (MVP-014). Immutable once stored.
 *
 * <ul>
 *   <li>{@link #TENANT_ADMIN}: a tenant administrator of the organization (MVP-010).
 *   <li>{@link #PLATFORM_BOOTSTRAP}: a platform administrator inviting the organization's first
 *       tenant administrator; only ever role {@code tenant-admin}, and accepted only while the
 *       organization still has no tenant administrator (architect decision on #38, A2).
 * </ul>
 */
public enum InvitationOrigin {
  /** Created by a tenant administrator. */
  TENANT_ADMIN,
  /** Created by a platform administrator for an organization without a tenant administrator. */
  PLATFORM_BOOTSTRAP
}
