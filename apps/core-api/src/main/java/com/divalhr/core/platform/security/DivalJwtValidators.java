package com.divalhr.core.platform.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/** Token validation policy shared by production wiring and tests. */
public final class DivalJwtValidators {

  private DivalJwtValidators() {}

  /**
   * Builds the validator: signature is checked by the decoder; this adds expiry, not-before,
   * exact issuer and audience checks.
   *
   * @param properties security properties
   * @return composed validator
   */
  public static OAuth2TokenValidator<Jwt> create(SecurityProperties properties) {
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(properties.issuer()),
        new AudienceValidator(properties.audience()));
  }
}
