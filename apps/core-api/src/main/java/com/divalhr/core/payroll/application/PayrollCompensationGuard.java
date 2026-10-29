package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.internal.JdbcCompensationRepository;
import com.divalhr.core.platform.access.CompensationEmploymentGuard;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Payroll's implementation of the {@link CompensationEmploymentGuard} port (MVP-093A, ADR 0009 and
 * ADR 0032): the first day of the employee's latest active compensation interval, so that a
 * separation can refuse to end an employment before it. A read; the separation already holds the
 * employment row, and every compensation command takes that row {@code FOR SHARE} first.
 */
@Component
public class PayrollCompensationGuard implements CompensationEmploymentGuard {

  private final JdbcCompensationRepository compensation;

  /**
   * Creates the adapter.
   *
   * @param compensation compensation storage
   */
  public PayrollCompensationGuard(JdbcCompensationRepository compensation) {
    this.compensation = compensation;
  }

  @Override
  public Optional<LocalDate> latestCompensationStart(TenantId tenant, UUID employeeId) {
    return compensation.latestActiveStart(tenant, employeeId);
  }
}
