package com.divalhr.keycloak.provisioning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which identities the access-revocation endpoint may disable (MVP-022, A22-5).
 *
 * <p>Only an employee identity that this extension created for the path tenant: exactly one
 * canonical invitation attribute, the same tenant, not a service account, no platform-admin role,
 * one tenant group and the single role {@code employee}. Anything else is refused with {@code
 * REVOCATION_REFUSED} and left untouched, so the Core API moves the revocation to manual
 * intervention.
 */
final class RevocationPolicy {

  private RevocationPolicy() {}

  /**
   * What the decision reads from the identity.
   *
   * @param invitations values of the invitation attribute
   * @param tenant the identity's tenant, or {@code null}
   * @param serviceAccount whether it is a service account
   * @param platformAdmin whether it holds the platform-admin realm role
   * @param groups number of groups
   * @param role its single invitation role, if exactly one
   */
  record Identity(
      List<String> invitations,
      UUID tenant,
      boolean serviceAccount,
      boolean platformAdmin,
      long groups,
      Optional<InvitationRole> role) {

    /** Copies the invitation values. */
    Identity {
      invitations = List.copyOf(invitations);
    }
  }

  /**
   * Whether the identity may be disabled for the path tenant.
   *
   * @param identity identity facts
   * @param pathTenant tenant of the request path
   * @return true when it may be disabled
   */
  static boolean revocable(Identity identity, UUID pathTenant) {
    if (identity.invitations().size() != 1) {
      return false;
    }
    try {
      RequestBodies.canonicalId(identity.invitations().get(0));
    } catch (RequestBodies.Rejected notCreatedHere) {
      return false;
    }
    return pathTenant.equals(identity.tenant())
        && !identity.serviceAccount()
        && !identity.platformAdmin()
        && identity.groups() == 1
        && identity.role().equals(Optional.of(InvitationRole.EMPLOYEE));
  }
}
