package com.divalhr.core.platform.tenancy;

/**
 * Verified tenant scope of the current caller. Only ever built from a validated access token; a
 * tenant ID supplied in a request body or path is never sufficient on its own.
 *
 * @param tenantId the caller's tenant
 */
public record TenantContext(TenantId tenantId) {}
