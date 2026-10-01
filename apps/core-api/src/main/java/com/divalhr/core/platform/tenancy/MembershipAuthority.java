package com.divalhr.core.platform.tenancy;

import java.util.Objects;
import java.util.Optional;

/**
 * The Core-side authority for tenant access (MVP-012A, architect decision on #37, D1 and M1):
 * effective tenant access is the intersection of the verified access token and the caller's active
 * tenant membership. Implemented by the identity module, so that platform code never reads identity
 * tables.
 *
 * <p>There is deliberately no cache across requests (M4): every call reads committed state, so a
 * membership change takes effect at the caller's next request.
 */
public interface MembershipAuthority {

  /**
   * The caller's active membership.
   *
   * @param subject verified, non-blank token subject
   * @return the membership, or empty when the subject has none
   * @throws RuntimeException when the lookup fails; callers fail closed (M5)
   */
  Optional<ActiveMembership> find(String subject);

  /**
   * One active membership. Never logged: it identifies a person's access.
   *
   * @param tenant the membership's tenant
   * @param role the membership's single tenant role ({@code tenant-admin} or {@code employee})
   */
  record ActiveMembership(TenantId tenant, String role) {

    /** Validates the components. */
    public ActiveMembership {
      Objects.requireNonNull(tenant, "tenant");
      Objects.requireNonNull(role, "role");
    }

    /**
     * Whether this membership grants exactly the given role in the given tenant (M2: exact
     * equality, no role hierarchy).
     *
     * @param verifiedTenant the token's verified tenant
     * @param requiredRole the operation's required tenant role
     * @return true on an exact match
     */
    public boolean grants(TenantId verifiedTenant, String requiredRole) {
      return tenant.equals(verifiedTenant) && role.equals(requiredRole);
    }
  }
}
