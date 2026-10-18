package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the payroll module may know about locked approved time to prepare payroll inputs (MVP-090A,
 * ADR 0023), implemented by {@code people}. Payroll never reads people's tables: it receives the
 * weeks that matter for a legal entity's sites and dates, each with its lock evidence, and the
 * approved rows of a locked generation. Identifiers, dates, versions, digests and minutes only: no
 * name, employee number, address, label, explanation or subject crosses this port.
 */
public interface LockedApprovedTime {

  /**
   * Serializes with every timesheet command of the tenant (the tenant's scheduling lock, lock order
   * step 1; MANDATORY: inside the caller's transaction) so that no lock, unlock, correction,
   * refresh or approval interleaves with a payroll-input generation.
   *
   * @param tenant verified tenant
   */
  void lockForPayrollInputs(TenantId tenant);

  /**
   * Every site-local week of the sites that intersects the inclusive dates and matters for payroll:
   * a week with a generation (whatever its state) or with any operational published shift. A week
   * with neither is not returned (nothing was worked, nothing is missing).
   *
   * @param tenant verified tenant
   * @param siteIds the legal entity's sites
   * @param from first date (inclusive)
   * @param to last date (inclusive)
   * @return the weeks, in site then week order
   */
  List<Week> weeks(TenantId tenant, Collection<UUID> siteIds, LocalDate from, LocalDate to);

  /**
   * The approved rows of a generation now: the READY rows of the current revision of every
   * timesheet that is APPROVED by its current action at that action's revision and cycle, each with
   * the legal entity its employment is placed in on the work date.
   *
   * @param tenant verified tenant
   * @param generationId generation
   * @return the rows, in no particular order
   */
  List<ApprovedRow> approvedRows(TenantId tenant, UUID generationId);

  /**
   * One site-local week.
   *
   * @param siteId site
   * @param weekStart the site-local Monday
   * @param timezone the site's zone as generated (or as scheduled)
   * @param scheduled whether any operational published shift lies in the week
   * @param generation the week's generation, or {@code null} when it was never generated
   */
  record Week(
      UUID siteId, LocalDate weekStart, String timezone, boolean scheduled, Generation generation) {

    /** Requires the site and the week. */
    public Week {
      Objects.requireNonNull(siteId, "siteId");
      Objects.requireNonNull(weekStart, "weekStart");
      Objects.requireNonNull(timezone, "timezone");
    }

    /** Identifiers only. */
    @Override
    public String toString() {
      return "LockedApprovedTime.Week[site=" + siteId + ", week=" + weekStart + "]";
    }
  }

  /**
   * A generated week as it stands now.
   *
   * @param id generation
   * @param version its current version
   * @param periodStartAt the week's exact start
   * @param periodEndAt the week's exact end (exclusive)
   * @param evidenceDigest the period evidence digest of its timesheets now
   * @param timesheets its timesheets
   * @param approvedTimesheets its timesheets APPROVED now by their current action
   * @param lock its current lock, or {@code null} when it was never locked
   */
  record Generation(
      UUID id,
      int version,
      Instant periodStartAt,
      Instant periodEndAt,
      String evidenceDigest,
      int timesheets,
      int approvedTimesheets,
      Lock lock) {

    /** Requires the identity and the digest. */
    public Generation {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(periodStartAt, "periodStartAt");
      Objects.requireNonNull(periodEndAt, "periodEndAt");
      Objects.requireNonNull(evidenceDigest, "evidenceDigest");
    }

    /** Whether the week is LOCKED now. */
    public boolean locked() {
      return lock != null && lock.locked();
    }

    /** Identifiers only. */
    @Override
    public String toString() {
      return "LockedApprovedTime.Generation[" + id + "]";
    }
  }

  /**
   * The current lock of a generation.
   *
   * @param locked whether the state is LOCKED (else OPEN after an unlock)
   * @param version the lock version
   * @param actionId the current period action
   * @param generationVersion the generation version the current action bound
   * @param evidenceDigest the evidence digest the current action bound
   * @param timesheets the locked timesheet count
   * @param rows the locked row count
   * @param workedMinutes the locked worked minutes
   */
  record Lock(
      boolean locked,
      int version,
      UUID actionId,
      int generationVersion,
      String evidenceDigest,
      int timesheets,
      int rows,
      int workedMinutes) {

    /** Requires the action and the digest. */
    public Lock {
      Objects.requireNonNull(actionId, "actionId");
      Objects.requireNonNull(evidenceDigest, "evidenceDigest");
    }

    /** Identifiers only. */
    @Override
    public String toString() {
      return "LockedApprovedTime.Lock[" + actionId + "]";
    }
  }

  /**
   * One approved timesheet row (shift) of a generation now.
   *
   * @param timesheetId timesheet
   * @param revision its current revision
   * @param approvalActionId its current APPROVED action
   * @param approvalCycle that action's cycle
   * @param employeeId employee
   * @param employmentId the employment of the row's day
   * @param employmentLegalEntityId the legal entity that employment is placed in on the work date,
   *     or {@code null} when it has none
   * @param costCenterId cost center, or {@code null}
   * @param workDate the shift's site-local date
   * @param shiftId shift
   * @param workedMinutes approved worked minutes
   */
  record ApprovedRow(
      UUID timesheetId,
      int revision,
      UUID approvalActionId,
      int approvalCycle,
      UUID employeeId,
      UUID employmentId,
      UUID employmentLegalEntityId,
      UUID costCenterId,
      LocalDate workDate,
      UUID shiftId,
      int workedMinutes) {

    /** Requires every identity and non-negative minutes. */
    public ApprovedRow {
      Objects.requireNonNull(timesheetId, "timesheetId");
      Objects.requireNonNull(approvalActionId, "approvalActionId");
      Objects.requireNonNull(employeeId, "employeeId");
      Objects.requireNonNull(employmentId, "employmentId");
      Objects.requireNonNull(workDate, "workDate");
      Objects.requireNonNull(shiftId, "shiftId");
      if (workedMinutes < 0 || revision < 1 || approvalCycle < 1) {
        throw new IllegalArgumentException("approved row out of range");
      }
    }

    /** Identifiers only. */
    @Override
    public String toString() {
      return "LockedApprovedTime.ApprovedRow[timesheet=" + timesheetId + ", shift=" + shiftId + "]";
    }
  }
}
