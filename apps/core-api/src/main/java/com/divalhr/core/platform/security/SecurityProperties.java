package com.divalhr.core.platform.security;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OIDC resource-server settings.
 *
 * @param issuer exact {@code iss} value accepted in access tokens
 * @param audience required {@code aud} value for this API
 * @param jwkSetUri URI from which signing keys are fetched (may differ from the issuer host when
 *     running inside a container network)
 */
@ConfigurationProperties("divalhr.security")
public record SecurityProperties(String issuer, String audience, String jwkSetUri) {

  /** Fails fast on missing configuration. */
  public SecurityProperties {
    Objects.requireNonNull(issuer, "divalhr.security.issuer is required");
    Objects.requireNonNull(audience, "divalhr.security.audience is required");
    Objects.requireNonNull(jwkSetUri, "divalhr.security.jwk-set-uri is required");
  }
}
