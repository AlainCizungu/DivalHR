package com.divalhr.core.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Proves where a denial came from (MVP-013, A13-1). Spring method security hands this publisher
 * every decision it takes on a method invocation; when it denies a method carrying {@link
 * PlatformScoped} or {@link TenantScoped}, the current request is marked. Only a marked request can
 * produce a {@code method_security} denial row and a drift alert: a plain {@code
 * AccessDeniedException} thrown by a controller, service or framework component is never taken for
 * method-security drift. No stack trace, exception message or method argument is inspected.
 *
 * <p>No application event is published, so behaviour outside this marker is unchanged; decisions on
 * anything other than a method invocation (for example the HTTP filter chain) are ignored.
 */
@Component
public class MethodSecurityDenialMarker implements AuthorizationEventPublisher {

  private static final String ATTRIBUTE = MethodSecurityDenialMarker.class.getName();

  @Override
  public <T> void publishAuthorizationEvent(
      Supplier<Authentication> authentication, T object, AuthorizationResult result) {
    if (result == null || result.isGranted() || !(object instanceof MethodInvocation invocation)) {
      return;
    }
    if (!privileged(invocation.getMethod())) {
      return;
    }
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes current) {
      current.getRequest().setAttribute(ATTRIBUTE, Boolean.TRUE);
    }
  }

  /**
   * Whether method security itself denied a privileged handler during this request.
   *
   * @param request current request
   * @return true only when this publisher marked the request
   */
  public static boolean deniedByMethodSecurity(HttpServletRequest request) {
    return Boolean.TRUE.equals(request.getAttribute(ATTRIBUTE));
  }

  private static boolean privileged(Method method) {
    return AnnotatedElementUtils.hasAnnotation(method, PlatformScoped.class)
        || AnnotatedElementUtils.hasAnnotation(method, TenantScoped.class);
  }
}
