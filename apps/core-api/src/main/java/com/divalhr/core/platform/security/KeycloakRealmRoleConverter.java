package com.divalhr.core.platform.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps Keycloak realm roles ({@code realm_access.roles}) to {@code ROLE_*} authorities. Only roles
 * on the allow-list are mapped so that Keycloak default roles never become privileges.
 */
public final class KeycloakRealmRoleConverter
    implements Converter<Jwt, Collection<GrantedAuthority>> {

  /** Roles recognised by DivalHR in Sprint 0. Placeholders without business permissions. */
  public static final List<String> KNOWN_ROLES =
      List.of("platform-admin", "tenant-admin", "employee");

  /**
   * Maps only the known realm roles (never Keycloak's internal roles, such as the MFA marker role)
   * and adds {@link AssuranceEvidence#MFA_AUTHORITY} only when the token proves MFA.
   *
   * @param jwt verified access token
   * @return granted authorities
   */
  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    List<GrantedAuthority> authorities = new ArrayList<>(roles(jwt));
    if (AssuranceEvidence.provesMfa(jwt)) {
      authorities.add(new SimpleGrantedAuthority(AssuranceEvidence.MFA_AUTHORITY));
    }
    return List.copyOf(authorities);
  }

  private static List<GrantedAuthority> roles(Jwt jwt) {
    Object realmAccess = jwt.getClaims().get("realm_access");
    if (!(realmAccess instanceof Map<?, ?> access)) {
      return List.of();
    }
    Object roles = access.get("roles");
    if (!(roles instanceof Collection<?> values)) {
      return List.of();
    }
    return values.stream()
        .map(String::valueOf)
        .filter(KNOWN_ROLES::contains)
        .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
        .toList();
  }
}
