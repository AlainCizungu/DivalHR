package com.divalhr.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.platform.security.PlatformScoped;
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
 * Every mutating /api/v1 handler must declare exactly one authorization scope, so no future
 * endpoint can silently skip both the platform-admin check and tenant scoping.
 */
@IntegrationTest
class AuthorizationMarkerIntegrationTest {

  private static final Set<RequestMethod> MUTATING =
      Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

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
      if (platform == tenant) {
        violations.add(info + " -> " + entry.getValue());
      }
    }
    assertThat(checked).as("mutating /api/v1 handlers found").isPositive();
    assertThat(violations).isEmpty();
  }
}
