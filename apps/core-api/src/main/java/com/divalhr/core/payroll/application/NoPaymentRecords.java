package com.divalhr.core.payroll.application;

import com.divalhr.core.platform.access.PayrollPaymentGuard;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The shipped {@link PayrollPaymentGuard} (MVP-094C, ADR 0035 section 12): no payment, bank-file or
 * mobile-money record exists anywhere in the data model, so no package has a payment. The port is
 * still consulted by every void preview and commit, so the day a payment record is introduced its
 * module implements the port and the void fails closed.
 */
@Component
public class NoPaymentRecords implements PayrollPaymentGuard {

  @Override
  public boolean paymentExists(TenantId tenant, UUID legalEntityId, UUID packageId) {
    return false;
  }
}
