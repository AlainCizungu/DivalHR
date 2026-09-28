package com.divalhr.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

  private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

  @Test
  void mapsOnlyKnownRealmRoles() {
    Jwt jwt =
        jwt(
            Map.of(
                "realm_access",
                Map.of("roles", List.of("employee", "offline_access", "uma_authorization"))));
    assertThat(converter.convert(jwt))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_employee");
  }

  @Test
  void returnsNoAuthoritiesWhenClaimMissingOrMalformed() {
    assertThat(converter.convert(jwt(Map.of("scope", "openid")))).isEmpty();
    assertThat(converter.convert(jwt(Map.of("realm_access", "tenant-admin")))).isEmpty();
  }

  private static Jwt jwt(Map<String, Object> claims) {
    return Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .claims(c -> c.putAll(claims))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();
  }
}
