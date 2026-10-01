package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.api.CreateInvitationRequest;
import com.divalhr.core.identity.api.InvitationController;
import com.divalhr.core.platform.security.AssuranceEvidence;
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
import com.divalhr.core.tenant.api.CreateTeamRequest;
import com.divalhr.core.tenant.api.DepartmentController;
import com.divalhr.core.tenant.api.LegalEntityController;
import com.divalhr.core.tenant.api.OrganizationController;
import com.divalhr.core.tenant.api.RegionController;
import com.divalhr.core.tenant.api.SiteController;
import com.divalhr.core.tenant.api.TeamController;
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
  @Autowired private TeamController teams;
  @Autowired private InvitationController invitations;

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
        new JwtAuthenticationToken(
            jwt,
            List.of(
                new SimpleGrantedAuthority("ROLE_tenant-admin"),
                new SimpleGrantedAuthority(AssuranceEvidence.MFA_AUTHORITY)));
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
  void preAuthorizeRejectsPlatformAdminsWithoutMfaAssurance() {
    JwtAuthenticationToken platformAdmin =
        caller("sub-method-security-pwd", List.of("ROLE_platform-admin"));
    SecurityContextHolder.getContext().setAuthentication(platformAdmin);

    assertThatThrownBy(
            () ->
                controller.create(
                    Organizations.newKey(),
                    CreateOrganizationRequest.of(
                        "Bypass attempt", "CD", "fr", "Africa/Kinshasa", List.of("CDF")),
                    platformAdmin,
                    new MockHttpServletRequest()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void preAuthorizeRejectsTenantAdminsWithoutMfaAssurance() {
    JwtAuthenticationToken tenantAdmin =
        caller("sub-method-security-tenant-pwd", List.of("ROLE_tenant-admin"));
    SecurityContextHolder.getContext().setAuthentication(tenantAdmin);

    assertThatThrownBy(() -> legalEntities.list(null, null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> invitations.list(null, null, null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> teams.list(UUID.randomUUID().toString(), null, null, null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void preAuthorizeAdmitsTenantAdminsWithMfaAssurance() {
    JwtAuthenticationToken tenantAdmin =
        caller(
            "sub-method-security-tenant-mfa",
            List.of("ROLE_tenant-admin", AssuranceEvidence.MFA_AUTHORITY));
    SecurityContextHolder.getContext().setAuthentication(tenantAdmin);

    assertThatNoException().isThrownBy(() -> legalEntities.list(null, null));
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
      // Even with the assurance authority, the wrong role is denied.
      JwtAuthenticationToken caller =
          new JwtAuthenticationToken(
              jwt,
              List.of(
                  new SimpleGrantedAuthority(role),
                  new SimpleGrantedAuthority(AssuranceEvidence.MFA_AUTHORITY)));
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
      assertThatThrownBy(() -> teams.list(UUID.randomUUID().toString(), null, null, null))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> invitations.list(null, null, null))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  invitations.create(
                      Organizations.newKey(),
                      CreateInvitationRequest.of("bypass@example.test", "employee", "fr"),
                      caller,
                      new MockHttpServletRequest()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  invitations.revoke(
                      UUID.randomUUID().toString(), caller, new MockHttpServletRequest()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  invitations.resend(
                      UUID.randomUUID().toString(),
                      Organizations.newKey(),
                      caller,
                      new MockHttpServletRequest()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  teams.create(
                      Organizations.newKey(),
                      CreateTeamRequest.of(
                          UUID.randomUUID().toString(), null, "AB", "Bypass", "2026-01-01", null),
                      caller,
                      new MockHttpServletRequest()))
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

  private static JwtAuthenticationToken caller(String subject, List<String> authorities) {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject(subject)
            .claim("tenant_id", TestTokens.TENANT_A.toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    return new JwtAuthenticationToken(
        jwt, authorities.stream().map(SimpleGrantedAuthority::new).toList());
  }
}
