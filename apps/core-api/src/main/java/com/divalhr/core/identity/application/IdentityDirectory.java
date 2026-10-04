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
   * requires MFA, to enroll an authenticator app (MVP-011), for the identity this invitation
   * created (Issue #31: keyed by invitation, never by subject). The provider derives the role, and
   * so the required actions, from the identity's own role group; the caller never names it.
   *
   * @param invitationId invitation whose identity is set up
   * @return what the provider found
   * @throws IdentityProviderUnavailableException when the provider cannot be reached or refuses
   */
  CredentialSetupOutcome requestCredentialSetup(UUID invitationId);

  /**
   * Deletes the identity this invitation created while it is pristine; never touches another
   * identity, and never one whose setup has started.
   *
   * @param invitationId invitation
   * @return whether the provider deleted (or found nothing) or refused
   * @throws IdentityProviderUnavailableException when the provider cannot be reached
   */
  CompensationOutcome compensate(UUID invitationId);

  /**
   * Disables a separated employee's identity in the provider and ends its sessions (MVP-022,
   * D22-13). The provider acts only on an identity it created, of exactly this tenant and with the
   * employee role group; anything else is refused unchanged. Idempotent: an identity that is
   * already disabled and has no session is reported revoked again.
   *
   * @param tenant the revocation's tenant
   * @param subject the subject read from the membership just before the call (A22-5)
   * @param revocationId the revocation (audit correlation in the provider)
   * @return what the provider did
   * @throws IdentityProviderUnavailableException when the provider cannot be reached or answers
   *     anything else
   */
  RevocationOutcome revokeAccess(TenantId tenant, String subject, UUID revocationId);

  /** Outcome of {@link #revokeAccess}. */
  enum RevocationOutcome {
    /** The identity is disabled and has no session. */
    REVOKED,
    /** No identity has this subject: nothing to revoke. */
    ABSENT,
    /** Not an employee identity of this tenant created by the provider extension: unchanged. */
    REFUSED
  }

  /** Outcome of {@link #requestCredentialSetup}. */
  enum CredentialSetupOutcome {
    /** Setup is pending: the action email was sent. */
    EMAIL_SENT,
    /** The role's proven terminal state: nothing left to set up (A1). */
    COMPLETED,
    /** Partial, contradictory or drifted state: nothing was sent; needs a realm administrator. */
    STATE_INVALID
  }

  /** Outcome of {@link #compensate}. */
  enum CompensationOutcome {
    /** The identity was deleted, or none existed. */
    DELETED_OR_ABSENT,
    /** The identity is not pristine and was left in place for a realm administrator. */
    REFUSED
  }

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
