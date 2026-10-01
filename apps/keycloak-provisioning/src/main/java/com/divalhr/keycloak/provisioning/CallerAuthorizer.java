package com.divalhr.keycloak.provisioning;

import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;

/**
 * Authenticates and authorizes the caller before any request input is consumed (A2). Uses only the
 * request's {@code Authorization} header and live realm models; never token role claims alone.
 */
public final class CallerAuthorizer {

  /** The authorized caller. */
  public record Caller(ClientModel client, UserModel serviceAccount) {}

  /** Authentication or authorization failure: 401 or 403, no detail. */
  public static final class Denied extends Exception {
    private static final long serialVersionUID = 1L;

    /** HTTP status: 401 or 403. */
    private final int status;

    /** Internal reason for the sanitized log; never returned to the caller. */
    private final String reason;

    Denied(int status, String reason) {
      super(reason, null, false, false);
      this.status = status;
      this.reason = reason;
    }

    /**
     * HTTP status.
     *
     * @return 401 or 403
     */
    public int status() {
      return status;
    }

    /**
     * Stable internal reason.
     *
     * @return reason
     */
    public String reason() {
      return reason;
    }
  }

  private CallerAuthorizer() {}

  /**
   * Authorizes the current request.
   *
   * @param session Keycloak session of the request
   * @param config extension configuration
   * @return the caller
   * @throws Denied when the caller is not the provisioner's live service account with the
   *     capability, or the realm is not enabled
   */
  public static Caller authorize(KeycloakSession session, ProvisioningConfig config) throws Denied {
    RealmModel realm = session.getContext().getRealm();
    AuthenticationManager.AuthResult auth;
    try {
      // Signature (realm keys), token type, expiry, issuer (realm URL) and audience.
      auth =
          new AppAuthManager.BearerTokenAuthenticator(session)
              .setAudience(ProvisioningConfig.AUDIENCE)
              .authenticate();
    } catch (RuntimeException rejected) {
      auth = null;
    }
    if (auth == null || auth.token() == null || auth.user() == null) {
      throw new Denied(401, "token");
    }
    if (realm == null || !config.realms().contains(realm.getName())) {
      throw new Denied(403, "realm_not_enabled");
    }
    AccessToken token = auth.token();
    if (!config.provisionerClientId().equals(token.getIssuedFor())) {
      throw new Denied(403, "client");
    }
    ClientModel client = realm.getClientByClientId(config.provisionerClientId());
    if (client == null
        || !client.isEnabled()
        || client.isPublicClient()
        || client.isBearerOnly()
        || !client.isServiceAccountsEnabled()) {
      throw new Denied(403, "client_state");
    }
    // The authenticated user must be this client's live, enabled service account.
    UserModel serviceAccount = session.users().getServiceAccount(client);
    UserModel user = auth.user();
    if (serviceAccount == null
        || !serviceAccount.isEnabled()
        || !serviceAccount.getId().equals(user.getId())
        || !client.getId().equals(serviceAccount.getServiceAccountClientLink())) {
      throw new Denied(403, "service_account");
    }
    // Dedicated capability, checked on the live model (effective roles), never from claims.
    ClientModel capabilityClient = realm.getClientByClientId(ProvisioningConfig.CAPABILITY_CLIENT);
    RoleModel capability =
        capabilityClient == null
            ? null
            : capabilityClient.getRole(ProvisioningConfig.CAPABILITY_ROLE);
    if (capability == null || !serviceAccount.hasRole(capability)) {
      throw new Denied(403, "capability");
    }
    return new Caller(client, serviceAccount);
  }
}
