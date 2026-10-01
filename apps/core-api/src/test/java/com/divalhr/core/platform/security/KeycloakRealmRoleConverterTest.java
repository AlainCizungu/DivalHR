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

  @Test
  void addsTheAssuranceAuthorityOnlyForTheExactMfaAcr() {
    Map<String, Object> admin = Map.of("realm_access", Map.of("roles", List.of("tenant-admin")));
    assertThat(converter.convert(jwt(with(admin, "acr", "urn:divalhr:loa:mfa"))))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("ROLE_tenant-admin", AssuranceEvidence.MFA_AUTHORITY);
    for (Object acr :
        List.of(
            "urn:divalhr:loa:pwd",
            "0",
            "1",
            "2",
            "URN:DIVALHR:LOA:MFA",
            " urn:divalhr:loa:mfa",
            "urn:divalhr:loa:mfa ",
            "urn:divalhr:loa:mfa2",
            "mfa",
            "",
            2,
            List.of("urn:divalhr:loa:mfa"),
            Map.of("value", "urn:divalhr:loa:mfa"))) {
      assertThat(converter.convert(jwt(with(admin, "acr", acr))))
          .as("acr %s", acr)
          .extracting(GrantedAuthority::getAuthority)
          .containsExactly("ROLE_tenant-admin");
    }
    assertThat(converter.convert(jwt(admin)))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_tenant-admin");
  }

  @Test
  void neverDerivesAssuranceFromAmrOrTheMarkerRole() {
    Map<String, Object> claims =
        Map.of(
            "realm_access",
            Map.of("roles", List.of("platform-admin", "divalhr-privileged-mfa")),
            "amr",
            List.of("pwd", "otp"),
            "acr",
            "urn:divalhr:loa:pwd");
    assertThat(converter.convert(jwt(claims)))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_platform-admin");
  }

  @Test
  void theAssuranceAuthorityCannotBeInjectedAsARole() {
    Map<String, Object> claims =
        Map.of("realm_access", Map.of("roles", List.of("ASSURANCE_MFA", "tenant-admin")));
    assertThat(converter.convert(jwt(claims)))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_tenant-admin");
  }

  private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
    Map<String, Object> copy = new java.util.HashMap<>(base);
    copy.put(key, value);
    return copy;
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
