package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.util.UUID;

/**
 * What {@code identity} may know about an employee when linking DivalHR access to it (MVP-022).
 * Implemented by {@code people}; read-only and tenant-bound. No name, number or HR value is ever
 * returned.
 */
public interface EmployeeRecords {

  /**
   * Whether the employee exists in the tenant (foreign and unknown alike are absent).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return true when it exists
   */
  boolean exists(TenantId tenant, UUID employeeId);

  /**
   * Whether the employee has a separation that is not cancelled. The caller holds the per-tenant
   * access-link lock, which every separation also takes before reading the link, so the answer
   * cannot go stale before the caller commits.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return true when separated or scheduled to be
   */
  boolean separated(TenantId tenant, UUID employeeId);
}
