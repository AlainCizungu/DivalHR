package com.divalhr.core.payroll.application;

import com.divalhr.core.platform.access.StatutoryEarningsGuard;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The shipped {@link StatutoryEarningsGuard} (MVP-094B, ADR 0034 section 10 (b)): no other earning
 * type exists in the data model (there is no allowance, benefit in kind, bonus, overtime-pay or
 * retro-pay record anywhere), so no employee has an unmodeled earning. The port is still consulted
 * by every preview and commit, so the day an earning type is introduced its module implements the
 * port and the affected employees are blocked.
 */
@Component
public class NoUnmodeledEarnings implements StatutoryEarningsGuard {

  @Override
  public Set<UUID> employeesWithUnmodeledEarnings(
      TenantId tenant, Collection<UUID> employeeIds, LocalDate first, LocalDate last) {
    return Set.of();
  }
}
