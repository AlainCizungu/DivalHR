package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.divalhr.core.tenant.api.CreateOrganizationRequest;
import com.divalhr.core.tenant.api.OrganizationController;
import java.time.Instant;
import java.util.List;
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
 * Proves {@code @PlatformScoped}'s {@code @PreAuthorize} is live on the controller bean itself,
 * independently of the MVC interceptor that runs first on HTTP requests.
 */
@IntegrationTest
class MethodSecurityEnforcementIntegrationTest {

  @Autowired private OrganizationController controller;

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
}
