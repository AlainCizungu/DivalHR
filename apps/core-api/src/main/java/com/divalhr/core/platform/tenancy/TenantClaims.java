package com.divalhr.core.platform.tenancy;

/** Token claim names used for tenant scope. */
public final class TenantClaims {

  /** Claim carrying the tenant UUID, set by an identity-provider mapper. */
  public static final String TENANT_ID = "tenant_id";

  private TenantClaims() {}
}
