package com.divalhr.keycloak.provisioning;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.common.Version;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/**
 * Registers the extension as {@code divalhr-provisioning} (internal SPI {@code
 * realm-restapi-extension}, ADR 0006). Start-up fails on a Keycloak version other than the one the
 * extension was built for, and on invalid options.
 */
public final class ProvisioningResourceProviderFactory implements RealmResourceProviderFactory {

  /** Provider ID: the URL segment and the SPI option prefix. */
  public static final String ID = "divalhr-provisioning";

  private static final Logger LOG = Logger.getLogger("divalhr.provisioning");

  private ProvisioningConfig config;

  @Override
  public RealmResourceProvider create(KeycloakSession session) {
    return new ProvisioningResourceProvider(session, config);
  }

  @Override
  public void init(Config.Scope scope) {
    VersionGuard.require(VersionGuard.builtFor(), Version.VERSION);
    config =
        new ProvisioningConfig(
            ProvisioningConfig.parseRealms(scope.get("realms")),
            scope.get("provisioner-client-id", "divalhr-core-provisioner"),
            scope.get("web-client-id", "divalhr-web"),
            scope.get("web-redirect-uri"),
            scope.getInt("action-lifespan-seconds", 24 * 3600));
    if (config.realms().isEmpty()) {
      LOG.warn("divalhr-provisioning is enabled in no realm (option realms is empty)");
    } else {
      LOG.infof("divalhr-provisioning enabled for realms %s", config.realms());
    }
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {
    // Nothing to do.
  }

  @Override
  public void close() {
    // Nothing to release.
  }

  @Override
  public String getId() {
    return ID;
  }
}
