package com.divalhr.keycloak.provisioning;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

/** Serves {@link ProvisioningResource} under {@code /realms/{realm}/divalhr-provisioning}. */
public final class ProvisioningResourceProvider implements RealmResourceProvider {

  private final KeycloakSession session;
  private final ProvisioningConfig config;

  /**
   * Creates the provider for one request.
   *
   * @param session Keycloak session
   * @param config extension configuration
   */
  public ProvisioningResourceProvider(KeycloakSession session, ProvisioningConfig config) {
    this.session = session;
    this.config = config;
  }

  @Override
  public Object getResource() {
    return new ProvisioningResource(session, config);
  }

  @Override
  public void close() {
    // Nothing to release.
  }
}
