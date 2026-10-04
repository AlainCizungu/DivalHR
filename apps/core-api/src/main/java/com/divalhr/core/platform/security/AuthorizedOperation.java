package com.divalhr.core.platform.security;

import com.divalhr.core.platform.audit.AuthorizationDenial.Scope;
import com.divalhr.core.platform.tenancy.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;

/**
 * Set on the request by {@link ScopeAuthorizationInterceptor} once a privileged request has passed
 * every check (MVP-013). Later stages (the per-subject limit, method security) use it for the
 * operation, scope and effective tenant of their denial evidence; nothing here comes from the
 * request itself.
 *
 * @param scope scope
 * @param operation the handler's operation name (possibly empty)
 * @param effectiveTenant the tenant confirmed by token and membership; {@code null} for platform
 * @param privileged whether the operation requires a privileged role (MVP-013 durable denial
 *     evidence applies only then; A30-1)
 */
public record AuthorizedOperation(
    Scope scope, String operation, TenantId effectiveTenant, boolean privileged) {

  private static final String ATTRIBUTE = AuthorizedOperation.class.getName();

  /** Validates the components. */
  public AuthorizedOperation {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(operation, "operation");
    if ((scope == Scope.PLATFORM) != (effectiveTenant == null)) {
      throw new IllegalArgumentException("effective tenant only for tenant scope");
    }
  }

  void bind(HttpServletRequest request) {
    request.setAttribute(ATTRIBUTE, this);
  }

  /**
   * The request's authorized operation.
   *
   * @param request current request
   * @return the operation, or {@code null} when the interceptor did not authorize this request
   */
  public static AuthorizedOperation of(HttpServletRequest request) {
    return request.getAttribute(ATTRIBUTE) instanceof AuthorizedOperation authorized
        ? authorized
        : null;
  }
}
