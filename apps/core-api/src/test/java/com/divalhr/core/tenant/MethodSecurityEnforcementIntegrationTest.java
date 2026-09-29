package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.divalhr.core.tenant.api.CreateLegalEntityRequest;
import com.divalhr.core.tenant.api.CreateOrganizationRequest;
import com.divalhr.core.tenant.api.CreateSiteRequest;
import com.divalhr.core.tenant.api.LegalEntityController;
import com.divalhr.core.tenant.api.OrganizationController;
import com.divalhr.core.tenant.api.SiteController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Proves the {@code @PreAuthorize} of {@code @PlatformScoped} and {@code @TenantAdminOperation} is
 * live on the controller beans themselves, independently of the MVC interceptor that runs first on
 * HTTP requests.
 */
@IntegrationTest
class MethodSecurityEnforcementIntegrationTest {

  @Autowired private OrganizationController controller;
  @Autowired private LegalEntityController legalEntities;
  @Autowired private SiteController sites;

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void preAuthorizeRejectsNonPlatformAdminsOnTheBeanProxy() {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("sub-method-security")
            .claim("tenant_id", TestTokens.TENANT_A.toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    JwtAuthenticationToken tenantAdmin =
        new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_tenant-admin")));
    SecurityContextHolder.getContext().setAuthentication(tenantAdmin);

    assertThatThrownBy(
            () ->
                controller.create(
                    Organizations.newKey(),
                    CreateOrganizationRequest.of(
                        "Bypass attempt", "CD", "fr", "Africa/Kinshasa", List.of("CDF")),
                    tenantAdmin,
                    new MockHttpServletRequest()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void preAuthorizeRejectsNonTenantAdminsOnHierarchyBeans() {
    for (String role : List.of("ROLE_employee", "ROLE_platform-admin")) {
      Jwt jwt =
          Jwt.withTokenValue("t")
              .header("alg", "RS256")
              .subject("sub-method-security-" + role)
              .claim("tenant_id", TestTokens.TENANT_A.toString())
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(60))
              .build();
      JwtAuthenticationToken caller =
          new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
      SecurityContextHolder.getContext().setAuthentication(caller);

      assertThatThrownBy(() -> legalEntities.list(null, null))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  legalEntities.create(
                      Organizations.newKey(),
                      CreateLegalEntityRequest.of("AB", "Bypass", "CD", "2026-01-01", null),
                      caller,
                      new MockHttpServletRequest()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> sites.list(UUID.randomUUID().toString(), null, null))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  sites.create(
                      Organizations.newKey(),
                      CreateSiteRequest.of(
                          UUID.randomUUID().toString(),
                          "AB",
                          "Bypass",
                          "Africa/Kinshasa",
                          "2026-01-01",
                          null),
                      caller,
                      new MockHttpServletRequest()))
          .isInstanceOf(AccessDeniedException.class);
    }
  }
}
