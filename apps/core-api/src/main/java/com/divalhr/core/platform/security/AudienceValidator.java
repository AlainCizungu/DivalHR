package com.divalhr.core.platform.security;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Rejects tokens that were not issued for this API. */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

  private final String audience;

  /**
   * Creates the validator.
   *
   * @param audience required audience
   */
  public AudienceValidator(String audience) {
    this.audience = audience;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    List<String> audiences = token.getAudience();
    if (audiences != null && audiences.contains(audience)) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Required audience is missing", null));
  }
}
