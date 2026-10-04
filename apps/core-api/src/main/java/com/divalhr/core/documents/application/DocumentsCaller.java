package com.divalhr.core.documents.application;

import com.divalhr.core.platform.tenancy.TenantId;

/**
 * The verified caller of an MVP-030 operation: tenant and subject come only from the verified JWT.
 *
 * @param tenant verified tenant (confirmed by an active membership)
 * @param subject verified subject (never logged)
 * @param correlationId correlation ID
 */
public record DocumentsCaller(TenantId tenant, String subject, String correlationId) {

  @Override
  public String toString() {
    return "DocumentsCaller[" + tenant + "]";
  }
}
