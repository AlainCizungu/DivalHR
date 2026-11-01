package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.util.UUID;

/**
 * What the payroll approval of the payroll module asks about payments (MVP-094C, ADR 0035 section
 * 12, decision D94C-5): whether any payment or disbursement record exists for an approved payroll
 * package. A package with a payment record can never be voided ({@code PAYROLL_VOID_NOT_ALLOWED}).
 *
 * <p>The shipped implementation reports none, because no payment, bank-file or mobile-money
 * integration exists in this release; a future payment module implements this port and the void
 * fails closed without any change to the approval service. The port reads, never writes, and takes
 * and returns identifiers and a boolean only.
 */
public interface PayrollPaymentGuard {

  /**
   * Whether a payment or disbursement record exists for the package.
   *
   * @param tenant verified tenant
   * @param legalEntityId the legal entity of the package
   * @param packageId the payroll package
   * @return {@code true} when any payment or disbursement record references the package
   */
  boolean paymentExists(TenantId tenant, UUID legalEntityId, UUID packageId);
}
