package com.divalhr.core.platform.tenancy;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Enforces that a resource belongs to the caller's verified tenant. Modules call this before
 * returning or mutating any tenant-owned record.
 */
@Component
public class TenantAccessGuard {

  private final TenantContextResolver resolver;

  /**
   * Creates the guard.
   *
   * @param resolver tenant context resolver
   */
  public TenantAccessGuard(TenantContextResolver resolver) {
    this.resolver = resolver;
  }

  /**
   * Verifies access to a tenant-owned resource.
   *
   * @param resourceTenant tenant that owns the resource
   * @return the verified caller context
   * @throws ApiException with {@link ErrorCode#TENANT_ACCESS_DENIED} on mismatch
   */
  public TenantContext requireAccessTo(TenantId resourceTenant) {
    TenantContext caller = resolver.current();
    if (!caller.tenantId().equals(resourceTenant)) {
      throw new ApiException(ErrorCode.TENANT_ACCESS_DENIED, Map.of());
    }
    return caller;
  }
}
