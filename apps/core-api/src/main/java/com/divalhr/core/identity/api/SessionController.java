package com.divalhr.core.identity.api;

import com.divalhr.core.identity.application.SessionRoles;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the verified tenant and effective roles of the caller (MVP-012A: token ∩ membership for
 * tenant roles; platform-admin from the token). Requires no MFA.
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

  private final SessionRoles sessions;

  /**
   * Creates the controller.
   *
   * @param sessions effective-session service
   */
  public SessionController(SessionRoles sessions) {
    this.sessions = sessions;
  }

  /**
   * Returns the current session.
   *
   * @param authentication authenticated principal
   * @return tenant and effective roles; never names, emails, token contents or reasons
   */
  @Operation(operationId = "getCurrentSession")
  @GetMapping("/session")
  public CurrentSession currentSession(Authentication authentication) {
    SessionRoles.Effective effective = sessions.of(authentication);
    return new CurrentSession(
        effective.tenant().map(tenant -> tenant.value().toString()).orElse(null),
        effective.roles());
  }
}
