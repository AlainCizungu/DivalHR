package com.divalhr.core.platform.tenancy;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves the verified tenant context from the authenticated principal. */
@Component
public class TenantContextResolver {

  /**
   * Returns the caller's tenant context.
   *
   * @return verified tenant context
   * @throws ApiException with {@link ErrorCode#TENANT_CONTEXT_MISSING} if absent or malformed
   */
  public TenantContext current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken token)) {
      throw missing();
    }
    Object claim = token.getToken().getClaims().get(TenantClaims.TENANT_ID);
    if (!(claim instanceof String raw) || raw.isBlank()) {
      throw missing();
    }
    try {
      return new TenantContext(TenantId.parse(raw));
    } catch (IllegalArgumentException invalid) {
      throw missing();
    }
  }

  private static ApiException missing() {
    return new ApiException(ErrorCode.TENANT_CONTEXT_MISSING, Map.of());
  }
}
