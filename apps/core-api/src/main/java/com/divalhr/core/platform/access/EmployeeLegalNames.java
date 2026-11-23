package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;

/**
 * The employee's legal name, for the partner referral preview (MVP-120F, ADR 0045). Implemented by
 * {@code people}; read-only and tenant-bound. It returns the name only: never a number, a contract
 * or any other HR value. The value is Confidential personal data and is never logged.
 */
public interface EmployeeLegalNames {

  /**
   * The employee's given names and family name, in that order, separated by one space.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the legal name, or empty when the employee does not exist in the tenant
   */
  Optional<String> legalName(TenantId tenant, UUID employeeId);
}
