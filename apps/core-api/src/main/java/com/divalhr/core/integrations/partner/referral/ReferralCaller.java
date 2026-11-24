package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.platform.tenancy.TenantId;

/**
 * The verified employee making a referral request. The tenant comes from the verified token, never
 * from the request.
 *
 * @param tenant verified tenant
 * @param subject verified subject
 * @param correlationId request correlation id
 */
public record ReferralCaller(TenantId tenant, String subject, String correlationId) {

  @Override
  public String toString() {
    return "ReferralCaller[redacted]";
  }
}
