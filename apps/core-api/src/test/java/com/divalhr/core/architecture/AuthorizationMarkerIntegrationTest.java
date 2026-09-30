package com.divalhr.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.platform.security.PlatformScoped;
import com.divalhr.core.platform.security.PublicOperation;
import com.divalhr.core.platform.security.TenantScoped;
import com.divalhr.core.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every /api/v1 handler except the explicitly public status endpoint must declare exactly one
 * authorization scope, so no future endpoint (read or write) can silently skip both the
 * platform-admin check and tenant scoping. Tenant-scoped markers are found through meta-annotations
 * such as {@code @TenantAdminOperation}.
 */
@IntegrationTest
class AuthorizationMarkerIntegrationTest {

  private static final Set<RequestMethod> MUTATING =
      Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

  /**
   * Handlers outside both scopes: the public status endpoint (security: []) and the caller's own
   * session, which only echoes the verified token and reads no tenant data.
   */
  private static final Set<String> PUBLIC = Set.of("/api/v1/system/status", "/api/v1/session");

  /**
   * Anonymous endpoints (MVP-010): every handler under this prefix, and only those, carries {@code
   * PublicOperation} and neither scope marker.
   */
  private static final String PUBLIC_PREFIX = "/api/v1/public/";

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  @Test
  void everyNonPublicApiHandlerHasExactlyOneScope() {
    List<String> violations = new ArrayList<>();
    int checked = 0;
    for (var entry : mappings.getHandlerMethods().entrySet()) {
      RequestMappingInfo info = entry.getKey();
      boolean api = info.getPatternValues().stream().anyMatch(p -> p.startsWith("/api/v1"));
      if (!api || PUBLIC.containsAll(info.getPatternValues())) {
        continue;
      }
      checked++;
      boolean platform = entry.getValue().hasMethodAnnotation(PlatformScoped.class);
      boolean tenant = entry.getValue().hasMethodAnnotation(TenantScoped.class);
      boolean anonymous = entry.getValue().hasMethodAnnotation(PublicOperation.class);
      boolean publicPath =
          info.getPatternValues().stream().allMatch(p -> p.startsWith(PUBLIC_PREFIX));
      boolean valid =
          publicPath ? anonymous && !platform && !tenant : !anonymous && platform != tenant;
      if (!valid) {
        violations.add(info + " -> " + entry.getValue());
      }
    }
    assertThat(checked).as("scoped /api/v1 handlers found").isGreaterThanOrEqualTo(5);
    assertThat(violations).isEmpty();
  }

  @Test
  void everyMutatingApiHandlerHasExactlyOneScope() {
    List<String> violations = new ArrayList<>();
    int checked = 0;
    for (var entry : mappings.getHandlerMethods().entrySet()) {
      RequestMappingInfo info = entry.getKey();
      boolean api = info.getPatternValues().stream().anyMatch(p -> p.startsWith("/api/v1"));
      boolean mutating =
          info.getMethodsCondition().getMethods().stream().anyMatch(MUTATING::contains);
      if (!api || !mutating) {
        continue;
      }
      checked++;
      boolean platform = entry.getValue().hasMethodAnnotation(PlatformScoped.class);
      boolean tenant = entry.getValue().hasMethodAnnotation(TenantScoped.class);
      boolean anonymous = entry.getValue().hasMethodAnnotation(PublicOperation.class);
      boolean publicPath =
          info.getPatternValues().stream().allMatch(p -> p.startsWith(PUBLIC_PREFIX));
      boolean valid =
          publicPath ? anonymous && !platform && !tenant : !anonymous && platform != tenant;
      if (!valid) {
        violations.add(info + " -> " + entry.getValue());
      }
    }
    assertThat(checked).as("mutating /api/v1 handlers found").isPositive();
    assertThat(violations).isEmpty();
  }
}
