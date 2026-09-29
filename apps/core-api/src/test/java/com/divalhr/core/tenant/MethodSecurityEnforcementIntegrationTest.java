package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.divalhr.core.tenant.api.AssignSiteRegionRequest;
import com.divalhr.core.tenant.api.CostCenterController;
import com.divalhr.core.tenant.api.CreateCostCenterRequest;
import com.divalhr.core.tenant.api.CreateDepartmentRequest;
import com.divalhr.core.tenant.api.CreateLegalEntityRequest;
import com.divalhr.core.tenant.api.CreateOrganizationRequest;
import com.divalhr.core.tenant.api.CreateRegionRequest;
import com.divalhr.core.tenant.api.CreateSiteRequest;
import com.divalhr.core.tenant.api.DepartmentController;
import com.divalhr.core.tenant.api.LegalEntityController;
import com.divalhr.core.tenant.api.OrganizationController;
import com.divalhr.core.tenant.api.RegionController;
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
  @Autowired private DepartmentController departments;
  @Autowired private CostCenterController costCenters;
  @Autowired private RegionController regions;

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
      assertThatThrownBy(() -> regions.list(UUID.randomUUID().toString(), null, null))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  regions.create(
                      Organizations.newKey(),
                      CreateRegionRequest.of(
                          UUID.randomUUID().toString(), "AB", "Bypass", "2026-01-01", null),
                      caller,
                      new MockHttpServletRequest()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  sites.assignRegion(
                      UUID.randomUUID().toString(),
                      Organizations.newKey(),
                      AssignSiteRegionRequest.of(UUID.randomUUID().toString()),
                      caller,
                      new MockHttpServletRequest()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> departments.list(UUID.randomUUID().toString(), null, null))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> costCenters.list(UUID.randomUUID().toString(), null, null))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  departments.create(
                      Organizations.newKey(),
                      CreateDepartmentRequest.of(
                          UUID.randomUUID().toString(), "AB", "Bypass", "2026-01-01", null),
                      caller,
                      new MockHttpServletRequest()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  costCenters.create(
                      Organizations.newKey(),
                      CreateCostCenterRequest.of(
                          UUID.randomUUID().toString(), "AB", "Bypass", "2026-01-01", null),
                      caller,
                      new MockHttpServletRequest()))
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
