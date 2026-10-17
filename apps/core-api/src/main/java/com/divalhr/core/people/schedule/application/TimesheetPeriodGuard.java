package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.internal.JdbcTimesheetPeriodRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * MVP-071 shared domain guard (L71-5): every protected mutation of a generated week asks it, under
 * the tenant's scheduling lock, whether the week is locked, and is refused with the one stable code
 * {@code 409 TIMESHEET_PERIOD_LOCKED} when it is. Protected: committing an attendance correction of
 * a shift whose site-local date lies in the locked week, refreshing the generation, and submitting,
 * returning, approving or reopening any of its timesheets. Reads, exports and every operation
 * outside the exact generated week are not its concern. V36's triggers hold the same boundary
 * against writes that bypass the application.
 */
@Service
public class TimesheetPeriodGuard {

  private final JdbcTimesheetPeriodRepository periods;

  /**
   * Creates the guard.
   *
   * @param periods period lock storage
   */
  public TimesheetPeriodGuard(JdbcTimesheetPeriodRepository periods) {
    this.periods = periods;
  }

  /**
   * Refuses when the generation is locked. The caller holds the tenant's scheduling lock, so the
   * answer cannot change before its transaction ends.
   *
   * @param tenant verified tenant
   * @param generationId the generation a timesheet, action or refresh belongs to
   * @throws ApiException {@code TIMESHEET_PERIOD_LOCKED}
   */
  public void requireOpen(TenantId tenant, UUID generationId) {
    if (periods.lockState(tenant, generationId, false).locked()) {
      throw locked();
    }
  }

  /**
   * Refuses when a shift of the site lies in a locked week (the generation's zone decides the
   * site-local date). The caller holds the tenant's scheduling lock.
   *
   * @param tenant verified tenant
   * @param siteId the shift's site
   * @param shiftStart the shift's start
   * @throws ApiException {@code TIMESHEET_PERIOD_LOCKED}
   */
  public void requireOpenWeek(TenantId tenant, UUID siteId, Instant shiftStart) {
    if (periods.lockedWeekCovers(tenant, siteId, shiftStart)) {
      throw locked();
    }
  }

  /**
   * The one stable refusal of a locked period.
   *
   * @return the exception
   */
  public static ApiException locked() {
    return new ApiException(ErrorCode.TIMESHEET_PERIOD_LOCKED, Map.of());
  }
}
