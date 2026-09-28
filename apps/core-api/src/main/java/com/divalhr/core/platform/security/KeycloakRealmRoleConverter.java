package com.divalhr.core.platform.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps Keycloak realm roles ({@code realm_access.roles}) to {@code ROLE_*} authorities. Only
 * roles on the allow-list are mapped so that Keycloak default roles never become privileges.
 */
public final class KeycloakRealmRoleConverter
    implements Converter<Jwt, Collection<GrantedAuthority>> {

  /** Roles recognised by DivalHR in Sprint 0. Placeholders without business permissions. */
  public static final List<String> KNOWN_ROLES =
      List.of("platform-admin", "tenant-admin", "employee");

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
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
