package com.divalhr.core.identity.api;

import com.divalhr.core.platform.tenancy.TenantContext;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Returns the verified tenant and roles of the caller; proves end-to-end JWT validation. */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

  private final TenantContextResolver tenantContextResolver;

  /**
   * Creates the controller.
   *
   * @param tenantContextResolver resolver for the verified tenant
   */
  public SessionController(TenantContextResolver tenantContextResolver) {
    this.tenantContextResolver = tenantContextResolver;
  }

  /**
   * Returns the current session.
   *
   * @param authentication authenticated principal
   * @return tenant and roles; never names, emails or token contents
   */
  @Operation(operationId = "getCurrentSession")
  @GetMapping("/session")
  public CurrentSession currentSession(Authentication authentication) {
    TenantContext context = tenantContextResolver.current();
    List<String> roles =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(authority -> authority.startsWith("ROLE_"))
            .map(authority -> authority.substring("ROLE_".length()))
            .sorted()
            .toList();
    return new CurrentSession(context.tenantId().toString(), roles);
  }
}
