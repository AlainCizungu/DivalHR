package com.divalhr.core.support;

import com.divalhr.core.platform.security.TenantScoped;
import com.divalhr.core.platform.tenancy.TenantAccessGuard;
import com.divalhr.core.platform.tenancy.TenantContext;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only stand-in for a tenant-owned resource. Scaffold for the tenant-isolation suite that
 * every future module endpoint must join.
 */
@RestController
public class TenantProbeTestController {

  private final TenantAccessGuard guard;

  /**
   * Creates the controller.
   *
   * @param guard tenant access guard
   */
  public TenantProbeTestController(TenantAccessGuard guard) {
    this.guard = guard;
  }

  /**
   * Reads a resource owned by the tenant in the path.
   *
   * @param tenantId owning tenant
   * @return the verified tenant
   */
  @GetMapping("/test-support/tenants/{tenantId}/probe")
  public Map<String, String> read(@PathVariable UUID tenantId) {
    TenantContext context = guard.requireAccessTo(new TenantId(tenantId));
    return Map.of("tenantId", context.tenantId().toString());
  }

  /**
   * Simulates a create where the client names a tenant in the body.
   *
   * @param body request body containing {@code tenantId}
   * @return the verified tenant
   */
  @TenantScoped
  @PostMapping("/test-support/probes")
  public Map<String, String> create(@RequestBody Map<String, String> body) {
    TenantContext context = guard.requireAccessTo(TenantId.parse(body.get("tenantId")));
    return Map.of("tenantId", context.tenantId().toString());
  }
}
