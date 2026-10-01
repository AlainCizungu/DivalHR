package com.divalhr.core.identity.application;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.tenancy.MembershipAuthority;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * Effective session of the caller (MVP-012A, architect decision on #37, A2, M3 and A12A-1).
 *
 * <ul>
 *   <li>A tenant role is effective only when the token carries it and the caller's active
 *       membership belongs to the token's verified tenant with that same role.
 *   <li>{@code platform-admin} comes from the token alone and is never derived from a membership; a
 *       platform administrator's tenant claim grants no tenant role by itself.
 *   <li>A platform-administrator token without a valid tenant claim gets a session with a {@code
 *       null} tenant; any other token without one keeps {@code TENANT_CONTEXT_MISSING}.
 * </ul>
 *
 * <p>The result never reveals why a role is missing (token, membership, identity-provider state or
 * marker role). Platform-only tokens never trigger a membership lookup.
 */
@Service
public class SessionRoles {

  /** The platform role. */
  public static final String PLATFORM_ADMIN = "platform-admin";

  private static final Set<String> TENANT_ROLES = Set.of("tenant-admin", "employee");

  private final TenantContextResolver tenants;
  private final MembershipAuthority memberships;

  /**
   * Creates the service.
   *
   * @param tenants verified tenant resolver
   * @param memberships tenant access authority
   */
  public SessionRoles(TenantContextResolver tenants, MembershipAuthority memberships) {
    this.tenants = tenants;
    this.memberships = memberships;
  }

  /**
   * The caller's effective session.
   *
   * @param authentication authenticated principal
   * @return verified tenant (or empty for a tenantless platform administrator) and sorted roles
   * @throws ApiException {@code TENANT_CONTEXT_MISSING} for a non-platform token without a tenant
   */
  public Effective of(Authentication authentication) {
    List<String> tokenRoles =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(authority -> authority.startsWith("ROLE_"))
            .map(authority -> authority.substring("ROLE_".length()))
            .toList();
    boolean platform = tokenRoles.contains(PLATFORM_ADMIN);
    Optional<TenantId> tenant;
    try {
      tenant = Optional.of(tenants.current().tenantId());
    } catch (ApiException missing) {
      if (!platform) {
        throw missing;
      }
      tenant = Optional.empty();
    }
    List<String> roles = new ArrayList<>();
    if (platform) {
      roles.add(PLATFORM_ADMIN);
    }
    List<String> claimedTenantRoles = tokenRoles.stream().filter(TENANT_ROLES::contains).toList();
    String subject = subject(authentication);
    if (tenant.isPresent() && !claimedTenantRoles.isEmpty() && subject != null) {
      TenantId verified = tenant.get();
      memberships
          .find(subject)
          .filter(membership -> membership.tenant().equals(verified))
          .filter(membership -> claimedTenantRoles.contains(membership.role()))
          .ifPresent(membership -> roles.add(membership.role()));
    }
    return new Effective(tenant, roles.stream().sorted().toList());
  }

  private static String subject(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken token) {
      String subject = token.getToken().getSubject();
      return subject == null || subject.isBlank() ? null : subject;
    }
    return null;
  }

  /**
   * An effective session.
   *
   * @param tenant verified tenant, empty only for a tenantless platform administrator
   * @param roles effective roles, sorted
   */
  public record Effective(Optional<TenantId> tenant, List<String> roles) {

    /** Defensively copies the roles. */
    public Effective {
      roles = List.copyOf(roles);
    }
  }
}
