package com.divalhr.core.identity.application;

import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.OrganizationDirectory.OrganizationSummary;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one serialization boundary for tenant administrators (MVP-014, architect decision on #38,
 * A1). Every path that creates a tenant-admin invitation or a tenant-admin membership takes it
 * first, then re-checks its predicates under it:
 *
 * <ul>
 *   <li>bootstrap creation and bootstrap resend ({@link TenantAdminBootstrapService});
 *   <li>tenant-origin invitation creation when the role is {@code tenant-admin} ({@link
 *       CreateInvitationService});
 *   <li>acceptance of a tenant-admin invitation, before provisioning and again before the
 *       membership is inserted ({@link InvitationAcceptance}).
 * </ul>
 *
 * <p>Lock order (no path ever takes an earlier lock after a later one): 0. the platform actor's
 * bootstrap lock (bootstrap creation only); 1. this organization lock; 2. the per-tenant invitation
 * lock ({@code JdbcInvitationRepository#lockTenant}); 3. invitation row locks; 4. membership
 * writes. Paths that do not involve a tenant administrator (employee invitations, revocations,
 * expiry) take only steps 2 to 4 and so never wait for this lock while holding a later one.
 */
@Component
public class TenantAdministrationLock {

  private final OrganizationDirectory organizations;
  private final TenantAdministrationProperties properties;

  /**
   * Creates the lock.
   *
   * @param organizations organization port (tenant module)
   * @param properties lock timeout
   */
  public TenantAdministrationLock(
      OrganizationDirectory organizations, TenantAdministrationProperties properties) {
    this.organizations = organizations;
    this.properties = properties;
  }

  /**
   * Takes the organization's lock for the rest of the current transaction (step 1).
   *
   * @param tenant target organization
   * @return the organization when it exists and is active; empty otherwise (nothing locked)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<OrganizationSummary> acquire(TenantId tenant) {
    return organizations.lockForTenantAdministration(tenant, properties.lockTimeout());
  }
}
