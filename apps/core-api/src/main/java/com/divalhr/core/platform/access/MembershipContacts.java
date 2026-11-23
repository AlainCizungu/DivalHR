package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;

/**
 * The verified contact of a tenant membership, for the partner referral preview (MVP-120F, ADR
 * 0045). Implemented by {@code identity}, which owns the membership; callers never read its tables.
 * The address was copied from the membership's own accepted invitation, so the member proved
 * control of it. Read-only; the value is Confidential personal data and is never logged.
 */
public interface MembershipContacts {

  /**
   * The membership's verified, normalized email address.
   *
   * @param tenant verified tenant
   * @param membershipId the caller's membership
   * @return the address, or empty when the membership has none (historical or seeded rows)
   */
  Optional<String> verifiedEmail(TenantId tenant, UUID membershipId);
}
