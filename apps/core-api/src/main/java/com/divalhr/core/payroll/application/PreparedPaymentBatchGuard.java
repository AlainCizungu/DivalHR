package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.internal.JdbcPaymentRepository;
import com.divalhr.core.platform.access.PayrollPaymentGuard;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The payroll void guard of MVP-095A (ADR 0036): a package that has a PREPARED payment batch cannot
 * be voided. Cancelling the batch releases it. A cancelled batch is evidence only and blocks
 * nothing. The database enforces the same rule with a guard on the package row, so a lost race
 * cannot void a package under a prepared batch. This replaces the MVP-094C placeholder that always
 * answered {@code false}.
 */
@Component
public class PreparedPaymentBatchGuard implements PayrollPaymentGuard {

  private final JdbcPaymentRepository payments;

  /**
   * Creates the guard.
   *
   * @param payments payment storage
   */
  public PreparedPaymentBatchGuard(JdbcPaymentRepository payments) {
    this.payments = payments;
  }

  @Override
  public boolean paymentExists(TenantId tenant, UUID legalEntityId, UUID packageId) {
    return payments.preparedExists(tenant, legalEntityId, packageId);
  }
}
