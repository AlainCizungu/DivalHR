/**
 * Payroll domain module.
 *
 * <p>MVP-090A (Issue #141): time-based payroll inputs generated from locked approved time, the
 * first increment of the payroll preparation release train (ADR 0023). The module owns the {@code
 * payroll} database schema (V37) and reads the people module's locked approved time only through
 * the platform port {@link com.divalhr.core.platform.access.LockedApprovedTime}; it never accesses
 * other modules' schemas or internal packages. No pay is calculated here.
 */
package com.divalhr.core.payroll;
