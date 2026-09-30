package com.divalhr.core.identity.application;

import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.InvitationLocale;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.UUID;

/**
 * Port to the identity provider. The domain speaks only of tenants, tenant roles, addresses and
 * opaque subjects; the adapter alone knows provider concepts (realm roles, groups, attributes,
 * admin APIs). Every operation is idempotent per invitation.
 */
public interface IdentityDirectory {

  /**
   * Creates (or finds, when an earlier attempt already created it) the identity for an accepted
   * invitation, with exactly the invitation's tenant and role. Never modifies an identity this
   * invitation did not create.
   *
   * @param request provisioning request
   * @return the identity's subject, or a conflict when the address already has an identity
   * @throws IdentityProviderUnavailableException when the provider cannot be reached
   */
  ProvisioningResult provision(ProvisioningRequest request);

  /**
   * Asks the provider to email the invitee a link to choose a password and, for a role that
   * requires MFA, to enroll an authenticator app (MVP-011).
   *
   * @param subject identity subject
   * @param role the invitation's role
   * @throws IdentityProviderUnavailableException when the provider cannot be reached or refuses
   */
  void requestCredentialSetup(String subject, TenantRole role);

  /**
   * Deletes the identity this invitation created, if any; never touches another identity.
   *
   * @param invitationId invitation
   * @throws IdentityProviderUnavailableException when the provider cannot be reached
   */
  void compensate(UUID invitationId);

  /**
   * What to provision.
   *
   * @param invitationId invitation (recorded on the identity for idempotency and compensation)
   * @param tenant the invitation's tenant
   * @param email normalized invitee address
   * @param role the invitation's role
   * @param locale the invitee's language
   */
  record ProvisioningRequest(
      UUID invitationId,
      TenantId tenant,
      EmailAddress email,
      TenantRole role,
      InvitationLocale locale) {}

  /** Outcome of {@link #provision}. */
  sealed interface ProvisioningResult {}

  /**
   * The identity exists with the invitation's tenant and role.
   *
   * @param subject opaque subject (the access token's {@code sub})
   */
  record Provisioned(String subject) implements ProvisioningResult {}

  /** The address already has an identity this invitation did not create. No detail is carried. */
  record IdentityConflict() implements ProvisioningResult {}
}
